package com.sajin.mizan.core;

/** Cancels a resting order owned by the signer. */
public record Cancel(long orderId, int instrument) implements OrderCommand {}
