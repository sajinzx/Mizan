package com.sajin.mizan.core;

/** Body of a signed command envelope (§5.3). */
public sealed interface OrderCommand permits Register, Deposit, Place, Cancel {}
