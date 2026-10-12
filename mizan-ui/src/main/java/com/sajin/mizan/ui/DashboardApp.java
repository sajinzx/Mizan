package com.sajin.mizan.ui;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.net.md.MarketDataClient;
import com.sajin.mizan.net.md.MdCodec;
import com.sajin.mizan.net.md.SnapshotFetcher;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Minimal market-data dashboard (M5-T4): ask and bid ladders, recent trades and a status bar
 * (sequence, gaps, snapshots, REVOKE indicator).
 *
 * <p>UDP is received on the {@link MarketDataClient}'s virtual thread. Updates are coalesced: at
 * most one {@code Platform.runLater} is pending at a time, so a burst of packets never floods or
 * blocks the JavaFX application thread.
 *
 * <p>Parameters: {@code --host=127.0.0.1 --gateway-port=9000 --md-port=9001 --instrument=1}.
 */
public final class DashboardApp extends Application {
  private final ObservableList<String> askRows = FXCollections.observableArrayList();
  private final ObservableList<String> bidRows = FXCollections.observableArrayList();
  private final ObservableList<String> tradeRows = FXCollections.observableArrayList();
  private final Label status = new Label("connecting…");
  private final AtomicBoolean refreshPending = new AtomicBoolean();
  private LadderModel model;
  private MarketDataClient client;

  @Override
  public void start(Stage stage) throws Exception {
    var named = getParameters().getNamed();
    String host = named.getOrDefault("host", "127.0.0.1");
    int gatewayPort = Integer.parseInt(named.getOrDefault("gateway-port", "9000"));
    int mdPort = Integer.parseInt(named.getOrDefault("md-port", "9001"));
    int instrument = Integer.parseInt(named.getOrDefault("instrument", "1"));
    model = new LadderModel(instrument);

    ListView<String> asks = list(askRows, "-fx-text-fill: #b3261e;");
    ListView<String> bids = list(bidRows, "-fx-text-fill: #1b6e3a;");
    ListView<String> trades = list(tradeRows, "");
    VBox ladder = new VBox(4, new Label("Asks"), asks, new Label("Bids"), bids);
    VBox tape = new VBox(4, new Label("Last trades"), trades);
    HBox center = new HBox(12, ladder, tape);
    center.setPadding(new Insets(8));
    BorderPane root = new BorderPane(center);
    status.setPadding(new Insets(4, 8, 4, 8));
    root.setBottom(status);
    stage.setTitle("Mizan market data — instrument " + instrument);
    stage.setScene(new Scene(root, 640, 720));
    stage.show();

    InetAddress addr = InetAddress.getByName(host);
    client =
        new MarketDataClient(
            new InetSocketAddress(addr, mdPort),
            new SnapshotFetcher(new InetSocketAddress(addr, gatewayPort)),
            new MarketDataClient.Listener() {
              @Override
              public void onBook(int instr) {
                scheduleRefresh();
              }

              @Override
              public void onTrade(MdCodec.TradePrint t) {
                Platform.runLater(() -> model.onTrade(t));
                scheduleRefresh();
              }

              @Override
              public void onRevoke(long fromTradeSeq) {
                Platform.runLater(() -> model.onRevoke(fromTradeSeq));
                scheduleRefresh();
              }

              @Override
              public void onGap(long expected, long got) {
                scheduleRefresh();
              }
            });
  }

  private static ListView<String> list(ObservableList<String> items, String style) {
    ListView<String> v = new ListView<>(items);
    v.setStyle("-fx-font-family: monospace; " + style);
    v.setPrefHeight(300);
    v.setPrefWidth(280);
    return v;
  }

  /** Called on the network thread; coalesces into a single pending FX update. */
  private void scheduleRefresh() {
    if (refreshPending.compareAndSet(false, true)) {
      Platform.runLater(this::refresh);
    }
  }

  private void refresh() {
    refreshPending.set(false);
    int i = model.instrument();
    model.setBook(
        client.levels(i, Side.BUY, LadderModel.DEPTH),
        client.levels(i, Side.SELL, LadderModel.DEPTH));
    model.onStatus(client.lastSeq(), client.gapCount(), client.snapshotCount());
    askRows.setAll(model.asks().stream().map(LadderModel.Row::text).toList());
    bidRows.setAll(model.bids().stream().map(LadderModel.Row::text).toList());
    tradeRows.setAll(model.trades());
    long spread = model.spread();
    status.setText(model.status() + (spread >= 0 ? " | spread " + spread : ""));
  }

  @Override
  public void stop() {
    if (client != null) {
      client.close();
    }
  }
}
