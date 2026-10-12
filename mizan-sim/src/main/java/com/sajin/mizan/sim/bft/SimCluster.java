package com.sajin.mizan.sim.bft;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.BlockHeader;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.GenesisAnchor;
import com.sajin.mizan.chain.ValidatorSet;
import com.sajin.mizan.chain.Votes;
import com.sajin.mizan.chain.bft.ConsensusMessage;
import com.sajin.mizan.chain.bft.MessageSigner;
import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.chain.bft.SafetyStore;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.crypto.Hashing;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * {@code n} validators running {@link RoundBft} inside a {@link SimWorld} (M7). Each validator's
 * application is a deterministic toy chain: blocks carry no commands, and the state root of height
 * {@code h} is {@code SHA256(prevHash || h)}, so every honest validator must agree on it. Decided
 * blocks and the safety state survive a simulated crash (they model the disk); the message log and
 * timers do not.
 *
 * <p>Messages are signed with the fast simulation stand-in (ADR 0014); honest and Byzantine nodes
 * sign only as themselves.
 */
public final class SimCluster {
  /** Virtual milliseconds between a block and the next proposal. */
  public static final long BLOCK_INTERVAL_MS = 100;

  /** Simulation parameters. */
  public record Params(
      int n,
      long proposeTimeoutMs,
      long timeoutDeltaMs,
      Function<ValidatorSet, RoundBft.ProposerFn> proposer) {
    public static Params fixedProposer(int n) {
      return new Params(n, 1_000, 500, vs -> RoundBft.ProposerFn.fixed(0));
    }

    public static Params rotating(int n) {
      return new Params(n, 1_000, 500, RoundBft.ProposerFn::rotating);
    }
  }

  /** One validator's durable state plus its live consensus instance. */
  public final class Validator implements RoundBft.App {
    final int index;
    final List<CommittedBlock> chain = new ArrayList<>();
    final SafetyStore safety;
    RoundBft bft;
    boolean byzantine;

    Validator(int index, SafetyStore safety) {
      this.index = index;
      this.safety = safety;
    }

    public List<CommittedBlock> chain() {
      return chain;
    }

    public RoundBft bft() {
      return bft;
    }

    byte[] head() {
      return chain.isEmpty() ? anchor : chain.get(chain.size() - 1).block().hash();
    }

    long headTimestamp() {
      return chain.isEmpty() ? 0 : chain.get(chain.size() - 1).block().header().timestampMillis();
    }

    static byte[] expectedRoot(byte[] prevHash, long height) {
      return Hashing.sha256(prevHash, ByteBuffer.allocate(8).putLong(height).array());
    }

    @Override
    public Block propose(long height, int round, byte[] prevHash, long prevTs) {
      // Pace blocks: at most one every BLOCK_INTERVAL_MS of virtual time after the previous one.
      long readyAt = prevTs + BLOCK_INTERVAL_MS;
      if (world.now() < readyAt) {
        RoundBft target = bft;
        world.atNode(index, readyAt, target::valueReady);
        return null;
      }
      long ts = Math.max(prevTs + 1, world.now());
      return new Block(
          new BlockHeader(
              BlockHeader.VERSION,
              g.chainId(),
              height,
              round,
              prevHash,
              Block.txRoot(List.of()),
              expectedRoot(prevHash, height),
              index,
              ts),
          List.of());
    }

    @Override
    public boolean valid(Block b) {
      BlockHeader h = b.header();
      return Arrays.equals(h.stateRoot(), expectedRoot(h.prevHash(), h.height()))
          && Math.abs(h.timestampMillis() - world.now()) <= 5_000;
    }

    @Override
    public boolean validCommitted(CommittedBlock cb) {
      BlockHeader h = cb.block().header();
      return Arrays.equals(h.stateRoot(), expectedRoot(h.prevHash(), h.height()));
    }

    @Override
    public void decide(CommittedBlock cb) {
      if (cb.height() != chain.size() + 1) {
        throw new IllegalStateException(
            "validator " + index + " decided " + cb.height() + " after " + chain.size());
      }
      chain.add(cb);
      if (!byzantine) {
        recordDecision(index, cb);
      }
    }

    @Override
    public CommittedBlock committed(long height) {
      return height >= 1 && height <= chain.size() ? chain.get((int) height - 1) : null;
    }
  }

  private final SimWorld world;
  private final SimNetwork net;
  private final GenesisConfig g;
  private final ValidatorSet vs;
  private final byte[] anchor;
  private final Params params;
  private final List<Validator> validators = new ArrayList<>();
  private final List<String> evidence = new ArrayList<>();
  private final java.util.Map<Long, byte[]> decidedHash = new java.util.HashMap<>();
  private final java.util.Map<Long, Integer> decidedBy = new java.util.HashMap<>();
  private String firstConflict;
  private final MessageSigner[] signers;
  private final SignatureVerifier verifier = CommandSigner.FAST_VERIFIER;

