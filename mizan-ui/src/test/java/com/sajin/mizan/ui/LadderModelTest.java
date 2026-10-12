package com.sajin.mizan.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.net.md.DepthBook;
import com.sajin.mizan.net.md.MdCodec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M5-T4: view-model only; no JavaFX toolkit needed. */
class LadderModelTest {

  @Test
  void ladderShowsBestLevelsWithAsksBestAtBottom() {
    LadderModel m = new LadderModel(1);
    m.setBook(
        List.of(new DepthBook.Level(100, 5), new DepthBook.Level(99, 10)),
        List.of(new DepthBook.Level(101, 2), new DepthBook.Level(102, 8)));
    assertThat(m.bids()).extracting(LadderModel.Row::price).containsExactly(100L, 99L);
    assertThat(m.asks()).extracting(LadderModel.Row::price).containsExactly(102L, 101L);
    assertThat(m.bids().get(1).bar()).isEqualTo(1.0);
    assertThat(m.asks().get(1).bar()).isEqualTo(0.2);
    assertThat(m.spread()).isEqualTo(1);
    assertThat(m.bids().get(0).text()).contains("100").contains("5");
  }

  @Test
  void depthIsCappedAndEmptySidesHaveNoSpread() {
    LadderModel m = new LadderModel(1);
    List<DepthBook.Level> many = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      many.add(new DepthBook.Level(100 - i, 1));
    }
    m.setBook(many, List.of());
    assertThat(m.bids()).hasSize(LadderModel.DEPTH);
    assertThat(m.spread()).isEqualTo(-1);
  }

  @Test
  void tradesAreFilteredBoundedAndRevoked() {
    LadderModel m = new LadderModel(1);
    for (long s = 1; s <= 30; s++) {
      m.onTrade(new MdCodec.TradePrint(s, 1, s, Side.BUY, 100, 1));
    }
    m.onTrade(new MdCodec.TradePrint(31, 2, 31, Side.SELL, 100, 1)); // other instrument
    assertThat(m.trades()).hasSize(LadderModel.TRADES);
    assertThat(m.trades().get(0)).startsWith("#30 BUY");
    m.onRevoke(25);
    assertThat(m.trades()).hasSize(14);
    assertThat(m.trades().get(0)).startsWith("#24 ");
    m.onStatus(77, 3, 2);
    assertThat(m.status())
        .contains("seq 77")
        .contains("gaps 3")
        .contains("snapshots 2")
        .contains("REVOKED from trade #25");
  }
}
