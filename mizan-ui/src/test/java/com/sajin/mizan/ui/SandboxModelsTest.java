package com.sajin.mizan.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.node.SandboxView;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The terminal's toolkit-free pieces: formatting, candle bucketing, sparkline history. */
class SandboxModelsTest {

  @Test
  void pricesAreTicksWithTwoDecimals() {
    assertThat(Fmt.price(25_005)).isEqualTo("250.05");
    assertThat(Fmt.price(1_234_567)).isEqualTo("12,345.67");
    assertThat(Fmt.price(-1)).isEqualTo("—");
    assertThat(Fmt.ticks("250.05")).isEqualTo(25_005);
    assertThat(Fmt.ticks("1,250")).isEqualTo(125_000);
    assertThat(Fmt.ticks("abc")).isEqualTo(-1);
    assertThat(Fmt.ticks("-3")).isEqualTo(-1);
    assertThat(Fmt.change(100, 110)).isEqualTo("+10.00%");
    assertThat(Fmt.change(0, 110)).isEqualTo("—");
    assertThat(Fmt.compact(12_345)).isEqualTo("12.3K");
    assertThat(Fmt.compact(4_560_000)).isEqualTo("4.56M");
    assertThat(Fmt.micros(850)).isEqualTo("850 µs");
    assertThat(Fmt.micros(12_300)).isEqualTo("12.3 ms");
    assertThat(Fmt.micros(0)).isEqualTo("—");
    assertThat(Fmt.rate(3.25)).isEqualTo("3.3/s");
  }

  static SandboxView.Candle c(long sec, long o, long h, long l, long cl, long v) {
    return new SandboxView.Candle(sec * 1_000, o, h, l, cl, v, 1);
  }

  @Test
  void oneSecondCandlesMergeIntoWiderBars() {
    List<SandboxView.Candle> one =
        List.of(
            c(100, 10, 12, 9, 11, 5),
            c(101, 11, 15, 11, 14, 2),
            c(104, 14, 14, 8, 9, 7),
            c(105, 9, 10, 9, 10, 1));
    List<SandboxView.Candle> five = CandleSeries.aggregate(one, 5, 10);
    assertThat(five).hasSize(2);
    SandboxView.Candle a = five.get(0);
    assertThat(a.startMillis()).isEqualTo(100_000);
    assertThat(a.open()).isEqualTo(10);
    assertThat(a.high()).isEqualTo(15);
    assertThat(a.low()).isEqualTo(8);
    assertThat(a.close()).isEqualTo(9);
    assertThat(a.volume()).isEqualTo(14);
    assertThat(a.trades()).isEqualTo(3);
    assertThat(five.get(1).open()).isEqualTo(9);
    assertThat(CandleSeries.aggregate(one, 1, 2))
        .hasSize(2)
        .last()
        .extracting(SandboxView.Candle::close)
        .isEqualTo(10L);
    assertThat(CandleSeries.aggregate(List.of(), 5, 10)).isEmpty();
  }

  @Test
  void historyKeepsTheNewestInOrder() {
    History h = new History(3);
    assertThat(h.values()).isEmpty();
    for (int i = 1; i <= 5; i++) {
      h.add(i);
    }
    assertThat(h.values()).containsExactly(3, 4, 5);
    assertThat(h.last()).isEqualTo(5);
    assertThat(h.max()).isEqualTo(5);
  }
}
