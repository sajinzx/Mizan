package com.sajin.mizan.chain.bft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.BlockHeader;
import com.sajin.mizan.chain.BlockValidator;
import com.sajin.mizan.chain.CommitCert;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.GenesisAnchor;
import com.sajin.mizan.chain.ValidatorSet;
import com.sajin.mizan.chain.Votes;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import com.sajin.mizan.testkit.StructuredMutator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * M9-T5: structure-aware fuzzing of consensus messages and block bytes. Oracles:
 *
 * <ol>
 *   <li>decoders throw only {@link ProtocolException}, never anything else;
 *   <li>whatever decodes is canonical: re-encoding gives back exactly the input bytes (so two byte
 *       strings can never decode to the same block or vote with different hashes);
 *   <li>block validation of any decodable block returns {@code Valid}/{@code Invalid} and never
 *       throws;
 *   <li>a live {@link RoundBft} fed mutated bytes, and validly signed messages with extreme field
 *       values, never throws and never moves its height backwards.
 * </ol>
 */
@ExtendWith(SeedExtension.class)
class StructuredFuzzTest {
  static final int CASES = Integer.getInteger("mizan.fuzz.cases", 60_000);
  static final GenesisConfig G = Fixtures.genesis(4);
  static final ValidatorSet VS = new ValidatorSet(G);
  static final MessageSigner[] KEYS = new MessageSigner[4];

  static {
    for (int i = 0; i < 4; i++) {
      KEYS[i] = MessageSigner.of(Fixtures.validatorSigner(i));
    }
  }

  static Block block(long height, int round, byte[] prev, int nCommands) {
    List<SignedCommand> cmds = new ArrayList<>();
    for (int i = 0; i < nCommands; i++) {
      cmds.add(
          CommandSigner.FAST.sign(
              G.chainId(),
              500 + i,
              1,
              new Place(
                  i,
                  Fixtures.INSTRUMENT,
                  i % 2 == 0 ? Side.BUY : Side.SELL,
                  OrderType.LIMIT,
                  TimeInForce.GTC,
                  100 + i,
                  1 + i)));
    }
    byte[] z = new byte[32];
    return new Block(
        new BlockHeader(
            BlockHeader.VERSION,
            G.chainId(),
            height,
            round,
            prev,
            Block.txRoot(cmds),
            z,
            VS.proposer(height, round),
            1_000 + height),
        cmds);
  }

