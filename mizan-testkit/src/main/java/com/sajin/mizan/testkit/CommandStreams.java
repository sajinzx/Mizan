package com.sajin.mizan.testkit;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.engine.RestingOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Command streams over a deliberately small universe (§9): five traders, two instruments, prices
 * 95–105, quantities 1–20, every TIF, ~10% cancels and ~10% hostile commands. The universe is small
 * so collisions (STP, partial fills, escrow release, FOK edges) happen often.
 *
 * <p>Streams are built from abstract {@link Op}s, which keeps them easy to shrink in property
 * tests. Cancels target a live resting order picked from the current book, which is why generation
 * is incremental ({@link Generator#next}).
 */
public final class CommandStreams {
  public static final List<Long> TRADERS = List.of(2L, 3L, 4L, 5L, 6L);
  public static final List<Integer> INSTRUMENTS =
      List.of(Fixtures.INSTRUMENT, Fixtures.ALT_INSTRUMENT);
  public static final long QUOTE_DEPOSIT = 20_000;
  public static final long BASE_DEPOSIT = 150;

  private CommandStreams() {}

  /**
   * One abstract step. Field ranges: {@code kind} 0–99, {@code account} 0–4, {@code instrument}
   * 0–1, {@code price} 95–105, {@code qty} 1–20, {@code tif} 0–9; {@code misc} is any long.
   */
  public record Op(
      int kind, int account, int instrument, boolean buy, int price, int qty, int tif, long misc) {}

  /** A random op from {@code rnd}, for seeded (non-jqwik) tests. */
  public static Op randomOp(SplittableRandom rnd) {
    return new Op(
        rnd.nextInt(100),
        rnd.nextInt(TRADERS.size()),
        rnd.nextInt(INSTRUMENTS.size()),
        rnd.nextBoolean(),
        rnd.nextInt(95, 106),
        rnd.nextInt(1, 21),
        rnd.nextInt(10),
        rnd.nextLong());
  }

  /** Builds signed commands step by step, tracking nonces per account. */
  public static final class Generator {
    private final CommandSigner signer;
    private final long[] nonces = new long[8]; // index = account id (0..6)

    public Generator(CommandSigner signer) {
      this.signer = signer;
    }

    private long nextNonce(long account) {
      return ++nonces[(int) account];
    }

    private SignedCommand sign(long account, OrderCommand body) {
      return signer.sign(Fixtures.CHAIN_ID, account, nextNonce(account), body);
    }

    /** Registration of every trader plus an initial deposit of each asset to each. */
    public List<SignedCommand> setup() {
      List<SignedCommand> out = new ArrayList<>();
      for (long t : TRADERS) {
        out.add(sign(t, new Register(Fixtures.accountSigner(t).publicKey())));
      }
      for (long t : TRADERS) {
        out.add(sign(Accounts.ADMIN_ACCOUNT, new Deposit(t, Fixtures.QUOTE, QUOTE_DEPOSIT)));
        out.add(sign(Accounts.ADMIN_ACCOUNT, new Deposit(t, Fixtures.BASE, BASE_DEPOSIT)));
        out.add(sign(Accounts.ADMIN_ACCOUNT, new Deposit(t, Fixtures.ALT, BASE_DEPOSIT)));
      }
      return out;
    }

    /**
     * The command for {@code op}. {@code book} supplies the current resting orders (any
     * instrument), so cancels can target live orders.
     */
    public SignedCommand next(Op op, Supplier<List<RestingOrder>> book) {
      long account = TRADERS.get(op.account());
      int instrument = INSTRUMENTS.get(op.instrument());
      Side side = op.buy() ? Side.BUY : Side.SELL;
      int k = op.kind();
      long misc = op.misc();
      if (k < 58 || k >= 92) {
        TimeInForce tif =
            switch (op.tif()) {
              case 0, 1, 2, 3, 4 -> TimeInForce.GTC;
              case 5, 6 -> TimeInForce.IOC;
              case 7 -> TimeInForce.FOK;
              default -> TimeInForce.POST_ONLY;
            };
        return sign(
            account, new Place(misc, instrument, side, OrderType.LIMIT, tif, op.price(), op.qty()));
      }
      if (k < 68) {
        long collar = side == Side.BUY ? op.price() + 5 : op.price() - 5;
        return sign(
            account,
            new Place(misc, instrument, side, OrderType.MARKET, TimeInForce.IOC, collar, op.qty()));
      }
      if (k < 78) {
        List<RestingOrder> live = book.get();
        if (!live.isEmpty() && (misc & 7) != 0) {
          RestingOrder o = live.get((int) Math.floorMod(misc, (long) live.size()));
          long who = (misc & 15) == 1 ? account : o.account(); // sometimes a non-owner tries
          return sign(who, new Cancel(o.id(), o.instrument()));
        }
        return sign(account, new Cancel(Math.floorMod(misc, 50L) + 1, instrument));
      }
      if (k < 82) {
        int asset = (int) Math.floorMod(misc, 4L); // 0 is not an asset
        long amount = Math.floorMod(misc >> 8, 5_000L); // sometimes zero
        return sign(Accounts.ADMIN_ACCOUNT, new Deposit(account, asset, amount));
      }
      return hostile(op, account, instrument, side);
    }

    private SignedCommand hostile(Op op, long account, int instrument, Side side) {
      long misc = op.misc();
      Place place =
          new Place(misc, instrument, side, OrderType.LIMIT, TimeInForce.GTC, op.price(), op.qty());
      return switch ((int) Math.floorMod(misc, 10L)) {
        case 0 -> SignedCommands.withBadSignature(sign(account, place));
        case 1 -> // replay of an already used nonce
            signer.sign(Fixtures.CHAIN_ID, account, Math.max(1, nonces[(int) account]), place);
        case 2 -> signer.sign(Fixtures.CHAIN_ID + 1, account, nextNonce(account), place);
        case 3 ->
            sign(account, new Place(misc, 77, side, OrderType.LIMIT, TimeInForce.GTC, 100, 1));
        case 4 ->
            sign(
                account,
                new Place(
                    misc,
                    instrument,
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Limits.MAX_PRICE + 1,
                    op.qty()));
        case 5 ->
            sign(
                account,
                new Place(
                    misc,
                    instrument,
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    op.price(),
                    Long.MAX_VALUE));
        case 6 -> sign(account, new Deposit(account, Fixtures.QUOTE, 1_000)); // not admin
        case 7 -> sign(account, new Register(Fixtures.accountSigner(account).publicKey()));
        case 8 ->
            sign(
                account,
                new Place(misc, instrument, side, OrderType.MARKET, TimeInForce.GTC, 100, 1));
        default ->
            sign(
                account,
                new Place(
                    misc, instrument, side, OrderType.LIMIT, TimeInForce.GTC, -op.price(), 0));
      };
    }
  }
}
