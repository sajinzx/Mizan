package com.sajin.mizan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SeedExtension.class)
class CommandCodecTest {

  /** One valid envelope per tag, with a recognisable signature. */
  static List<byte[]> samples() {
    byte[] pk = new byte[CommandCodec.PUBKEY_BYTES];
    Arrays.fill(pk, (byte) 7);
    List<OrderCommand> bodies =
        List.of(
            new Register(pk),
            new Deposit(5, 0xFFFF, Limits.MAX_DEPOSIT),
            new Place(-1, 3, Side.SELL, OrderType.LIMIT, TimeInForce.POST_ONLY, 100, 20),
            new Cancel(Long.MAX_VALUE, 0));
    return bodies.stream().map(b -> envelope(4242, 2, 9, b)).toList();
  }

  static byte[] envelope(int chainId, long account, long nonce, OrderCommand body) {
    byte[] unsigned = CommandCodec.encodeUnsigned(chainId, account, nonce, body);
    byte[] raw = Arrays.copyOf(unsigned, unsigned.length + CommandCodec.SIGNATURE_BYTES);
    for (int i = unsigned.length; i < raw.length; i++) {
      raw[i] = (byte) i;
    }
    return raw;
  }

  /** Decodes, asserting that the only possible failure is {@link ProtocolException}. */
  static boolean decodesOrRejects(byte[] raw) {
    try {
      SignedCommand sc = CommandCodec.decode(raw);
      // Whatever decodes must re-encode to exactly the same bytes (canonical encoding).
      assertThat(CommandCodec.encode(sc)).isEqualTo(raw);
      return true;
    } catch (ProtocolException expected) {
      return false;
    }
  }

  // ------------------------------------------------------------------ round trip

  @Property(tries = 2_000)
  void roundTrip(
      @ForAll int chainId,
      @ForAll long account,
      @ForAll long nonce,
      @ForAll("bodies") OrderCommand body,
      @ForAll("signatures") byte[] sig)
      throws ProtocolException {
    byte[] unsigned = CommandCodec.encodeUnsigned(chainId, account, nonce, body);
    SignedCommand sc = CommandCodec.assemble(unsigned, sig);

    assertThat(sc.chainId()).isEqualTo(chainId);
    assertThat(sc.accountId()).isEqualTo(account);
    assertThat(sc.nonce()).isEqualTo(nonce);
    assertThat(sc.signature()).isEqualTo(sig);
    assertThat(CommandCodec.unsignedBytes(sc)).isEqualTo(unsigned);
    assertThat(CommandCodec.encode(sc)).isEqualTo(sc.raw());
    assertThat(sc.raw()).hasSize(CommandCodec.envelopeLength(body));
    assertThat(sc.raw().length).isLessThanOrEqualTo(Limits.MAX_ENVELOPE_BYTES);
    if (body instanceof Register r) {
      assertThat(((Register) sc.body()).pubKey()).isEqualTo(r.pubKey());
    } else {
      assertThat(sc.body()).isEqualTo(body);
    }
  }

  @Provide
  Arbitrary<byte[]> signatures() {
    return Arbitraries.bytes().array(byte[].class).ofSize(CommandCodec.SIGNATURE_BYTES);
  }

  @Provide
  Arbitrary<OrderCommand> bodies() {
    Arbitrary<Integer> u16 = Arbitraries.integers().between(0, 0xFFFF);
    Arbitrary<Long> anyLong = Arbitraries.longs();
    Arbitrary<OrderCommand> register =
        Arbitraries.bytes()
            .array(byte[].class)
            .ofSize(CommandCodec.PUBKEY_BYTES)
            .map(Register::new);
    Arbitrary<OrderCommand> deposit = Combinators.combine(anyLong, u16, anyLong).as(Deposit::new);
    Arbitrary<OrderCommand> place =
        Combinators.combine(
                anyLong,
                u16,
                Arbitraries.of(Side.class),
                Arbitraries.of(OrderType.class),
                Arbitraries.of(TimeInForce.class),
                anyLong,
                anyLong)
            .as(Place::new);
    Arbitrary<OrderCommand> cancel = Combinators.combine(anyLong, u16).as(Cancel::new);
    return Arbitraries.oneOf(register, deposit, place, cancel);
  }

  // ------------------------------------------------------------------ fuzz

  @Test
  void fuzzRandomBytesOnlyProtocolException(Seed seed) {
    SplittableRandom rnd = seed.random();
    int cases = 200_000;
    int decoded = 0;
    for (int i = 0; i < cases; i++) {
      // Bias towards plausible prefixes so the fuzzer reaches the body decoders.
      byte[] raw = new byte[rnd.nextInt(0, Limits.MAX_ENVELOPE_BYTES + 40)];
      rnd.nextBytes(raw);
      if (raw.length >= 2 && rnd.nextBoolean()) {
        raw[0] = 1;
        raw[1] = (byte) rnd.nextInt(0, 6);
      }
      if (decodesOrRejects(raw)) {
        decoded++;
      }
    }
    System.out.println("codec fuzz: " + cases + " random cases, " + decoded + " decoded");
  }

