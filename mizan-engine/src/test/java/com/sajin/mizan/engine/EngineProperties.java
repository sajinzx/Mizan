package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.ExecResult.Status;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.CommandStreams;
import com.sajin.mizan.testkit.CommandStreams.Op;
import com.sajin.mizan.testkit.Differential;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.RecordingListener;
import com.sajin.mizan.testkit.StateDump;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

/**
 * M2-T10: engine properties over random command streams (§9 small universe). Tries default to
 * jqwik's 1,000; {@code ./gradlew nightly} runs them at 100,000.
 */
class EngineProperties {

  // ------------------------------------------------------------------ generators and runner

  @Provide
  Arbitrary<List<Op>> streams() {
    Arbitrary<Op> op =
        Combinators.combine(
                Arbitraries.integers().between(0, 99),
                Arbitraries.integers().between(0, CommandStreams.TRADERS.size() - 1),
                Arbitraries.integers().between(0, CommandStreams.INSTRUMENTS.size() - 1),
                Arbitraries.of(true, false),
                Arbitraries.integers().between(95, 105),
                Arbitraries.integers().between(1, 20),
                Arbitraries.integers().between(0, 9),
                Arbitraries.longs())
            .as(Op::new);
    return op.list().ofMinSize(15).ofMaxSize(120);
  }

  static List<RestingOrder> resting(ExecutionCore core) {
    List<RestingOrder> out = new ArrayList<>();
    for (int i : core.instrumentIds()) {
      for (Side s : Side.values()) {
        out.addAll(core.restingOrders(i, s));
      }
    }
    return out;
  }

  /** Observes each executed command. */
  interface Step {
    void after(SignedCommand c, ExecResult r, StateDump before, ExecutionCore core);
  }

  /** Executes setup plus {@code ops} on a fresh core; returns all executed commands. */
  static List<SignedCommand> run(List<Op> ops, ExecutionCore core, RecordingListener l, Step step) {
    var gen = new CommandStreams.Generator(CommandSigner.FAST);
    List<SignedCommand> cmds = new ArrayList<>(gen.setup());
    List<SignedCommand> executed = new ArrayList<>();
    int next = 0;
    while (next < cmds.size() || next - cmds.size() < ops.size()) {
      SignedCommand c =
          next < cmds.size()
              ? cmds.get(next)
              : gen.next(ops.get(next - cmds.size()), () -> resting(core));
      next++;
      StateDump before = step == null ? null : StateDump.of(core);
      ExecResult r = core.execute(c, l);
      executed.add(c);
      if (step != null) {
        step.after(c, r, before, core);
      }
    }
    return executed;
  }

  static ExecutionCore fresh() {
    return ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
  }

  // ------------------------------------------------------------------ properties

  /** For every asset, the sum over all accounts (incl. fees) equals accepted deposits. */
  @Property
  void conservation(@ForAll("streams") List<Op> ops) {
    Map<Integer, Long> deposited = new TreeMap<>();
    RecordingListener l = new RecordingListener();
    run(
        ops,
        fresh(),
        l,
        (c, r, before, core) -> {
          if (c.body() instanceof Deposit d && r.status() == Status.ACCEPTED) {
            deposited.merge(d.asset(), d.amount(), Long::sum);
          }
          for (var a : Fixtures.genesis().assets()) {
            assertThat(core.ledgerTotal(a.id()))
                .as("asset %d", a.id())
                .isEqualTo(deposited.getOrDefault(a.id(), 0L));
          }
        });
    Statistics.collect(
        l.trades().isEmpty() ? "no trades" : l.trades().size() < 5 ? "1-4 trades" : "5+ trades");
  }

  /** Two independent cores fed the same commands agree on the root after every command. */
  @Property
  void replay(@ForAll("streams") List<Op> ops) {
    ExecutionCore a = fresh();
    ExecutionCore b = fresh();
    run(
        ops,
        a,
        new RecordingListener(),
        (c, r, before, core) -> {
          assertThat(b.execute(c, ExecutionCore.SILENT)).isEqualTo(r);
          assertThat(b.stateRoot()).isEqualTo(a.stateRoot());
        });
  }

  /** A copy taken mid-stream is unaffected by later commands and replays to the same end state. */
  @Property(tries = 300)
  void copyIndependence(
      @ForAll("streams") List<Op> ops, @ForAll @IntRange(min = 0, max = 100) int cutPercent) {
    ExecutionCore full = fresh();
    List<SignedCommand> cmds = run(ops, full, new RecordingListener(), null);
    int cut = cmds.size() * cutPercent / 100;
    ExecutionCore core = fresh();
    cmds.subList(0, cut).forEach(c -> core.execute(c, ExecutionCore.SILENT));
    ExecutionCore copy = core.copy();
    byte[] atCut = copy.stateRoot();
    cmds.subList(cut, cmds.size()).forEach(c -> core.execute(c, ExecutionCore.SILENT));
    assertThat(copy.stateRoot()).isEqualTo(atCut);
    cmds.subList(cut, cmds.size()).forEach(c -> copy.execute(c, ExecutionCore.SILENT));
    assertThat(copy.stateRoot()).isEqualTo(core.stateRoot()).isEqualTo(full.stateRoot());
  }

