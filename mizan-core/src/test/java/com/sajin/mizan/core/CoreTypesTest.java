package com.sajin.mizan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CoreTypesTest {

  static Instrument instrument(int id) {
    return new Instrument(id, 1, 2, 1, 1, 0, 10, Limits.MAX_PRICE, Limits.MAX_QTY);
  }

  static GenesisConfig genesis(List<Instrument> instruments) {
    return new GenesisConfig(
        4242,
        1,
        new byte[44],
        List.of(new GenesisConfig.Asset(1, "BASE"), new GenesisConfig.Asset(2, "QUOTE")),
        instruments,
        List.of(new GenesisConfig.Validator(0, new byte[44], "127.0.0.1", 7000)),
        new GenesisConfig.BlockParams(500, 50, 1),
        new GenesisConfig.ConsensusParams(1000, 500));
  }

  @Test
  void enumOrdinalsAreFrozenWireValues() {
    assertThat(Side.values()).containsExactly(Side.BUY, Side.SELL);
    assertThat(OrderType.values()).containsExactly(OrderType.LIMIT, OrderType.MARKET);
    assertThat(TimeInForce.values())
        .containsExactly(TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK, TimeInForce.POST_ONLY);
    assertThat(Side.BUY.opposite()).isEqualTo(Side.SELL);
    assertThat(Side.SELL.opposite()).isEqualTo(Side.BUY);
  }

  @Test
  void limitsMatchSpec() {
    assertThat(Limits.MAX_PRICE).isEqualTo(2_147_483_648L);
    assertThat(Limits.MAX_QTY).isEqualTo(2_147_483_648L);
    // A single order's notional cannot overflow a long.
    assertThat(Math.multiplyExact(Limits.MAX_PRICE, Limits.MAX_QTY)).isEqualTo(1L << 62);
    assertThat(Limits.MAX_DEPOSIT).isEqualTo(1L << 50);
    assertThat(Limits.MAX_ACCOUNT).isEqualTo((1L << 47) - 1);
    assertThat(Limits.MAX_ASSET).isEqualTo(0xFFFF);
    assertThat(Limits.MAX_ENVELOPE_BYTES).isEqualTo(256);
    assertThat(Accounts.FEE_ACCOUNT).isZero();
    assertThat(Accounts.ADMIN_ACCOUNT).isEqualTo(1);
  }

  @Test
  void commandsConstructAndRejectNulls() {
    OrderCommand place = new Place(7, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 5);
    var sc = new SignedCommand(4242, 2, 1, place, new byte[64], new byte[97]);
    assertThat(sc.body()).isEqualTo(place);
    assertThat(new Deposit(2, 1, 1000).amount()).isEqualTo(1000);
    assertThat(new Cancel(9, 1).orderId()).isEqualTo(9);
    assertThat(new Register(new byte[44]).pubKey()).hasSize(44);

    assertThatThrownBy(() -> new Register(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Place(1, 1, null, OrderType.LIMIT, TimeInForce.GTC, 1, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SignedCommand(1, 1, 1, null, new byte[64], new byte[1]))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Trade(1, 1, 1, 2, 3, 4, null, 1, 1, 0))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void commandBodiesAreExhaustiveForPatternSwitch() {
    Stream<OrderCommand> all =
        Stream.of(
            new Register(new byte[44]),
            new Deposit(2, 1, 1),
            new Place(1, 1, Side.SELL, OrderType.MARKET, TimeInForce.IOC, 1, 1),
            new Cancel(1, 1));
    Set<String> tags =
        all.map(
                c ->
                    switch (c) {
                      case Register r -> "REGISTER";
                      case Deposit d -> "DEPOSIT";
                      case Place p -> "PLACE";
                      case Cancel x -> "CANCEL";
                    })
            .collect(Collectors.toSet());
    assertThat(tags).containsExactlyInAnyOrder("REGISTER", "DEPOSIT", "PLACE", "CANCEL");
  }

  @Test
  void execResultFactories() {
    assertThat(ExecResult.accepted(42))
        .isEqualTo(new ExecResult(ExecResult.Status.ACCEPTED, 42, null));
    assertThat(ExecResult.rejected("x").status()).isEqualTo(ExecResult.Status.REJECTED);
    assertThat(ExecResult.rejected("x").orderId()).isZero();
    assertThat(ExecResult.authFailed("y").reason()).isEqualTo("y");
  }

  @Test
  void exceptionHierarchyAndCodes() {
    List<OrderRejectedException> rejects =
        List.of(
            new InvalidOrderException("a"),
            new InsufficientBalanceException("b"),
            new LimitExceededException("c"),
            new InvalidSignatureException("d"),
            new StaleNonceException("e"));
    assertThat(rejects).allSatisfy(e -> assertThat(e).isInstanceOf(RuntimeException.class));
    assertThat(rejects.stream().map(OrderRejectedException::reasonCode))
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(rejects.get(0).getStackTrace()).isEmpty();
    assertThat(new InvariantViolationError("bug")).isInstanceOf(Error.class);
    assertThat(new ProtocolException("p")).isInstanceOf(IOException.class);
    assertThat(new PersistenceException("q")).isInstanceOf(IOException.class);
    assertThat(new ChainUnavailableException("r"))
        .isInstanceOf(Exception.class)
        .isNotInstanceOf(RuntimeException.class);
  }

  @Test
  void instrumentValidation() {
    Instrument i = instrument(1);
    assertThat(i.priceCap()).isEqualTo(Limits.MAX_PRICE);
    assertThat(new Instrument(1, 1, 2, 1, 1, 0, 0, 100, 5).qtyCap()).isEqualTo(5);
    assertThatThrownBy(() -> new Instrument(1, 1, 1, 1, 1, 0, 0, 100, 5))
        .hasMessageContaining("differ");
    assertThatThrownBy(() -> new Instrument(1, 1, 2, 0, 1, 0, 0, 100, 5))
        .hasMessageContaining("tick");
    assertThatThrownBy(() -> new Instrument(1, 1, 2, 1, 1, 10_001, 0, 100, 5))
        .hasMessageContaining("makerBps");
    assertThatThrownBy(() -> new Instrument(1, 1, 2, 1, 1, 0, 0, Limits.MAX_PRICE + 1, 5))
        .hasMessageContaining("maxPrice");
    assertThatThrownBy(() -> new Instrument(0x10000, 1, 2, 1, 1, 0, 0, 100, 5))
        .hasMessageContaining("instrument id");
  }

  @Test
  void genesisValidation() {
    GenesisConfig g = genesis(List.of(instrument(1)));
    assertThat(g.instrument(1)).contains(instrument(1));
    assertThat(g.instrument(2)).isEmpty();
    assertThatThrownBy(() -> g.instruments().add(instrument(3)))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(() -> genesis(List.of(instrument(1), instrument(1))))
        .hasMessageContaining("duplicate instrument");
    assertThatThrownBy(() -> genesis(List.of(new Instrument(1, 1, 9, 1, 1, 0, 0, 100, 5))))
        .hasMessageContaining("unknown asset");
    assertThatThrownBy(() -> new GenesisConfig.BlockParams(0, 50, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GenesisConfig.Validator(0, new byte[44], "h", 70_000))
        .hasMessageContaining("port");
  }
}