  @Test
  void fuzzMutatedValidEnvelopesOnlyProtocolException(Seed seed) {
    SplittableRandom rnd = seed.random();
    List<byte[]> samples = samples();
    int cases = 100_000;
    for (int i = 0; i < cases; i++) {
      byte[] raw = samples.get(rnd.nextInt(samples.size())).clone();
      int flips = rnd.nextInt(1, 4);
      for (int f = 0; f < flips; f++) {
        raw[rnd.nextInt(raw.length)] ^= (byte) (1 << rnd.nextInt(8));
      }
      decodesOrRejects(raw);
    }
    System.out.println("codec fuzz: " + cases + " mutated cases");
  }

  @Test
  void everyTruncationAndExtensionIsRejected() {
    for (byte[] raw : samples()) {
      assertThat(decodesOrRejects(raw)).isTrue();
      for (int len = 0; len < raw.length; len++) {
        byte[] cut = Arrays.copyOf(raw, len);
        assertThatThrownBy(() -> CommandCodec.decode(cut))
            .as("truncated to %d of %d", len, raw.length)
            .isInstanceOf(ProtocolException.class);
      }
      for (int len = raw.length + 1; len <= Limits.MAX_ENVELOPE_BYTES + 8; len++) {
        byte[] ext = Arrays.copyOf(raw, len);
        assertThatThrownBy(() -> CommandCodec.decode(ext))
            .as("extended to %d", len)
            .isInstanceOf(ProtocolException.class);
      }
    }
  }

  // ------------------------------------------------------------------ individual rules

  private static void assertRejected(byte[] raw, String messagePart) {
    assertThatThrownBy(() -> CommandCodec.decode(raw))
        .isInstanceOf(ProtocolException.class)
        .hasMessageContaining(messagePart);
  }

  @Test
  void rejectsNullAndOversized() {
    assertRejected(null, "null");
    assertRejected(new byte[Limits.MAX_ENVELOPE_BYTES + 1], "too large");
    assertRejected(new byte[CommandCodec.HEADER_BYTES], "too short");
  }

  @Test
  void rejectsUnknownVersionAndTag() {
    byte[] raw = samples().get(2);
    raw[0] = 2;
    assertRejected(raw, "version");
    for (int tag : new int[] {0, 5, 255}) {
      byte[] t = samples().get(2);
      t[1] = (byte) tag;
      assertRejected(t, "tag");
    }
  }

  @Test
  void rejectsWrongPubKeyLength() {
    byte[] raw = samples().get(0);
    raw[CommandCodec.HEADER_BYTES] = 43;
    assertRejected(raw, "pubkey length");
  }

  @Test
  void rejectsOrdinalsOutOfRange() {
    int sideAt = CommandCodec.HEADER_BYTES + 8 + 2;
    String[] fields = {"side", "type", "tif"};
    int[] bad = {2, 2, 4};
    for (int k = 0; k < 3; k++) {
      byte[] raw = samples().get(2);
      raw[sideAt + k] = (byte) bad[k];
      assertRejected(raw, fields[k]);
      raw[sideAt + k] = (byte) 0xFF;
      assertRejected(raw, fields[k]);
    }
  }

  @Test
  void decodedCommandOwnsItsBytes() throws ProtocolException {
    byte[] raw = samples().get(3);
    SignedCommand sc = CommandCodec.decode(raw);
    raw[5] ^= 1;
    assertThat(CommandCodec.encode(sc)).isEqualTo(sc.raw()).isNotEqualTo(raw);
  }

  @Test
  void encodeRejectsUnrepresentableValues() {
    assertThatThrownBy(() -> CommandCodec.encodeUnsigned(1, 1, 1, new Cancel(1, 0x10000)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CommandCodec.encodeUnsigned(1, 1, 1, new Register(new byte[43])))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void layoutMatchesSpec() throws ProtocolException {
    byte[] raw =
        envelope(
            0x01020304,
            0x1112131415161718L,
            0x2122232425262728L,
            new Place(5, 0x0A0B, Side.SELL, OrderType.MARKET, TimeInForce.IOC, 6, 7));
    assertThat(Arrays.copyOfRange(raw, 0, 22))
        .containsExactly(
            1, 3, 1, 2, 3, 4, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x21, 0x22, 0x23,
            0x24, 0x25, 0x26, 0x27, 0x28);
    assertThat(Arrays.copyOfRange(raw, 22, 51))
        .containsExactly(
            0, 0, 0, 0, 0, 0, 0, 5, 0x0A, 0x0B, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0,
            0, 7);
    assertThat(raw).hasSize(22 + 29 + 64);
    assertThat(CommandCodec.decode(raw).body())
        .isEqualTo(new Place(5, 0x0A0B, Side.SELL, OrderType.MARKET, TimeInForce.IOC, 6, 7));
  }
}
