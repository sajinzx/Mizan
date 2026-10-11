package com.sajin.mizan.engine;

import static com.sajin.mizan.engine.TestCore.BASE;
import static com.sajin.mizan.engine.TestCore.I;
import static com.sajin.mizan.engine.TestCore.QUOTE;
import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.ExecResult.Status;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.RecordingListener.Accepted;
import com.sajin.mizan.testkit.RecordingListener.Cancelled;
import com.sajin.mizan.testkit.RecordingListener.Delta;
import com.sajin.mizan.testkit.RecordingListener.Rejected;
import com.sajin.mizan.testkit.SignedCommands;
import com.sajin.mizan.testkit.StateDump;
import org.junit.jupiter.api.Test;

class ExecutionCoreTest {

  // ------------------------------------------------------------------ TIF scenarios

  @Test
  void gtcRestsThenMatchesWithPriceImprovementRefund() {
    TestCore t = TestCore.funded();
    ExecResult ask = t.gtc(3, Side.SELL, 100, 10);
    assertThat(ask.status()).isEqualTo(Status.ACCEPTED);
    assertThat(ask.orderId()).isEqualTo(1);
    assertThat(t.held(3, BASE)).isEqualTo(10);
    assertThat(t.events.drain())
        .containsExactly(new Accepted(3, 0, 1), new Delta(I, Side.SELL, 100, 10));

    ExecResult bid = t.gtc(2, Side.BUY, 102, 4);
    assertThat(bid.orderId()).isEqualTo(2);
    Trade trade = t.events.trades().get(0);
    assertThat(trade).isEqualTo(new Trade(1, I, 1, 2, 2, 3, Side.BUY, 100, 4, 102));
    // Buyer escrowed 4*102, paid 400, refund 8; taker fee 10 bps of 4 base floors to 0.
    assertThat(t.free(2, QUOTE)).isEqualTo(1_000_000 - 400);
    assertThat(t.held(2, QUOTE)).isZero();
    assertThat(t.free(2, BASE)).isEqualTo(10_000 + 4);
    assertThat(t.held(3, BASE)).isEqualTo(6);
    assertThat(t.free(3, QUOTE)).isEqualTo(1_000_000 + 400); // maker fee 0
    assertThat(t.core.nextTradeSeq()).isEqualTo(2);
  }

  @Test
  void gtcRemainderRestsAfterPartialFill() {
    TestCore t = TestCore.funded();
    t.gtc(3, Side.SELL, 100, 3);
    t.gtc(2, Side.BUY, 101, 5);
    assertThat(t.core.restingOrders(I, Side.BUY))
        .containsExactly(new RestingOrder(2, 2, I, Side.BUY, TimeInForce.GTC, 101, 2));
    assertThat(t.held(2, QUOTE)).isEqualTo(2 * 101);
  }

  @Test
  void iocReleasesRemainder() {
    TestCore t = TestCore.funded();
    t.gtc(3, Side.SELL, 100, 3);
    ExecResult r = t.limit(2, Side.BUY, 101, 5, TimeInForce.IOC);
    assertThat(r.status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.core.restingOrders(I, Side.BUY)).isEmpty();
    assertThat(t.held(2, QUOTE)).isZero();
    assertThat(t.free(2, QUOTE)).isEqualTo(1_000_000 - 300);
  }

  @Test
  void postOnlyRejectsWhenCrossingAndRestsOtherwise() {
    TestCore t = TestCore.funded();
    t.gtc(3, Side.SELL, 100, 3);
    StateDump before = StateDump.of(t.core);
    ExecResult crossing = t.limit(2, Side.BUY, 100, 1, TimeInForce.POST_ONLY);
    assertThat(crossing.status()).isEqualTo(Status.REJECTED);
    assertThat(StateDump.of(t.core).book()).isEqualTo(before.book());
    assertThat(t.core.nextOrderId()).isEqualTo(before.nextOrderId());

    ExecResult resting = t.limit(2, Side.BUY, 99, 1, TimeInForce.POST_ONLY);
    assertThat(resting.status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.core.restingOrders(I, Side.BUY)).hasSize(1);
  }

