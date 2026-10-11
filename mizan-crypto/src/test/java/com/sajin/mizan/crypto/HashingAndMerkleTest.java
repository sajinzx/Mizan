package com.sajin.mizan.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.crypto.SimpleMerkle.Leaf;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashingAndMerkleTest {
  private static final HexFormat HEX = HexFormat.of();

  private static String hex(byte[] b) {
    return HEX.formatHex(b);
  }

  @Test
  void sha256KnownAnswer() {
    assertThat(hex(Hashing.sha256("abc".getBytes(StandardCharsets.US_ASCII))))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(Hashing.sha256("a".getBytes(StandardCharsets.US_ASCII), new byte[] {'b', 'c'}))
        .isEqualTo(Hashing.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  // Expected roots computed independently with Python's hashlib (see PROGRESS.md, M1-T5).

  @Test
  void emptyTreeIsZeros() {
    assertThat(SimpleMerkle.root(List.of())).isEqualTo(new byte[32]);
    assertThat(SimpleMerkle.rootOfList(List.of())).isEqualTo(new byte[32]);
  }

  @Test
  void singleLeafKnownAnswer() {
    assertThat(hex(SimpleMerkle.root(List.of(new Leaf(1, new byte[] {(byte) 0xAB})))))
        .isEqualTo("0d6f2eed92711ad304f63cc74a251d15360e29cb4310ba6b85d3266bf7058f96");
  }

  @Test
  void oddCountsPairLastNodeWithItself() {
    List<Leaf> three =
        List.of(new Leaf(1, bytes("a")), new Leaf(2, bytes("bb")), new Leaf(5, new byte[0]));
    assertThat(hex(SimpleMerkle.root(three)))
        .isEqualTo("4af74962013485384ebb1a5c18012d503781b8352b81b2c59d3788d2c5b67acd");

    List<Leaf> five = new ArrayList<>();
    for (int k = 1; k <= 5; k++) {
      byte[] v = new byte[k];
      java.util.Arrays.fill(v, (byte) k);
      five.add(new Leaf(k, v));
    }
    assertThat(hex(SimpleMerkle.root(five)))
        .isEqualTo("6eed48d0945111efcde6031f35c9e0805395839f7a69130babf80c45ca496f3d");
  }

  @Test
  void listRootUsesIndexAsKey() {
    List<byte[]> items = List.of(bytes("x"), bytes("y"), bytes("z"));
    assertThat(hex(SimpleMerkle.rootOfList(items)))
        .isEqualTo("5a2ab7cdaf84493f118220dcb1ffa0e2a2e5d8d3f046ff760a698ada193ff7c6");
    assertThat(SimpleMerkle.rootOfList(items))
        .isEqualTo(
            SimpleMerkle.root(
                List.of(
                    new Leaf(0, bytes("x")), new Leaf(1, bytes("y")), new Leaf(2, bytes("z")))));
  }

  @Test
  void leafAndNodeHashesAreDomainSeparated() {
    var md = Hashing.newSha256();
    byte[] l = SimpleMerkle.leafHash(md, 1, bytes("a"));
    byte[] r = SimpleMerkle.leafHash(md, 2, bytes("b"));
    byte[] node = SimpleMerkle.nodeHash(md, l, r);
    // A two-leaf root is the node hash of the two leaves.
    assertThat(SimpleMerkle.root(List.of(new Leaf(1, bytes("a")), new Leaf(2, bytes("b")))))
        .isEqualTo(node);
    // Hashing the same bytes as a leaf (tag 0x00) cannot reproduce the node hash (tag 0x01).
    byte[] keyAndValue =
        ByteBuffer.allocate(64).put(l, 8, 24).put(r).array(); // arbitrary 64-byte payload
    assertThat(Hashing.sha256(new byte[] {0x01}, l, r)).isEqualTo(node);
    assertThat(Hashing.sha256(new byte[] {0x00}, l, r)).isNotEqualTo(node);
    assertThat(SimpleMerkle.leafHash(md, 0, keyAndValue)).isNotEqualTo(node);
  }

  @Test
  void rootChangesWithAnyLeafChange() {
    List<Leaf> base = List.of(new Leaf(1, bytes("a")), new Leaf(2, bytes("b")));
    byte[] r = SimpleMerkle.root(base);
    assertThat(SimpleMerkle.root(List.of(new Leaf(1, bytes("a")), new Leaf(3, bytes("b")))))
        .isNotEqualTo(r);
    assertThat(SimpleMerkle.root(List.of(new Leaf(1, bytes("a")), new Leaf(2, bytes("c")))))
        .isNotEqualTo(r);
    assertThat(SimpleMerkle.root(List.of(new Leaf(1, bytes("a"))))).isNotEqualTo(r);
  }

  @Test
  void rejectsUnsortedOrDuplicateKeys() {
    assertThatThrownBy(
            () -> SimpleMerkle.root(List.of(new Leaf(2, bytes("a")), new Leaf(1, bytes("b")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SimpleMerkle.root(List.of(new Leaf(1, bytes("a")), new Leaf(1, bytes("b")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }
}
