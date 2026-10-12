package com.sajin.mizan.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.net.Messages;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M9-T6: chain panel view model; no JavaFX toolkit needed. */
class ChainPanelModelTest {

  static Messages.ChainInfo info(List<Messages.ChainInfo.Validator> vs) {
    byte[] head = new byte[32];
    head[0] = (byte) 0xAB;
    byte[] root = new byte[32];
    root[0] = 0x12;
    return new Messages.ChainInfo(50, head, root, vs);
  }

  @Test
  void showsHeadRootAndLiveness() {
    ChainPanelModel m = new ChainPanelModel();
    assertThat(m.header()).contains("waiting");
    m.update(
        info(
            List.of(
                new Messages.ChainInfo.Validator(0, 50, 0),
                new Messages.ChainInfo.Validator(1, 48, 300),
                new Messages.ChainInfo.Validator(2, 30, 9_000),
                new Messages.ChainInfo.Validator(3, -1, -1))));
    assertThat(m.header()).contains("height 50").contains("head ab0000").contains("root 120000");
    List<ChainPanelModel.ValidatorRow> rows = m.validators();
    assertThat(rows)
        .extracting(ChainPanelModel.ValidatorRow::liveness)
        .containsExactly(
            ChainPanelModel.Liveness.LIVE,
            ChainPanelModel.Liveness.LIVE,
            ChainPanelModel.Liveness.STALE,
            ChainPanelModel.Liveness.UNKNOWN);
    assertThat(rows.get(1).lag()).isEqualTo(2);
    assertThat(rows.get(1).text()).contains("v1").contains("lag 2").contains("300 ms ago");
    assertThat(rows.get(3).text()).contains("h=?").contains("never");
    assertThat(m.quorumLine()).isEqualTo("2/4 live — BELOW QUORUM");
  }

  @Test
  void quorumAndFetchErrors() {
    ChainPanelModel m = new ChainPanelModel();
    m.failed("connection refused");
    assertThat(m.header()).isEqualTo("chain: connection refused");
    m.update(
        info(
            List.of(
                new Messages.ChainInfo.Validator(0, 50, 0),
                new Messages.ChainInfo.Validator(1, 50, 10),
                new Messages.ChainInfo.Validator(2, 50, 20),
                new Messages.ChainInfo.Validator(3, 10, 5_000))));
    assertThat(m.quorumLine()).isEqualTo("3/4 live");
    m.failed("timeout");
    assertThat(m.header()).contains("height 50").contains("stale: timeout");
  }
}
