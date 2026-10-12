package com.sajin.mizan.sim;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.testkit.Fixtures;

/**
 * Genesis for local development and demos: the fixture genesis, whose admin key is derived from a
 * public label. <b>Never</b> use it for anything but local experiments; anyone can sign as its
 * admin.
 */
public final class DevGenesis {
  private DevGenesis() {}

  public static GenesisConfig config(int validators) {
    return Fixtures.genesis(validators);
  }

  public static GenesisConfig config() {
    return config(1);
  }

  /** The public dev admin key (can deposit any amount; local experiments only). */
  public static Signer adminKey() {
    return Fixtures.adminSigner();
  }

  /** The public dev key of {@code account}. */
  public static Signer accountKey(long account) {
    return Fixtures.accountSigner(account);
  }

  /** The public dev key of validator {@code index}. */
  public static Signer validatorKey(int index) {
    return Fixtures.validatorSigner(index);
  }
}
