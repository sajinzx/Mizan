package com.sajin.mizan.node;

import com.sajin.mizan.core.Side;
import java.util.List;

/**
 * Immutable views of a running {@link Sandbox}, built on demand for dashboards. Prices and
 * quantities are integer ticks and lots, exactly as the engine stores them.
 */
public final class SandboxView {
  private SandboxView() {}

  public enum Severity {
    INFO,
    GOOD,
    WARN,
    CRITICAL
  }

  /** One book level; {@code cumulative} sums quantity from the best price outwards. */
  public record Level(long price, long qty, long cumulative) {}

  /** One-second OHLCV bucket. */
  public record Candle(
      long startMillis, long open, long high, long low, long close, long volume, int trades) {}

  public record Instrument(
      int id,
      String symbol,
      long last,
      long open,
      long high,
      long low,
      long volume,
      long notional,
      long trades,
      long bestBid,
      long bestAsk,
      long fair,
      List<Level> bids,
      List<Level> asks,
      List<Candle> candles) {}

  public record Trade(
      long seq,
      long height,
      int instrument,
      String symbol,
      Side takerSide,
      long price,
      long qty,
      long timeMillis,
      boolean mine) {}

  /** A committed block as the deterministic shadow replica applied it. */
  public record Block(
      long height,
      int round,
      int proposer,
      int commands,
      int accepted,
      int trades,
      long timeMillis,
      String stateRoot) {}

  /** {@code peerAgeMillis[j]} = time since this validator last heard validator j (-1 never). */
  public record Validator(
      int index,
      boolean up,
      boolean isolated,
      long height,
      String headHash,
      String stateRoot,
      boolean rootMatchesShadow,
      boolean nextProposer,
      List<Long> peerAgeMillis,
      int gatewayPort,
      long poolDepth,
      long roundBumps,
      long revokes,
      long equivocations,
      long decided,
      long finalP50Micros,
      long finalP99Micros,
      long restarts,
      long lastRecoveryMillis) {}

  public record Event(long timeMillis, Severity severity, String source, String text) {}

  public record MyOrder(
      long nonce,
      String kind,
      int instrument,
      Side side,
      String type,
      String tif,
      long price,
      long qty,
      String status,
      long orderId,
      long height,
      String reason,
      int via,
      long sentMillis,
      long ackMillis,
      long finalMillis) {}

  public record OpenOrder(long orderId, int instrument, Side side, long price, long remaining) {}

  public record Balance(int asset, String name, long free, long held) {}

  public record Stats(
      long commands,
      long accepted,
      long rejected,
      long trades,
      long blocks,
      double commandsPerSec,
      double tradesPerSec,
      double blocksPerSec,
      long ackP50Micros,
      long ackP99Micros,
      long finalP50Micros,
      long finalP99Micros,
      long botOrders,
      long busy,
      long revokes,
      long roundBumps,
      long equivocations,
      long syncedBlocks) {}

  /**
   * The determinism check: the shadow replica re-executes every committed block on its own; each
   * running validator's reported state root is compared with the shadow's root at that height.
   */
  public record Determinism(
      long shadowHeight, String shadowRoot, int compared, int matching, boolean ok) {}

  public record Bots(int makers, int takers, int ratePerSec, int volatilityBps, boolean running) {}

  public record Snapshot(
      long nowMillis,
      long height,
      int stateRootVersion,
      String dataDir,
      List<Instrument> instruments,
      List<Trade> trades,
      List<Block> blocks,
      List<Validator> validators,
      List<Event> events,
      List<MyOrder> myOrders,
      List<OpenOrder> myOpenOrders,
      List<Balance> myBalances,
      Stats stats,
      Determinism determinism,
      Bots bots) {}
}
