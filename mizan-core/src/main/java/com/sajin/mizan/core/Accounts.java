package com.sajin.mizan.core;

/** Well-known accounts created at genesis (§5.3). */
public final class Accounts {
  /** Collects fees. Has no key and can never sign. */
  public static final long FEE_ACCOUNT = 0;

  /** Admin/faucet account holding the genesis admin key; the only account allowed to deposit. */
  public static final long ADMIN_ACCOUNT = 1;

  private Accounts() {}
}