  static CommittedBlock committed(Block b) {
    List<CommitCert.Sig> sigs = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      sigs.add(
          new CommitCert.Sig(
              i,
              KEYS[i].sign(
                  Votes.signBytes(
                      G.chainId(), Votes.PRECOMMIT, b.height(), b.header().round(), b.hash()))));
    }
    return new CommittedBlock(b, new CommitCert(b.header().round(), sigs));
  }

  static List<byte[]> messageCorpus() {
    Block b1 = block(1, 0, GenesisAnchor.hash(G), 3);
    Block b2 = block(2, 1, b1.hash(), 0);
    Block b3 = block(3, 0, b2.hash(), 5);
    List<ConsensusMessage> ms =
        List.of(
            new ConsensusMessage.Proposal(
                1, 0, -1, 1, b1, KEYS[1].sign(new byte[] {1})), // signature content irrelevant
            new ConsensusMessage.Proposal(3, 0, 2, 3, b3, KEYS[3].sign(new byte[] {2})),
            new ConsensusMessage.Vote(Votes.PREVOTE, 1, 0, b1.hash(), 2, KEYS[2].sign(new byte[1])),
            new ConsensusMessage.Vote(Votes.PRECOMMIT, 1, 3, null, 3, KEYS[3].sign(new byte[1])),
            new ConsensusMessage.Commit(1, committed(b1)),
            new ConsensusMessage.Tx(2, b3.commands().get(0).raw()),
            new ConsensusMessage.SyncRequest(1, 1, ConsensusMessage.MAX_SYNC_BLOCKS),
            new ConsensusMessage.SyncResponse(
                2, List.of(committed(b1), committed(b2), committed(b3))));
    List<byte[]> out = new ArrayList<>();
    ms.forEach(m -> out.add(ConsensusMessage.encode(m)));
    return out;
  }

  @Test
  void consensusMessagesOnlyRejectAndAreCanonical(Seed seed) {
    List<byte[]> corpus = messageCorpus();
    StructuredMutator mut = new StructuredMutator(seed.random());
    SplittableRandom rnd = seed.random();
    int decoded = 0;
    for (int i = 0; i < CASES; i++) {
      byte[] m = mut.mutate(corpus.get(rnd.nextInt(corpus.size())), corpus);
      ConsensusMessage msg;
      try {
        msg = ConsensusMessage.decode(m);
      } catch (ProtocolException expected) {
        continue;
      }
      decoded++;
      assertThat(ConsensusMessage.encode(msg)).as("canonical re-encoding, case %d", i).isEqualTo(m);
    }
    System.out.printf(
        "consensus message fuzz: %d structured cases, %d still decoded (all canonical)%n",
        CASES, decoded);
    assertThat(decoded).as("some mutations keep the structure valid").isPositive();
  }

  @Test
  void blockBytesOnlyRejectAndValidationNeverThrows(Seed seed) {
    Block b1 = block(1, 0, GenesisAnchor.hash(G), 4);
    List<byte[]> corpus =
        List.of(
            b1.encode(),
            committed(b1).encode(),
            committed(block(1, 2, GenesisAnchor.hash(G), 0)).encode());
    ExecutionCore genesis = ExecutionCore.genesis(G, CommandSigner.FAST_VERIFIER);
    BlockValidator.Context ctx =
        new BlockValidator.Context(
            VS,
            1,
            GenesisAnchor.hash(G),
            0,
            (h, r) -> VS.proposer(h, (int) r),
            G.block().maxCommands());
    StructuredMutator mut = new StructuredMutator(seed.random());
    SplittableRandom rnd = seed.random();
    int validated = 0;
    for (int i = 0; i < CASES / 4; i++) {
      byte[] m = mut.mutate(corpus.get(rnd.nextInt(corpus.size())), corpus);
      Block b;
      CommitCert cert = null;
      try {
        if (rnd.nextBoolean()) {
          CommittedBlock cb = CommittedBlock.decode(m);
          assertThat(cb.encode()).as("canonical committed block, case %d", i).isEqualTo(m);
          b = cb.block();
          cert = cb.cert();
        } else {
          b = Block.decode(m);
          assertThat(b.encode()).as("canonical block, case %d", i).isEqualTo(m);
        }
      } catch (ProtocolException expected) {
        continue;
      }
      BlockValidator.Mode mode = cert == null ? BlockValidator.Mode.LIVE : BlockValidator.Mode.SYNC;
      BlockValidator.Result r =
          BlockValidator.validate(
              b, cert, genesis.copy(), ctx, mode, 1_001, CommandSigner.FAST_VERIFIER);
      assertThat(r).isNotNull();
      validated++;
    }
    System.out.printf("block fuzz: %d structured cases, %d validated%n", CASES / 4, validated);
    assertThat(validated).isPositive();
  }

  /** Signed messages with extreme fields: what a Byzantine validator (index 3) can really send. */
  static byte[] signedExtreme(SplittableRandom rnd, Block b) {
    long[] heights = {0, 1, 2, 3, -1, Long.MAX_VALUE, Long.MIN_VALUE};
    int[] rounds = {0, 1, 2, 7, -1, -2, 1 << 20, Integer.MAX_VALUE, Integer.MIN_VALUE};
    long h = heights[rnd.nextInt(heights.length)];
    int r = rounds[rnd.nextInt(rounds.length)];
    int signer = 3;
    if (rnd.nextBoolean()) {
      int type = rnd.nextBoolean() ? Votes.PREVOTE : Votes.PRECOMMIT;
      byte[] id = rnd.nextBoolean() ? null : rnd.nextBoolean() ? b.hash() : new byte[32];
      return ConsensusMessage.encode(
          new ConsensusMessage.Vote(
              type,
              h,
              r,
              id,
              signer,
              KEYS[signer].sign(Votes.signBytes(G.chainId(), type, h, r, id))));
    }
    int vr = rounds[rnd.nextInt(rounds.length)];
    return ConsensusMessage.encode(
        new ConsensusMessage.Proposal(
            h,
            r,
            vr,
            signer,
            b,
            KEYS[signer].sign(
                ConsensusMessage.Proposal.signBytes(G.chainId(), h, r, vr, b.hash()))));
  }

  @Test
  void liveRoundBftNeverThrowsOnHostileInput(Seed seed) {
    long[] now = {10_000};
    List<Runnable> timers = new ArrayList<>();
    long[] decided = {0};
    RoundBft.App app =
        new RoundBft.App() {
          @Override
          public Block propose(long height, int round, byte[] prevHash, long prevTs) {
            return null;
          }

          @Override
          public boolean valid(Block block) {
            return block.commands().size() < 4;
          }

          @Override
          public boolean validCommitted(CommittedBlock committed) {
            return true;
          }

          @Override
          public void decide(CommittedBlock committed) {
            assertThat(committed.height()).isEqualTo(decided[0] + 1);
            decided[0] = committed.height();
          }

          @Override
          public CommittedBlock committed(long height) {
            return null;
          }
        };
    Scheduler sched =
        new Scheduler() {
          @Override
          public long nowMillis() {
            return now[0];
          }

          @Override
          public Cancellable schedule(long delayMillis, Runnable task) {
            timers.add(task);
            return () -> {};
          }
        };
    Network net =
        new Network() {
          @Override
          public void send(int to, byte[] message) {}

          @Override
          public void broadcast(byte[] message) {}
        };
    RoundBft bft =
        new RoundBft(
            new RoundBft.Config(
                0,
                VS,
                KEYS[0],
                com.sajin.mizan.crypto.SignatureVerifier.ed25519(),
                net,
                sched,
                app,
                new SafetyStore.InMemory(),
                RoundBft.ProposerFn.rotating(VS),
                1_000,
                500,
                new RoundBft.Observer() {}),
            1,
            GenesisAnchor.hash(G),
            0);
    bft.start();
    List<byte[]> corpus = messageCorpus();
    StructuredMutator mut = new StructuredMutator(seed.random());
    SplittableRandom rnd = seed.random();
    Block b1 = block(1, 0, GenesisAnchor.hash(G), 1);
    int cases = CASES / 6;
    long lastHeight = bft.height();
    for (int i = 0; i < cases; i++) {
      int kind = rnd.nextInt(10);
      byte[] m =
          kind < 6
              ? mut.mutate(corpus.get(rnd.nextInt(corpus.size())), corpus)
              : kind < 9 ? signedExtreme(rnd, b1) : corpus.get(rnd.nextInt(corpus.size()));
      int from = new int[] {1, 2, 3, 3, 0, -1, 4, Integer.MAX_VALUE}[rnd.nextInt(8)];
      bft.onMessage(from, m);
      if (rnd.nextInt(50) == 0 && !timers.isEmpty()) { // fire a pending timeout now and then
        now[0] += 1_000;
        timers.remove(rnd.nextInt(timers.size())).run();
      }
      bft.onPeerHeight(rnd.nextInt(-1, 5), rnd.nextLong(-2, 10));
      assertThat(bft.height()).isGreaterThanOrEqualTo(lastHeight);
      lastHeight = bft.height();
    }
    System.out.printf(
        "live RoundBft fuzz: %d hostile inputs, height %d, round %d%n",
        cases, bft.height(), bft.round());
  }

  @Test
  void mutatorKeepsSomeInputsIntactInLength(Seed seed) {
    // Sanity of the mutator itself: it changes bytes and lengths in both directions.
    StructuredMutator mut = new StructuredMutator(seed.random());
    byte[] in = new byte[100];
    Arrays.fill(in, (byte) 7);
    boolean shorter = false;
    boolean longer = false;
    boolean changed = false;
    for (int i = 0; i < 1_000; i++) {
      byte[] o = mut.mutate(in, List.of(new byte[] {1, 2, 3}));
      shorter |= o.length < in.length;
      longer |= o.length > in.length;
      changed |= o.length == in.length && !Arrays.equals(o, in);
    }
    assertThat(shorter && longer && changed).isTrue();
  }
}
