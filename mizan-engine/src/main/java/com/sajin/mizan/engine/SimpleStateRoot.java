package com.sajin.mizan.engine;

import com.sajin.mizan.crypto.Hashing;
import com.sajin.mizan.crypto.SimpleMerkle;
import com.sajin.mizan.crypto.SimpleMerkle.Leaf;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * State root, version 1 (§5.6), fully recomputed on each call:
 *
 * <pre>
 * stateRoot = SHA256(0x02 || ledgerRoot || metaRoot || bookRoot || nextTradeSeq:8 || nextOrderId:8)
 * ledger leaf: key = account << 16 | asset   value = free:8 | held:8   (zero entries omitted)
 * meta   leaf: key = account                 value = lastNonce:8 | SHA256(pubKey):32
 * book   leaf: key = orderId                 value = instrument:2 | side:1 | account:8 | price:8 | remaining:8
 * </pre>
 *
 * <p>Order ids are arrival order, so the book leaves also pin FIFO queue order.
 */
public final class SimpleStateRoot {
  private static final byte ROOT_TAG = 0x02;

  private SimpleStateRoot() {}

  public static byte[] compute(ExecutionCore core) {
    List<Leaf> ledgerLeaves = new ArrayList<>();
    core.ledger()
        .forEachBalance(
            (account, asset, free, held) ->
                ledgerLeaves.add(
                    new Leaf(
                        Ledger.key(account, asset),
                        ByteBuffer.allocate(16).putLong(free).putLong(held).array())));

    List<Leaf> metaLeaves = new ArrayList<>();
    core.ledger()
        .forEachAccount(
            (account, lastNonce, pubKey) ->
                metaLeaves.add(
                    new Leaf(
                        account,
                        ByteBuffer.allocate(8 + 32)
                            .putLong(lastNonce)
                            .put(Hashing.sha256(pubKey))
                            .array())));

    List<Order> orders = new ArrayList<>();
    for (OrderBook b : core.books()) {
      orders.addAll(b.orders());
    }
    orders.sort((a, b) -> Long.compare(a.id(), b.id()));
    List<Leaf> bookLeaves = new ArrayList<>(orders.size());
    for (Order o : orders) {
      bookLeaves.add(
          new Leaf(
              o.id(),
              ByteBuffer.allocate(2 + 1 + 8 + 8 + 8)
                  .putShort((short) o.instrument())
                  .put((byte) o.side().code())
                  .putLong(o.accountId())
                  .putLong(o.limitPrice())
                  .putLong(o.remaining())
                  .array()));
    }

    return Hashing.sha256(
        new byte[] {ROOT_TAG},
        SimpleMerkle.root(ledgerLeaves),
        SimpleMerkle.root(metaLeaves),
        SimpleMerkle.root(bookLeaves),
        ByteBuffer.allocate(16).putLong(core.nextTradeSeq()).putLong(core.nextOrderId()).array());
  }
}
