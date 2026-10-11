package com.sajin.mizan.core;

import java.util.Objects;

/**
 * One fill between a resting maker and an incoming taker.
 *
 * @param buyEscrowPrice the price at which the buyer's quote was escrowed, or {@code 0} when the
 *     buyer was not pre-escrowed (market buy)
 */
public record Trade(
    long seq,
    int instrument,
    long makerOrderId,
    long takerOrderId,
    long buyAccount,
    long sellAccount,
    Side takerSide,
    long price,
    long qty,
    long buyEscrowPrice) {
  public Trade {
    Objects.requireNonNull(takerSide, "takerSide");
  }
}
