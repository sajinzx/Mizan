package com.sajin.mizan.core;

/** Admin-only faucet: credits {@code amount} of {@code asset} to account {@code target}. */
public record Deposit(long target, int asset, long amount) implements OrderCommand {}
