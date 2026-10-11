package com.sajin.mizan.core;

/**
 * A tradable pair (§5.8): prices are in ticks of {@code quote}, quantities in lots of {@code base}.
 * Fees are in basis points of what each side receives.
 */
public record Instrument(
    int id,
    int base,
    int quote,
    long tick,
    long lot,
    int makerBps,
    int takerBps,
    long maxPrice,
    long maxQty) {
  public Instrument {
    require(id >= 0 && id <= 0xFFFF, "instrument id out of range: " + id);
    require(base >= 0 && base <= Limits.MAX_ASSET, "base asset out of range: " + base);
    require(quote >= 0 && quote <= Limits.MAX_ASSET, "quote asset out of range: " + quote);
    require(base != quote, "base and quote must differ");
    require(tick > 0 && lot > 0, "tick and lot must be positive");
    require(makerBps >= 0 && makerBps <= 10_000, "makerBps out of range: " + makerBps);
    require(takerBps >= 0 && takerBps <= 10_000, "takerBps out of range: " + takerBps);
    require(maxPrice > 0 && maxPrice <= Limits.MAX_PRICE, "maxPrice out of range: " + maxPrice);
    require(maxQty > 0 && maxQty <= Limits.MAX_QTY, "maxQty out of range: " + maxQty);
  }

  /** Effective upper bound on price: {@code min(MAX_PRICE, maxPrice)}. */
  public long priceCap() {
    return Math.min(Limits.MAX_PRICE, maxPrice);
  }

  /** Effective upper bound on quantity: {@code min(MAX_QTY, maxQty)}. */
  public long qtyCap() {
    return Math.min(Limits.MAX_QTY, maxQty);
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}
