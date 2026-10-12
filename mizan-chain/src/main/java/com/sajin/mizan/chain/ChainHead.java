package com.sajin.mizan.chain;

/**
 * An immutable view of the committed head, published by the consensus thread after each commit and
 * readable from any thread (M9-T6 chain panel). Arrays are not copied; never mutate them.
 */
@SuppressWarnings("ArrayRecordComponent")
public record ChainHead(long height, byte[] hash, byte[] stateRoot) {}