  @Test
  void marketBuyIsLimitedByFreeQuote() {
    TestCore t = TestCore.funded(1_000, 10_000);
    t.gtc(3, Side.SELL, 100, 50);
    ExecResult r = t.market(2, Side.BUY, 110, 50);
    assertThat(r.status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.events.trades()).extracting(Trade::qty).containsExactly(10L);
    assertThat(t.events.trades().get(0).buyEscrowPrice()).isZero();
    assertThat(t.free(2, QUOTE)).isZero();
    assertThat(t.core.restingOrders(I, Side.SELL).get(0).remaining()).isEqualTo(40);
  }

  @Test
  void marketSellRespectsCollarAndReleasesRemainder() {
    TestCore t = TestCore.funded();
    t.gtc(2, Side.BUY, 100, 3);
    t.gtc(4, Side.BUY, 95, 3);
    t.market(3, Side.SELL, 98, 10);
    assertThat(t.events.trades()).extracting(Trade::price).containsExactly(100L);
    assertThat(t.held(3, BASE)).isZero();
    assertThat(t.free(3, BASE)).isEqualTo(10_000 - 3);
    assertThat(t.core.restingOrders(I, Side.BUY))
        .extracting(RestingOrder::price)
        .containsExactly(95L);
  }

  @Test
  void marketOnEmptyBookIsAcceptedWithNoFills() {
    TestCore t = TestCore.funded();
    assertThat(t.market(2, Side.BUY, 100, 5).status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.market(3, Side.SELL, 100, 5).status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.held(3, BASE)).isZero();
    assertThat(t.events.trades()).isEmpty();
  }

  @Test
  void selfTradePreventionCancelsRestingOrderAndReleasesEscrow() {
    TestCore t = TestCore.funded();
    t.gtc(2, Side.SELL, 100, 5);
    t.gtc(3, Side.SELL, 100, 5);
    t.events.drain();
    t.gtc(2, Side.BUY, 100, 5);
    assertThat(t.events.events())
        .containsSubsequence(new Cancelled(1, "STP"), new Delta(I, Side.SELL, 100, 5));
    assertThat(t.events.trades()).singleElement().extracting(Trade::sellAccount).isEqualTo(3L);
    assertThat(t.held(2, BASE)).isZero();
    assertThat(t.free(2, BASE)).isEqualTo(10_000 + 5);
  }

  @Test
  void cancelReleasesEscrowAfterPartialFill() {
    TestCore t = TestCore.funded();
    long bid = t.gtc(2, Side.BUY, 100, 10).orderId();
    t.gtc(3, Side.SELL, 100, 4);
    assertThat(t.held(2, QUOTE)).isEqualTo(600);
    t.events.drain();
    ExecResult r = t.cancel(2, bid);
    assertThat(r).isEqualTo(ExecResult.accepted(bid));
    assertThat(t.events.drain())
        .containsExactly(new Cancelled(bid, "USER"), new Delta(I, Side.BUY, 100, 0));
    assertThat(t.held(2, QUOTE)).isZero();
    assertThat(t.free(2, QUOTE)).isEqualTo(1_000_000 - 400);
  }

  // ------------------------------------------------------------------ rejects

