package com.sajin.mizan.testkit;

/**
 * Stored test vectors for fixture keys, computed independently with Python {@code cryptography}:
 * {@code Ed25519PrivateKey.from_private_bytes(sha256(b"mizan-fixture-account" + pack(">q", 1)))}.
 */
final class FixtureVectors {
  static final String ADMIN_PUBLIC_KEY_HEX =
      "a132db7bc5ce4fdc96d5e5e034fd02e9e514ccde74670d79a11fd0d01933b17a";

  private FixtureVectors() {}
}
