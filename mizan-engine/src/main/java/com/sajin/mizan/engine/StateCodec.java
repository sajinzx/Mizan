package com.sajin.mizan.engine;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Canonical, versioned serialization of the entire engine state, used for snapshots (§6.7).
 * Big-endian:
 *
 * <pre>
 * version:1 = 1
 * nextOrderId:8  nextTradeSeq:8
 * accountCount:4  (account:8 lastNonce:8 keyLen:1 key)*        account order
 * balanceCount:4  (account:8 asset:2 free:8 held:8)*          key order
 * bookCount:4     (instrument:2 orderCount:4 order*)*         instrument order
 *   order = id:8 account:8 side:1 tif:1 price:8 remaining:8   bids best-first, then asks; FIFO
 * </pre>
 *
 * <p>Instruments and assets come from genesis, not from the snapshot. Encoding the same state
 * always yields the same bytes, and {@code encode(decode(b)) == b}.
 */
public final class StateCodec {
  public static final int VERSION = 1;

  private StateCodec() {}

  public static byte[] encode(ExecutionCore core) {
    List<byte[]> accounts = new ArrayList<>();
    core.ledger()
        .forEachAccount(
            (account, nonce, pk) ->
                accounts.add(
                    ByteBuffer.allocate(8 + 8 + 1 + pk.length)
                        .putLong(account)
                        .putLong(nonce)
                        .put((byte) pk.length)
                        .put(pk)
                        .array()));
    List<byte[]> balances = new ArrayList<>();
    core.ledger()
        .forEachBalance(
            (account, asset, free, held) ->
                balances.add(
                    ByteBuffer.allocate(8 + 2 + 8 + 8)
                        .putLong(account)
                        .putShort((short) asset)
                        .putLong(free)
                        .putLong(held)
                        .array()));
    List<byte[]> books = new ArrayList<>();
    for (OrderBook b : core.books()) {
      List<Order> orders = new ArrayList<>();
      for (Side s : List.of(Side.BUY, Side.SELL)) {
        for (PriceLevel l : b.levels(s)) {
          for (Order o : l.orders()) {
            orders.add(o);
          }
        }
      }
      ByteBuffer bb = ByteBuffer.allocate(2 + 4 + Math.multiplyExact(orders.size(), ORDER_BYTES));
      bb.putShort((short) b.instrument()).putInt(orders.size());
      for (Order o : orders) {
        bb.putLong(o.id())
            .putLong(o.accountId())
            .put((byte) o.side().code())
            .put((byte) o.tif().code())
            .putLong(o.limitPrice())
            .putLong(o.remaining());
      }
      books.add(bb.array());
    }

    int size = 1 + 16 + 4 + 4 + 4;
    for (byte[] a : accounts) {
      size += a.length;
    }
    size += Math.multiplyExact(balances.size(), BALANCE_BYTES);
    for (byte[] b : books) {
      size += b.length;
    }
    ByteBuffer out = ByteBuffer.allocate(size);
    out.put((byte) VERSION).putLong(core.nextOrderId()).putLong(core.nextTradeSeq());
    out.putInt(accounts.size());
    accounts.forEach(out::put);
    out.putInt(balances.size());
    balances.forEach(out::put);
    out.putInt(books.size());
    books.forEach(out::put);
    return out.array();
  }

  private static final int ORDER_BYTES = 8 + 8 + 1 + 1 + 8 + 8;
  private static final int BALANCE_BYTES = 8 + 2 + 8 + 8;

  /**
   * Decodes a snapshot against its genesis.
   *
   * @throws ProtocolException if the bytes are malformed, non-canonical or inconsistent with
   *     genesis
   */
  public static ExecutionCore decode(byte[] bytes, GenesisConfig g, SignatureVerifier verifier)
      throws ProtocolException {
    try {
      ByteBuffer in = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
      if (in.get() != VERSION) {
        throw new ProtocolException("unknown state version");
      }
      long nextOrderId = in.getLong();
      long nextTradeSeq = in.getLong();
      if (nextOrderId < 1 || nextTradeSeq < 1) {
        throw new ProtocolException("bad counters");
      }
      Ledger ledger = new Ledger();
      int accounts = count(in);
      for (int i = 0; i < accounts; i++) {
        long account = in.getLong();
        long nonce = in.getLong();
        byte[] pk = new byte[Byte.toUnsignedInt(in.get())];
        in.get(pk);
        ledger.restoreAccount(account, pk, nonce);
      }
      int balances = count(in);
      for (int i = 0; i < balances; i++) {
        long account = in.getLong();
        int asset = Short.toUnsignedInt(in.getShort());
        ledger.restoreBalance(account, asset, in.getLong(), in.getLong());
      }
      TreeMap<Integer, Instrument> instruments = new TreeMap<>();
      for (Instrument i : g.instruments()) {
        instruments.put(i.id(), i);
      }
      int bookCount = count(in);
      if (bookCount != instruments.size()) {
        throw new ProtocolException("book count does not match genesis");
      }
      TreeMap<Integer, OrderBook> books = new TreeMap<>();
      for (int i = 0; i < bookCount; i++) {
        int instrument = Short.toUnsignedInt(in.getShort());
        if (!instruments.containsKey(instrument) || books.containsKey(instrument)) {
          throw new ProtocolException("unexpected instrument " + instrument);
        }
        OrderBook book = new OrderBook(instrument);
        int orders = count(in);
        for (int k = 0; k < orders; k++) {
          long id = in.getLong();
          long account = in.getLong();
          Side side = Side.fromCode(Byte.toUnsignedInt(in.get()));
          TimeInForce tif = TimeInForce.fromCode(Byte.toUnsignedInt(in.get()));
          long price = in.getLong();
          long remaining = in.getLong();
          if (side == null
              || (tif != TimeInForce.GTC && tif != TimeInForce.POST_ONLY)
              || id <= 0
              || id >= nextOrderId
              || price <= 0
              || remaining <= 0) {
            throw new ProtocolException("bad resting order " + id);
          }
          book.rest(new LimitOrder(id, account, instrument, side, tif, price, remaining));
        }
        books.put(instrument, book);
      }
      if (in.hasRemaining()) {
        throw new ProtocolException("trailing bytes");
      }
      ExecutionCore core =
          ExecutionCore.restore(g, verifier, ledger, books, nextOrderId, nextTradeSeq);
      if (!Arrays.equals(encode(core), bytes)) {
        throw new ProtocolException("non-canonical state encoding");
      }
      return core;
    } catch (BufferUnderflowException | IllegalArgumentException | ArithmeticException e) {
      throw new ProtocolException("malformed state: " + e.getMessage());
    } catch (InvariantViolationError e) {
      throw new ProtocolException("inconsistent state: " + e.getMessage());
    }
  }

  private static int count(ByteBuffer in) throws ProtocolException {
    int n = in.getInt();
    if (n < 0 || n > in.remaining()) {
      throw new ProtocolException("bad count " + n);
    }
    return n;
  }
}
