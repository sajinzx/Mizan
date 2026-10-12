package com.sajin.mizan.chain.bft;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.BlockHeader;
import com.sajin.mizan.chain.CommitCert;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.ValidatorSet;
import com.sajin.mizan.chain.Votes;
import com.sajin.mizan.chain.bft.ConsensusMessage.Commit;
import com.sajin.mizan.chain.bft.ConsensusMessage.Proposal;
import com.sajin.mizan.chain.bft.ConsensusMessage.SyncRequest;
import com.sajin.mizan.chain.bft.ConsensusMessage.SyncResponse;
import com.sajin.mizan.chain.bft.ConsensusMessage.Tx;
import com.sajin.mizan.chain.bft.ConsensusMessage.Vote;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tendermint-lite consensus for one validator (§6.8, docs/protocol.md). Implements the rules of
 * Algorithm 1 (L11–L65) as a single-threaded state machine: every input ({@link #onMessage},
 * scheduled timeouts, {@link #valueReady}) must arrive on the same thread. The same code runs under
 * the simulator and over TCP.
 *
 * <p>Safety-critical choices:
 *
 * <ul>
 *   <li>before any proposal or vote is sent, the new {@link SafetyState} is saved; a message that
 *       would contradict what this validator signed earlier is never signed (crash safety);
 *   <li>messages are acted on only after their signature and validator index verify;
 *   <li>conflicting messages from one validator are reported as equivocation evidence and the
 *       second one is ignored.
 * </ul>
 *
 * <p>Liveness helpers: own current-height messages are retransmitted periodically, and a validator
 * that hears from a peer at a lower height replies with the decided block and its certificate, so
 * the peer can catch up.
 */
public final class RoundBft {

  /** Steps within a round. */
  public enum Step {
    PROPOSE,
    PREVOTE,
    PRECOMMIT
  }

  /** The replicated application: produces, validates and applies blocks. */
  public interface App {
    /** A block to propose at {@code (height, round)}, or {@code null} if none is ready yet. */
    Block propose(long height, int round, byte[] prevHash, long prevTimestampMillis);

    /** Application validity of a block for the current height (live voting). */
    boolean valid(Block block);

    /** Application validity of a block decided by others (sync mode; cert already verified). */
    boolean validCommitted(CommittedBlock committed);

    /** Applies a decided block durably. Called once per height, in order. */
    void decide(CommittedBlock committed);

    /** A previously decided block, for catching up a lagging peer; {@code null} if unknown. */
    CommittedBlock committed(long height);

    /** A gossiped client command (M8). Ignored by default. */
    default void onTx(int from, byte[] envelope) {}
  }

  /** Observations for metrics, logs and tests. */
  public interface Observer {
    default void equivocation(
        int validator, long height, int round, String kind, byte[] a, byte[] b) {}

    default void roundStarted(long height, int round) {}

    default void decided(long height, int round) {}

    /** Blocks from {@code peer} were applied by sync; this validator is now at {@code height}. */
    default void synced(int peer, long height, int blocks) {}

    /**
     * {@code peer} served an invalid block during sync and is ignored until {@code untilMillis}.
     */
    default void peerBanned(int peer, long untilMillis) {}
  }

  /** Who proposes at {@code (height, round)}. */
  @FunctionalInterface
  public interface ProposerFn {
    int proposer(long height, int round);

    /** Rotation {@code (height + round) mod n} (§6.8, M8). */
    static ProposerFn rotating(ValidatorSet vs) {
      return vs::proposer;
    }

    /** A fixed proposer (M7). */
    static ProposerFn fixed(int index) {
      return (h, r) -> index;
    }
  }

  /** Static configuration. */
  public record Config(
      int me,
      ValidatorSet validators,
      MessageSigner signer,
      SignatureVerifier verifier,
      Network network,
      Scheduler scheduler,
      App app,
      SafetyStore safety,
      ProposerFn proposerFn,
      long proposeTimeoutMs,
      long timeoutDeltaMs,
      Observer observer) {}

  private record VoteKey(int type, int round) {}

  private final Config cfg;
  private final ValidatorSet vs;
  private final int chainId;

  // ---- consensus state for the current height ----
  private long height;
  private byte[] prevHash;
  private long prevTimestamp;
  private int round;
  private Step step;
  private int lockedRound = -1;
  private Block lockedBlock;
  private int validRound = -1;
  private Block validBlock;
  private boolean proposalPending;
  private SafetyState.Signed lastProposal;
  private SafetyState.Signed lastPrevote;
  private SafetyState.Signed lastPrecommit;

  // ---- message log for the current height ----
  private final TreeMap<Integer, Proposal> proposals = new TreeMap<>();
  private final Map<VoteKey, Map<Integer, Vote>> votes = new HashMap<>();
  private final Map<Integer, Set<Integer>> seenAtRound = new HashMap<>();
  private final Set<Integer> prevoteTimeoutStarted = new HashSet<>();
  private final Set<Integer> precommitTimeoutStarted = new HashSet<>();
  private final Set<Integer> lockStepDone = new HashSet<>();
  private final Map<ByteBuffer, Boolean> validity = new HashMap<>();
  private final List<byte[]> ownMessages = new ArrayList<>();
  private final List<byte[]> nextHeight = new ArrayList<>();
  private final Map<Integer, Long> lastCatchUpReply = new HashMap<>();
  private final Map<Integer, Long> lastSyncRequest = new HashMap<>();
  private final Map<Integer, Long> bannedUntil = new HashMap<>();
  private long syncedBlocks;
  private Scheduler.Cancellable proposeTimer;
  private boolean started;

  public RoundBft(Config cfg, long height, byte[] prevHash, long prevTimestampMillis) {
    this.cfg = Objects.requireNonNull(cfg, "cfg");
    this.vs = cfg.validators();
    this.chainId = vs.chainId();
    this.height = height;
    this.prevHash = prevHash.clone();
    this.prevTimestamp = prevTimestampMillis;
  }

  // ------------------------------------------------------------------ inputs

  /** Restores the safety state (if it is for this height) and starts the first round. */
  public void start() {
    if (started) {
      throw new IllegalStateException("already started");
    }
    started = true;
    int startRound = 0;
    SafetyState s = cfg.safety().load();
    if (s != null) {
      lastProposal = s.proposal();
      lastPrevote = s.prevote();
      lastPrecommit = s.precommit();
      if (s.height() == height) {
        startRound = s.round();
        lockedRound = s.lockedRound();
        lockedBlock = s.lockedBlock();
      }
    }
    scheduleRetransmit();
    startRound(startRound);
    check();
  }

  /** The application now has a value to propose. */
  public void valueReady() {
    if (proposalPending && step == Step.PROPOSE && isProposer(round)) {
      tryPropose();
      check();
    }
  }

  public void onMessage(int from, byte[] bytes) {
    ConsensusMessage m;
    try {
      m = ConsensusMessage.decode(bytes);
    } catch (ProtocolException e) {
      return; // malformed: ignore
    }
    switch (m) {
      case Proposal p -> onProposal(from, p, bytes);
      case Vote v -> onVote(from, v, bytes);
      case Commit c -> onCommit(c.committed());
      case Tx t -> cfg.app().onTx(from, t.envelope());
      case SyncRequest r -> serveSync(from, r);
      case SyncResponse r -> applySync(from, r);
    }
  }

  /** A peer reports its height (advisory, e.g. from UDP heartbeats): sync if it is ahead. */
  public void onPeerHeight(int peer, long peerHeight) {
    if (peerHeight >= height) {
      requestSync(peer);
    }
  }

  /** Blocks applied through bulk sync so far. */
  public long syncedBlocks() {
    return syncedBlocks;
  }

  private void requestSync(int peer) {
    if (peer < 0 || peer >= vs.size() || peer == cfg.me()) {
      return;
    }
    long now = cfg.scheduler().nowMillis();
    Long last = lastSyncRequest.get(peer);
    if (last != null && now - last < 1_000) {
      return;
    }
    lastSyncRequest.put(peer, now);
    cfg.network()
        .send(
            peer,
            ConsensusMessage.encode(
                new SyncRequest(cfg.me(), height, ConsensusMessage.MAX_SYNC_BLOCKS)));
  }

  private void serveSync(int from, SyncRequest r) {
    if (from < 0 || from >= vs.size() || r.fromHeight() < 1) {
      return;
    }
    List<CommittedBlock> blocks = new ArrayList<>();
    long bytes = 0;
    int max = Math.min(Math.max(r.max(), 1), ConsensusMessage.MAX_SYNC_BLOCKS);
    for (long h = r.fromHeight(); h < height && blocks.size() < max && bytes < (1 << 20); h++) {
      CommittedBlock cb = cfg.app().committed(h);
      if (cb == null) {
        break;
      }
      blocks.add(cb);
      bytes += cb.encode().length;
    }
    if (!blocks.isEmpty()) {
      cfg.network().send(from, ConsensusMessage.encode(new SyncResponse(cfg.me(), blocks)));
    }
  }

  private void applySync(int from, SyncResponse r) {
    long now = cfg.scheduler().nowMillis();
    Long banned = bannedUntil.get(from);
    if (banned != null && now < banned) {
      return;
    }
    int applied = 0;
    for (CommittedBlock cb : r.blocks()) {
      if (cb.height() < height) {
        continue;
      }
      if (cb.height() > height) {
        break; // not contiguous with what we have
      }
      if (!tryCommit(cb)) {
        bannedUntil.put(from, now + 60_000); // served an invalid block
        cfg.observer().peerBanned(from, now + 60_000);
        return;
      }
      syncedBlocks++;
      applied++;
    }
    if (applied > 0) {
      cfg.observer().synced(from, height - 1, applied);
    }
    if (!r.blocks().isEmpty() && r.blocks().get(r.blocks().size() - 1).height() >= height) {
      return;
    }
    lastSyncRequest.remove(from);
    requestSync(from); // more may follow
  }

  public long height() {
    return height;
  }

  public int round() {
    return round;
  }

  public Step step() {
    return step;
  }

  // ------------------------------------------------------------------ receiving

  private void onProposal(int from, Proposal p, byte[] raw) {
    if (p.height() != height) {
      routeOtherHeight(from, p.height(), raw);
      return;
    }
    if (p.round() < 0 || p.proposer() != cfg.proposerFn().proposer(height, p.round())) {
      return;
    }
    byte[] id = p.block().hash();
    if (!cfg.verifier()
        .verify(
            vs.key(p.proposer()),
            Proposal.signBytes(chainId, height, p.round(), p.validRound(), id),
            p.signature())) {
      return;
    }
    Proposal existing = proposals.get(p.round());
    if (existing != null) {
      if (!Arrays.equals(existing.block().hash(), id)) {
        cfg.observer()
            .equivocation(p.proposer(), height, p.round(), "proposal", existing.block().hash(), id);
      }
      return;
    }
    proposals.put(p.round(), p);
    seen(p.round(), p.proposer());
    check();
  }

  private void onVote(int from, Vote v, byte[] raw) {
    if (v.height() != height) {
      routeOtherHeight(from, v.height(), raw);
      return;
    }
    if (v.validator() < 0 || v.validator() >= vs.size() || v.round() < 0) {
      return;
    }
    byte[] msg = Votes.signBytes(chainId, v.type(), height, v.round(), v.blockHash());
    if (!cfg.verifier().verify(vs.key(v.validator()), msg, v.signature())) {
      return;
    }
    Map<Integer, Vote> byValidator =
        votes.computeIfAbsent(new VoteKey(v.type(), v.round()), k -> new HashMap<>());
    Vote existing = byValidator.get(v.validator());
    if (existing != null) {
      if (!existing.sameTarget(v)) {
        cfg.observer()
            .equivocation(
                v.validator(),
                height,
                v.round(),
                v.type() == Votes.PREVOTE ? "prevote" : "precommit",
                existing.blockHash(),
                v.blockHash());
      }
      return;
    }
    byValidator.put(v.validator(), v);
    seen(v.round(), v.validator());
    check();
  }

  private void onCommit(CommittedBlock cb) {
    if (cb.height() == height) {
      tryCommit(cb);
    }
  }

  /** Validates a decided block in sync mode and applies it. Returns whether it was valid. */
  private boolean tryCommit(CommittedBlock cb) {
    if (!structurallyValid(cb.block(), false)
        || !vs.verifies(cb.cert(), height, cb.block().hash(), cfg.verifier())
        || !cfg.app().validCommitted(cb)) {
      return false;
    }
    decide(cb);
    return true;
  }

  /** A message for another height: buffer one height ahead; help a peer that is behind. */
  private void routeOtherHeight(int from, long h, byte[] raw) {
    if (h == height + 1) {
      if (nextHeight.size() < 4 * vs.size() * 8) {
        nextHeight.add(raw);
      }
    } else if (h > height + 1) {
      requestSync(from);
    } else if (h < height && from >= 0 && from < vs.size() && from != cfg.me()) {
      long now = cfg.scheduler().nowMillis();
      Long last = lastCatchUpReply.get(from);
      if (last == null || now - last >= cfg.proposeTimeoutMs() / 2) {
        CommittedBlock cb = cfg.app().committed(h);
        if (cb != null) {
          lastCatchUpReply.put(from, now);
          cfg.network().send(from, ConsensusMessage.encode(new Commit(cfg.me(), cb)));
        }
      }
    }
  }

  private void seen(int r, int validator) {
    seenAtRound.computeIfAbsent(r, k -> new HashSet<>()).add(validator);
  }

  // ------------------------------------------------------------------ rules

  /** Applies every rule whose condition holds, until nothing changes. */
  private void check() {
    boolean changed = true;
    while (changed) {
      changed = false;
      long h = height;
      // L49: decide on any round with a proposal and 2f+1 precommits for it.
      for (Proposal p : proposals.values()) {
        byte[] id = p.block().hash();
        if (count(Votes.PRECOMMIT, p.round(), id) >= vs.quorum() && valid(p.block())) {
          decide(new CommittedBlock(p.block(), cert(p.round(), id)));
          return; // height advanced; the new height runs its own checks
        }
      }
      // L55: f+1 validators already at a higher round: skip ahead.
      int skipTo = -1;
      for (Map.Entry<Integer, Set<Integer>> e : seenAtRound.entrySet()) {
        if (e.getKey() > round && e.getKey() > skipTo && e.getValue().size() >= vs.f() + 1) {
          skipTo = e.getKey();
        }
      }
      if (skipTo > round) {
        startRound(skipTo);
        changed = true;
        continue;
      }
      Proposal p = proposals.get(round);
      // L22 / L28
      if (step == Step.PROPOSE && p != null) {
        byte[] id = p.block().hash();
        if (p.validRound() == -1) {
          boolean ok = valid(p.block()) && (lockedRound == -1 || sameBlock(lockedBlock, id));
          castVote(Votes.PREVOTE, ok ? id : null);
          step = Step.PREVOTE;
          changed = true;
        } else if (p.validRound() < round
            && count(Votes.PREVOTE, p.validRound(), id) >= vs.quorum()) {
          boolean ok =
              valid(p.block()) && (lockedRound <= p.validRound() || sameBlock(lockedBlock, id));
          castVote(Votes.PREVOTE, ok ? id : null);
          step = Step.PREVOTE;
          changed = true;
        }
      }
      // L34
      if (step == Step.PREVOTE
          && countAny(Votes.PREVOTE, round) >= vs.quorum()
          && prevoteTimeoutStarted.add(round)) {
        int r = round;
        cfg.scheduler().schedule(timeout(r), () -> onTimeoutPrevote(h, r));
      }
      // L36
      if (p != null && step != Step.PROPOSE && !lockStepDone.contains(round)) {
        byte[] id = p.block().hash();
        if (count(Votes.PREVOTE, round, id) >= vs.quorum() && valid(p.block())) {
          lockStepDone.add(round);
          if (step == Step.PREVOTE) {
            lockedBlock = p.block();
            lockedRound = round;
            castVote(Votes.PRECOMMIT, id);
            step = Step.PRECOMMIT;
          }
          validBlock = p.block();
          validRound = round;
          changed = true;
        }
      }
      // L44
      if (step == Step.PREVOTE && count(Votes.PREVOTE, round, null) >= vs.quorum()) {
        castVote(Votes.PRECOMMIT, null);
        step = Step.PRECOMMIT;
        changed = true;
      }
      // L47
      if (countAny(Votes.PRECOMMIT, round) >= vs.quorum() && precommitTimeoutStarted.add(round)) {
        int r = round;
        cfg.scheduler().schedule(timeout(r), () -> onTimeoutPrecommit(h, r));
      }
    }
  }

  private void startRound(int r) {
    round = r;
    step = Step.PROPOSE;
    proposalPending = false;
    if (proposeTimer != null) {
      proposeTimer.cancel();
      proposeTimer = null;
    }
    cfg.observer().roundStarted(height, r);
    if (isProposer(r)) {
      tryPropose();
    } else {
      long h = height;
      proposeTimer = cfg.scheduler().schedule(timeout(r), () -> onTimeoutPropose(h, r));
    }
  }

  private void tryPropose() {
    Block b =
        validBlock != null ? validBlock : cfg.app().propose(height, round, prevHash, prevTimestamp);
    if (b == null) {
      proposalPending = true;
      // A proposer without a value still bounds the round by its own propose timeout.
      if (proposeTimer == null) {
        long h = height;
        int r = round;
        proposeTimer = cfg.scheduler().schedule(timeout(r), () -> onTimeoutPropose(h, r));
      }
      return;
    }
    proposalPending = false;
    byte[] id = b.hash();
    if (lastProposal != null && lastProposal.forbids(height, round, id)) {
      return; // proposed something else at this (h, r) before a crash: stay silent this round
    }
    lastProposal = new SafetyState.Signed(height, round, id);
    persist();
    int vr = validBlock != null ? validRound : -1;
    Proposal p =
        new Proposal(
            height,
            round,
            vr,
            cfg.me(),
            b,
            cfg.signer().sign(Proposal.signBytes(chainId, height, round, vr, id)));
    byte[] raw = ConsensusMessage.encode(p);
    ownMessages.add(raw);
    cfg.network().broadcast(raw);
    proposals.putIfAbsent(round, p);
    seen(round, cfg.me());
  }

  private void castVote(int type, byte[] wanted) {
    SafetyState.Signed last = type == Votes.PREVOTE ? lastPrevote : lastPrecommit;
    byte[] id = wanted;
    if (last != null && last.height() == height && last.round() == round) {
      id = last.hash(); // already signed at this (h, r): re-send exactly that vote, never another
    } else if (last != null && last.forbids(height, round, id)) {
      return; // a later position was already signed: stay silent
    }
    SafetyState.Signed now = new SafetyState.Signed(height, round, id);
    if (type == Votes.PREVOTE) {
      lastPrevote = now;
    } else {
      lastPrecommit = now;
    }
    persist();
    Vote v =
        new Vote(
            type,
            height,
            round,
            id,
            cfg.me(),
            cfg.signer().sign(Votes.signBytes(chainId, type, height, round, id)));
    byte[] raw = ConsensusMessage.encode(v);
    ownMessages.add(raw);
    cfg.network().broadcast(raw);
    Map<Integer, Vote> m = votes.computeIfAbsent(new VoteKey(type, round), k -> new HashMap<>());
    m.putIfAbsent(cfg.me(), v);
    seen(round, cfg.me());
  }

  private void persist() {
    cfg.safety()
        .save(
            new SafetyState(
                height, round, lockedRound, lockedBlock, lastProposal, lastPrevote, lastPrecommit));
  }

  // ------------------------------------------------------------------ timeouts

  private void onTimeoutPropose(long h, int r) {
    if (h == height && r == round && step == Step.PROPOSE) {
      castVote(Votes.PREVOTE, null);
      step = Step.PREVOTE;
      check();
    }
  }

  private void onTimeoutPrevote(long h, int r) {
    if (h == height && r == round && step == Step.PREVOTE) {
      castVote(Votes.PRECOMMIT, null);
      step = Step.PRECOMMIT;
      check();
    }
  }

  private void onTimeoutPrecommit(long h, int r) {
    if (h == height && r == round) {
      startRound(r + 1);
      check();
    }
  }

  private long timeout(int r) {
    return cfg.proposeTimeoutMs() + (long) r * cfg.timeoutDeltaMs();
  }

  private void scheduleRetransmit() {
    cfg.scheduler()
        .schedule(
            Math.max(1, cfg.proposeTimeoutMs() / 2),
            () -> {
              for (byte[] m : ownMessages) {
                cfg.network().broadcast(m);
              }
              scheduleRetransmit();
            });
  }

  // ------------------------------------------------------------------ decide

  private void decide(CommittedBlock cb) {
    cfg.app().decide(cb);
    cfg.observer().decided(height, cb.cert().round());
    height = cb.height() + 1;
    prevHash = cb.block().hash();
    prevTimestamp = cb.block().header().timestampMillis();
    lockedRound = -1;
    lockedBlock = null;
    validRound = -1;
    validBlock = null;
    proposals.clear();
    votes.clear();
    seenAtRound.clear();
    prevoteTimeoutStarted.clear();
    precommitTimeoutStarted.clear();
    lockStepDone.clear();
    validity.clear();
    ownMessages.clear();
    if (proposeTimer != null) {
      proposeTimer.cancel();
      proposeTimer = null;
    }
    List<byte[]> buffered = new ArrayList<>(nextHeight);
    nextHeight.clear();
    startRound(0);
    for (byte[] raw : buffered) {
      onMessage(-1, raw);
    }
    check();
  }

  private CommitCert cert(int r, byte[] id) {
    TreeMap<Integer, Vote> sorted =
        new TreeMap<>(votes.getOrDefault(new VoteKey(Votes.PRECOMMIT, r), Map.of()));
    List<CommitCert.Sig> sigs = new ArrayList<>();
    for (Vote v : sorted.values()) {
      if (Arrays.equals(v.blockHash(), id)) {
        sigs.add(new CommitCert.Sig(v.validator(), v.signature()));
      }
    }
    return new CommitCert(r, sigs);
  }

  // ------------------------------------------------------------------ validity and counting

  private boolean valid(Block b) {
    ByteBuffer key = ByteBuffer.wrap(b.hash());
    Boolean cached = validity.get(key);
    if (cached == null) {
      cached = structurallyValid(b, true) && cfg.app().valid(b);
      validity.put(key, cached);
    }
    return cached;
  }

  /** Header checks independent of application state (§6.8 validity table). */
  private boolean structurallyValid(Block b, boolean live) {
    BlockHeader h = b.header();
    return h.version() == BlockHeader.VERSION
        && h.chainId() == chainId
        && h.height() == height
        && Arrays.equals(h.prevHash(), prevHash)
        && h.round() >= 0
        && (!live || h.round() <= round)
        && h.proposer() == cfg.proposerFn().proposer(height, h.round())
        && h.timestampMillis() > prevTimestamp
        && b.commands().size() <= Block.MAX_COMMANDS
        && b.txRootMatches();
  }

  private boolean isProposer(int r) {
    return cfg.proposerFn().proposer(height, r) == cfg.me();
  }

  private int count(int type, int r, byte[] id) {
    Map<Integer, Vote> m = votes.get(new VoteKey(type, r));
    if (m == null) {
      return 0;
    }
    int n = 0;
    for (Vote v : m.values()) {
      if (Arrays.equals(v.blockHash(), id)) {
        n++;
      }
    }
    return n;
  }

  private int countAny(int type, int r) {
    Map<Integer, Vote> m = votes.get(new VoteKey(type, r));
    return m == null ? 0 : m.size();
  }

  private static boolean sameBlock(Block b, byte[] id) {
    return b != null && Arrays.equals(b.hash(), id);
  }
}