  /**
   * Book structure after every command: never crossed, no empty levels, FIFO by order id within a
   * level, level totals equal the sum of remaining, and the index matches the levels.
   */
  @Property
  void fifoAndBookInvariants(@ForAll("streams") List<Op> ops) {
    run(
        ops,
        fresh(),
        new RecordingListener(),
        (c, r, before, core) -> {
          for (OrderBook b : core.books()) {
            int counted = 0;
            for (Side s : Side.values()) {
              for (PriceLevel lvl : b.levels(s)) {
                assertThat(lvl.isEmpty()).isFalse();
                long sum = 0;
                long prevId = 0;
                for (Order o : lvl.orders()) {
                  assertThat(o.id()).as("FIFO by order id").isGreaterThan(prevId);
                  assertThat(o.limitPrice()).isEqualTo(lvl.price());
                  assertThat(o.side()).isEqualTo(s);
                  assertThat(o.remaining()).isPositive();
                  assertThat(b.find(o.id())).isSameAs(o);
                  prevId = o.id();
                  sum += o.remaining();
                  counted++;
                }
                assertThat(lvl.total()).isEqualTo(sum);
              }
            }
            assertThat(b.orderCount()).isEqualTo(counted);
            var bids = b.depth(Side.BUY, 1);
            var asks = b.depth(Side.SELL, 1);
            if (!bids.isEmpty() && !asks.isEmpty()) {
              assertThat(bids.get(0).price()).as("crossed book").isLessThan(asks.get(0).price());
            }
          }
        });
  }

  /**
   * A rejected command changes nothing except its burned nonce; an auth failure changes nothing at
   * all.
   */
  @Property
  void rejectionPurity(@ForAll("streams") List<Op> ops) {
    run(
        ops,
        fresh(),
        new RecordingListener(),
        (c, r, before, core) -> {
          StateDump after = StateDump.of(core);
          if (r.status() == Status.AUTH_FAILED) {
            assertThat(after).isEqualTo(before);
          } else if (r.status() == Status.REJECTED) {
            List<StateDump.Account> expected = new ArrayList<>();
            for (StateDump.Account a : before.accounts()) {
              expected.add(
                  a.account() == c.accountId()
                      ? new StateDump.Account(a.account(), c.nonce(), a.pubKeyHex())
                      : a);
            }
            assertThat(after)
                .isEqualTo(
                    new StateDump(
                        before.nextOrderId(),
                        before.nextTradeSeq(),
                        before.balances(),
                        expected,
                        before.book()));
          }
        });
  }

  /**
   * Held balances equal exactly the escrow of resting orders, and nothing is negative. This covers
   * price-improvement refunds, IOC/market remainder release, STP release and
   * partial-fill-then-cancel release; it also shows the market-buy guard never overspends.
   */
  @Property
  void escrowMatchesRestingOrders(@ForAll("streams") List<Op> ops) {
    run(
        ops,
        fresh(),
        new RecordingListener(),
        (c, r, before, core) -> {
          Map<Long, Long> expectedHeld = new TreeMap<>();
          for (RestingOrder o : resting(core)) {
            Instrument in = core.genesisConfig().instrument(o.instrument()).orElseThrow();
            boolean buy = o.side() == Side.BUY;
            expectedHeld.merge(
                Ledger.key(o.account(), buy ? in.quote() : in.base()),
                buy ? o.price() * o.remaining() : o.remaining(),
                Long::sum);
          }
          Map<Long, Long> actualHeld = new TreeMap<>();
          core.forEachBalance(
              (a, asset, free, held) -> {
                assertThat(free).isNotNegative();
                assertThat(held).isNotNegative();
                if (held != 0) {
                  actualHeld.put(Ledger.key(a, asset), held);
                }
              });
          assertThat(actualHeld).isEqualTo(expectedHeld);
        });
  }

  /** No trade is between an account and itself, and fees collected match the fee schedule. */
  @Property
  void selfTradePreventionAndFeeConservation(@ForAll("streams") List<Op> ops) {
    RecordingListener l = new RecordingListener();
    ExecutionCore core = fresh();
    run(ops, core, l, null);
    Map<Integer, Long> fees = new TreeMap<>();
    for (Trade t : l.trades()) {
      assertThat(t.buyAccount()).isNotEqualTo(t.sellAccount());
      Instrument in = core.genesisConfig().instrument(t.instrument()).orElseThrow();
      boolean buyerTaker = t.takerSide() == Side.BUY;
      fees.merge(
          in.base(), Ledger.bps(t.qty(), buyerTaker ? in.takerBps() : in.makerBps()), Long::sum);
      fees.merge(
          in.quote(),
          Ledger.bps(t.price() * t.qty(), buyerTaker ? in.makerBps() : in.takerBps()),
          Long::sum);
    }
    for (var a : Fixtures.genesis().assets()) {
      assertThat(core.free(Accounts.FEE_ACCOUNT, a.id()) + core.held(Accounts.FEE_ACCOUNT, a.id()))
          .isEqualTo(fees.getOrDefault(a.id(), 0L));
    }
    Statistics.collect(l.trades().size() > 5 ? ">5 trades" : "<=5 trades");
  }

  /** Re-submitting any earlier command is an auth failure that changes nothing. */
  @Property(tries = 300)
  void replayProtection(@ForAll("streams") List<Op> ops) {
    ExecutionCore core = fresh();
    List<SignedCommand> cmds = run(ops, core, new RecordingListener(), null);
    byte[] root = core.stateRoot();
    for (SignedCommand c : cmds) {
      assertThat(core.execute(c, ExecutionCore.SILENT).status()).isEqualTo(Status.AUTH_FAILED);
    }
    assertThat(core.stateRoot()).isEqualTo(root);
  }

  /** The engine equals the naive reference engine after every command (§9). */
  @Property
  void differentialEquality(@ForAll("streams") List<Op> ops) {
    Differential d = new Differential(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
    var gen = new CommandStreams.Generator(CommandSigner.FAST);
    d.run(gen.setup());
    for (Op op : ops) {
      d.step(gen.next(op, () -> resting(d.core())));
    }
  }
}