  @Test
  void businessRejectsBurnOnlyTheNonce() {
    TestCore t = TestCore.funded();
    t.gtc(3, Side.SELL, 100, 5);
    Place[] bad = {
      new Place(1, 99, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 1), // unknown instrument
      new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 0, 1),
      new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, -1),
      new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Limits.MAX_PRICE + 1, 1),
      new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, Limits.MAX_QTY + 1),
      new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 100_000), // no balance
      new Place(1, I, Side.BUY, OrderType.MARKET, TimeInForce.GTC, 100, 1),
      new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.FOK, 100, 6),
    };
    for (Place p : bad) {
      StateDump before = StateDump.of(t.core);
      long nonceBefore = t.core.lastNonce(2);
      ExecResult r = t.exec(2, p);
      assertThat(r.status()).as("%s", p).isEqualTo(Status.REJECTED);
      assertThat(r.reason()).isNotBlank();
      StateDump after = StateDump.of(t.core);
      assertThat(after.balances()).isEqualTo(before.balances());
      assertThat(after.book()).isEqualTo(before.book());
      assertThat(after.nextOrderId()).isEqualTo(before.nextOrderId());
      assertThat(t.core.lastNonce(2)).isEqualTo(nonceBefore + 1);
    }
    assertThat(t.cancel(2, 1).status()).isEqualTo(Status.REJECTED); // not the owner
    assertThat(t.exec(2, new Cancel(1, 99)).status()).isEqualTo(Status.REJECTED);
    assertThat(t.events.events()).contains(new Rejected(2, 1));
  }

  @Test
  void tickAndLotAreEnforced() {
    TestCore t = TestCore.funded();
    var g = Fixtures.genesis();
    var coarse =
        new com.sajin.mizan.core.GenesisConfig(
            g.chainId(),
            1,
            g.adminPubKey(),
            g.assets(),
            java.util.List.of(
                new com.sajin.mizan.core.Instrument(1, 1, 2, 5, 10, 0, 0, 1_000, 1_000)),
            g.validators(),
            g.block(),
            g.consensus());
    ExecutionCore core = ExecutionCore.genesis(coarse, CommandSigner.FAST_VERIFIER, false);
    SignedCommand reg = t.cmd(2, new Register(Fixtures.accountSigner(2).publicKey()));
    assertThat(core.execute(reg, ExecutionCore.SILENT).status()).isEqualTo(Status.ACCEPTED);
    assertThat(core.execute(t.cmd(1, new Deposit(2, 2, 100_000)), ExecutionCore.SILENT).status())
        .isEqualTo(Status.ACCEPTED);
    for (Place p :
        new Place[] {
          new Place(1, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 101, 10),
          new Place(1, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 15),
        }) {
      assertThat(core.execute(t.cmd(2, p), ExecutionCore.SILENT).status())
          .isEqualTo(Status.REJECTED);
    }
    assertThat(
            core.execute(
                    t.cmd(2, new Place(1, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 20)),
                    ExecutionCore.SILENT)
                .status())
        .isEqualTo(Status.ACCEPTED);
  }

  @Test
  void depositRejects() {
    TestCore t = TestCore.funded();
    assertThat(t.exec(Accounts.ADMIN_ACCOUNT, new Deposit(2, 9, 10)).status())
        .isEqualTo(Status.REJECTED); // unknown asset
    assertThat(t.exec(Accounts.ADMIN_ACCOUNT, new Deposit(42, QUOTE, 10)).status())
        .isEqualTo(Status.REJECTED); // unregistered target
    assertThat(
            t.exec(Accounts.ADMIN_ACCOUNT, new Deposit(Accounts.FEE_ACCOUNT, QUOTE, 10)).status())
        .isEqualTo(Status.REJECTED); // fee account is never registered
    assertThat(t.exec(Accounts.ADMIN_ACCOUNT, new Deposit(2, QUOTE, 0)).status())
        .isEqualTo(Status.REJECTED);
  }

  // ------------------------------------------------------------------ authentication

  @Test
  void authFailuresChangeNothing() {
    TestCore t = TestCore.funded();
    byte[] root = t.core.stateRoot();
    SignedCommand good =
        t.cmd(2, new Place(1, I, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 1));
    SignedCommand[] bad = {
      SignedCommands.withBadSignature(good),
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID + 1, 2, 99, good.body()),
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 2, 0, good.body()), // stale nonce
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 42, 1, good.body()), // unknown account
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 0, 1, good.body()), // fee account cannot sign
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 2, 99, new Deposit(2, QUOTE, 5)), // not admin
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 2, 99, new Register(new byte[44])), // registered
      CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 0, 1, new Register(new byte[44])), // account 0
      CommandSigner.FAST.sign(
          Fixtures.CHAIN_ID, Limits.MAX_ACCOUNT + 1, 1, new Register(new byte[44])),
      CommandSigner.FAST.sign(
          Fixtures.CHAIN_ID, 9, 0, new Register(Fixtures.accountSigner(9).publicKey())),
      SignedCommands.signedByWrongKey(good, 3),
    };
    for (SignedCommand c : bad) {
      assertThat(t.exec(c).status()).as("%s", c.body()).isEqualTo(Status.AUTH_FAILED);
      assertThat(t.core.stateRoot()).isEqualTo(root);
    }
    assertThat(t.exec(good).status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.exec(good).status()).as("replay").isEqualTo(Status.AUTH_FAILED);
  }

  @Test
  void realEd25519EndToEnd() {
    TestCore t = TestCore.ed25519();
    assertThat(t.exec(SignedCommands.register(2, 1)).status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.exec(SignedCommands.deposit(1, 2, QUOTE, 1_000)).status())
        .isEqualTo(Status.ACCEPTED);
    SignedCommand order = SignedCommands.limit(2, 2, I, Side.BUY, 100, 5, TimeInForce.GTC);
    assertThat(t.exec(SignedCommands.withBadSignature(order)).status())
        .isEqualTo(Status.AUTH_FAILED);
    assertThat(t.exec(order).status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.held(2, QUOTE)).isEqualTo(500);
    // A self-signed Register whose body key did not sign the command is refused.
    SignedCommand forged =
        SignedCommands.sign(
            Fixtures.accountSigner(4),
            Fixtures.CHAIN_ID,
            3,
            1,
            new Register(Fixtures.accountSigner(3).publicKey()));
    assertThat(t.exec(forged).status()).isEqualTo(Status.AUTH_FAILED);
  }

  @Test
  void signatureChecksCanBeDisabledForBenchmarksOnly() {
    ExecutionCore core =
        ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER, false);
    SignedCommand forged =
        SignedCommands.withBadSignature(
            CommandSigner.FAST.sign(Fixtures.CHAIN_ID, 1, 1, new Deposit(1, QUOTE, 5)));
    assertThat(core.execute(forged, ExecutionCore.SILENT).status()).isEqualTo(Status.ACCEPTED);
  }

  // ------------------------------------------------------------------ copy and views

  @Test
  void copyIsIndependent() {
    TestCore t = TestCore.funded();
    t.gtc(3, Side.SELL, 100, 5);
    ExecutionCore copy = t.core.copy();
    byte[] root = copy.stateRoot();
    assertThat(root).isEqualTo(t.core.stateRoot());
    t.gtc(2, Side.BUY, 100, 5);
    assertThat(copy.stateRoot()).isEqualTo(root);
    assertThat(copy.restingOrders(I, Side.SELL)).hasSize(1);
    assertThat(t.core.restingOrders(I, Side.SELL)).isEmpty();
    assertThat(copy.genesisConfig()).isSameAs(t.core.genesisConfig());
  }

  @Test
  void viewsReflectState() {
    TestCore t = TestCore.funded();
    t.gtc(3, Side.SELL, 101, 2);
    t.gtc(4, Side.SELL, 101, 3);
    assertThat(t.core.depth(I, Side.SELL, 3)).containsExactly(new OrderBook.Level(101, 5));
    assertThat(t.core.instrumentIds()).containsExactly(1, 2);
    assertThat(t.core.isRegistered(6)).isTrue();
    assertThat(t.core.ledgerTotal(BASE)).isEqualTo(5 * 10_000);
    assertThat(t.core.nextOrderId()).isEqualTo(3);
  }
}
