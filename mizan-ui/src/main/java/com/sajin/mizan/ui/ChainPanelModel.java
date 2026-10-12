package com.sajin.mizan.ui;

import com.sajin.mizan.net.Messages;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Toolkit-free view model of the chain panel (M9-T6): height, head hash, state root and per-
 * validator liveness, from the gateway's {@code CHAIN_INFO}.
 */
public final class ChainPanelModel {
  /** A validator not heard from for longer than this is shown as stale. */
  public static final long LIVE_MS = 2_000;

  public enum Liveness {
    LIVE,
    STALE,
    UNKNOWN
  }

  public record ValidatorRow(int index, Liveness liveness, long height, long lag, long ageMillis) {
    public String text() {
      String h = height < 0 ? "?" : Long.toString(height);
      String age = ageMillis < 0 ? "never" : ageMillis + " ms ago";
      String l = lag > 0 ? " (lag " + lag + ")" : "";
      return String.format("v%-2d %-7s h=%s%s  %s", index, liveness, h, l, age);
    }
  }

  private Messages.ChainInfo info;
  private String error;

  public void update(Messages.ChainInfo i) {
    info = i;
    error = null;
  }

  public void failed(String why) {
    error = why;
  }

  public String header() {
    if (info == null) {
      return error == null ? "chain: waiting…" : "chain: " + error;
    }
    String s =
        "height "
            + info.height()
            + "   head "
            + shortHex(info.headHash())
            + "   root "
            + shortHex(info.stateRoot());
    return error == null ? s : s + "   (stale: " + error + ")";
  }

  public List<ValidatorRow> validators() {
    if (info == null) {
      return List.of();
    }
    List<ValidatorRow> rows = new ArrayList<>();
    for (Messages.ChainInfo.Validator v : info.validators()) {
      Liveness l =
          v.ageMillis() < 0
              ? Liveness.UNKNOWN
              : v.ageMillis() <= LIVE_MS ? Liveness.LIVE : Liveness.STALE;
      long lag = v.height() < 0 ? 0 : Math.max(0, info.height() - v.height());
      rows.add(new ValidatorRow(v.index(), l, v.height(), lag, v.ageMillis()));
    }
    return rows;
  }

  /** Live validators (including the answering node) out of all. */
  public String quorumLine() {
    List<ValidatorRow> rows = validators();
    long live = rows.stream().filter(r -> r.liveness() == Liveness.LIVE).count();
    int n = rows.size();
    int quorum = n == 0 ? 0 : 2 * ((n - 1) / 3) + 1;
    return live + "/" + n + " live" + (n > 0 && live < quorum ? " — BELOW QUORUM" : "");
  }

  static String shortHex(byte[] h) {
    return HexFormat.of().formatHex(h, 0, 6) + "…";
  }
}
