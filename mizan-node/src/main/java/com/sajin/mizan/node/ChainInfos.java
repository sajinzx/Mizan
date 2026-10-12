package com.sajin.mizan.node;

import com.sajin.mizan.chain.ChainHead;
import com.sajin.mizan.chain.bft.BftDriver;
import com.sajin.mizan.net.Messages;
import java.util.ArrayList;
import java.util.List;

/** Builds {@code CHAIN_INFO} answers for the dashboard's chain panel (M9-T6). */
final class ChainInfos {
  private ChainInfos() {}

  /** A node without peers: itself only. */
  static Messages.ChainInfo single(ChainHead h) {
    return new Messages.ChainInfo(
        h.height(),
        h.hash(),
        h.stateRoot(),
        List.of(new Messages.ChainInfo.Validator(0, h.height(), 0)));
  }

  /** Validator {@code me} of {@code n}: peers' liveness as seen by its consensus driver. */
  static Messages.ChainInfo bft(BftDriver d, int me, int n) {
    ChainHead h = d.head();
    long now = System.currentTimeMillis();
    List<Messages.ChainInfo.Validator> vs = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      if (i == me) {
        vs.add(new Messages.ChainInfo.Validator(i, h.height(), 0));
      } else {
        long heard = d.lastHeardMillis(i);
        vs.add(
            new Messages.ChainInfo.Validator(
                i, d.reportedHeight(i), heard < 0 ? -1 : Math.max(0, now - heard)));
      }
    }
    return new Messages.ChainInfo(h.height(), h.hash(), h.stateRoot(), vs);
  }
}