  public SimCluster(SimWorld world, Params params, Function<Integer, SafetyStore> safetyFactory) {
    this.world = world;
    this.params = params;
    this.g = Fixtures.genesis(params.n());
    this.vs = new ValidatorSet(g);
    this.anchor = GenesisAnchor.hash(g);
    this.net = new SimNetwork(world, params.n());
    this.signers = new MessageSigner[params.n()];
    for (int i = 0; i < params.n(); i++) {
      byte[] pk = Fixtures.validatorSigner(i).publicKey();
      signers[i] = m -> CommandSigner.fastTag(pk, m);
      validators.add(new Validator(i, safetyFactory.apply(i)));
    }
  }

  public SimNetwork network() {
    return net;
  }

  public SimWorld world() {
    return world;
  }

  public List<Validator> validators() {
    return validators;
  }

  public List<String> evidence() {
    return evidence;
  }

  public ValidatorSet validatorSet() {
    return vs;
  }

  /** Starts every validator (honest unless marked Byzantine with {@link #makeByzantine}). */
  public void start() {
    for (Validator v : validators) {
      if (v.byzantine) {
        startByzantine(v);
      } else {
        startHonest(v);
      }
    }
  }

  private void startHonest(Validator v) {
    RoundBft.Observer obs =
        new RoundBft.Observer() {
          @Override
          public void equivocation(
              int validator, long height, int round, String kind, byte[] a, byte[] b) {
            evidence.add(
                "seen by "
                    + v.index
                    + ": validator "
                    + validator
                    + " "
                    + kind
                    + " h="
                    + height
                    + " r="
                    + round);
          }
        };
    v.bft =
        new RoundBft(
            new RoundBft.Config(
                v.index,
                vs,
                signers[v.index],
                verifier,
                net.endpoint(v.index),
                world.scheduler(v.index),
                v,
                v.safety,
                params.proposer().apply(vs),
                params.proposeTimeoutMs(),
                params.timeoutDeltaMs(),
                obs),
            v.chain.size() + 1L,
            v.head(),
            v.headTimestamp());
    RoundBft bft = v.bft;
    net.setHandler(v.index, bft::onMessage);
    world.atNode(v.index, world.now(), bft::start);
  }

  /**
   * Marks validator {@code i} Byzantine: on every proposal it prevotes and precommits the block to
   * everyone, and additionally sends odd-numbered peers a conflicting vote (another hash or nil)
   * for the same round, i.e. it double-votes.
   */
  public void makeByzantine(int i) {
    validators.get(i).byzantine = true;
  }

  private void startByzantine(Validator v) {
    net.setHandler(
        v.index,
        (from, bytes) -> {
          ConsensusMessage m;
          try {
            m = ConsensusMessage.decode(bytes);
          } catch (ProtocolException e) {
            return;
          }
          if (m instanceof ConsensusMessage.Proposal p) {
            byte[] real = p.block().hash();
            byte[] fake = Hashing.sha256(real, new byte[] {(byte) world.random().nextInt(256)});
            for (int type : new int[] {Votes.PREVOTE, Votes.PRECOMMIT}) {
              for (int to = 0; to < params.n(); to++) {
                if (to == v.index) {
                  continue;
                }
                List<byte[]> targets = new ArrayList<>();
                targets.add(real);
                if (to % 2 == 1) {
                  targets.add(world.random().nextBoolean() ? fake : null);
                }
                for (byte[] target : targets) {
                  byte[] sig =
                      signers[v.index].sign(
                          Votes.signBytes(g.chainId(), type, p.height(), p.round(), target));
                  net.endpoint(v.index)
                      .send(
                          to,
                          ConsensusMessage.encode(
                              new ConsensusMessage.Vote(
                                  type, p.height(), p.round(), target, v.index, sig)));
                }
              }
            }
          }
        });
  }

  /** Crashes validator {@code i}: its timers, in-flight messages and message log are lost. */
  public void crash(int i) {
    world.crash(i);
    net.setHandler(i, null);
  }

  /** Restarts validator {@code i} from its durable chain and safety state. */
  public void restart(int i) {
    world.revive(i);
    startHonest(validators.get(i));
  }

  /** Lowest decided height among honest, alive validators. */
  public long minHonestHeight() {
    long min = Long.MAX_VALUE;
    for (Validator v : validators) {
      if (!v.byzantine && world.alive(v.index)) {
        min = Math.min(min, v.chain.size());
      }
    }
    return min;
  }

  private void recordDecision(int validator, CommittedBlock cb) {
    byte[] hash = cb.block().hash();
    byte[] prior = decidedHash.putIfAbsent(cb.height(), hash);
    if (prior == null) {
      decidedBy.put(cb.height(), validator);
    } else if (!Arrays.equals(prior, hash) && firstConflict == null) {
      firstConflict =
          "height "
              + cb.height()
              + ": validator "
              + decidedBy.get(cb.height())
              + " and "
              + validator
              + " decided different blocks";
    }
  }

  /**
   * Safety: for every height, all honest validators that decided it decided the same block. Returns
   * a description of the first conflict, or {@code null}. Tracked as decisions happen.
   */
  public String conflict() {
    return firstConflict;
  }
}
