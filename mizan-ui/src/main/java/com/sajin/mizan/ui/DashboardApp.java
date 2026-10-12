package com.sajin.mizan.ui;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.net.ChainInfoFetcher;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.net.md.MarketDataClient;
import com.sajin.mizan.net.md.MdCodec;
import com.sajin.mizan.net.md.SnapshotFetcher;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Market-data and chain dashboard (M5-T4, M9-T6).
 *
 * <ul>
 *   <li><b>Chain panel</b>: height, head hash, state root, live validators against quorum, and per
 *       validator liveness and lag, polled once a second with {@code CHAIN_INFO}.
 *   <li><b>Instrument selector</b>: one ladder model per instrument; switching is instant because
 *       every model is kept up to date.
 *   <li><b>Ladders, depth chart, trades</b> for the selected instrument.
 *   <li><b>REVOKE banner</b>: lit for 3 s after a {@code REVOKE}, with the count and first revoked
 *       trade.
 * </ul>
 *
 * <p>UDP is received on the {@link MarketDataClient}'s virtual thread. Updates are coalesced: at
 * most one {@code Platform.runLater} is pending at a time, so a burst of packets never floods or
 * blocks the JavaFX application thread.
 *
 * <p>Parameters: {@code --host=127.0.0.1 --gateway-port=9000 --md-port=9001 --instruments=1,2}.
 */
public final class DashboardApp extends Application {
  private final ObservableList<String> askRows = FXCollections.observableArrayList();
  private final ObservableList<String> bidRows = FXCollections.observableArrayList();
  private final ObservableList<String> tradeRows = FXCollections.observableArrayList();
  private final ObservableList<String> validatorRows = FXCollections.observableArrayList();
  private final Label status = new Label("connecting…");
  private final Label chainHeader = new Label("chain: waiting…");
  private final Label quorum = new Label();
  private final Label revokeBanner = new Label();
  private final XYChart.Series<Number, Number> bidSeries = new XYChart.Series<>();
  private final XYChart.Series<Number, Number> askSeries = new XYChart.Series<>();
  private final AtomicBoolean refreshPending = new AtomicBoolean();
  private final Map<Integer, LadderModel> models = new LinkedHashMap<>();
  private final ChainPanelModel chain = new ChainPanelModel();
  private volatile boolean running = true;
  private ComboBox<Integer> selector;
  private MarketDataClient client;

  @Override
  public void start(Stage stage) throws Exception {
    var named = getParameters().getNamed();
    String host = named.getOrDefault("host", "127.0.0.1");
    int gatewayPort = Integer.parseInt(named.getOrDefault("gateway-port", "9000"));
    int mdPort = Integer.parseInt(named.getOrDefault("md-port", "9001"));
    List<Integer> instruments = new ArrayList<>();
    for (String s : named.getOrDefault("instruments", "1,2").split(",", -1)) {
      instruments.add(Integer.parseInt(s.trim()));
    }
    instruments.forEach(i -> models.put(i, new LadderModel(i)));

    selector = new ComboBox<>(FXCollections.observableArrayList(instruments));
    selector.setValue(instruments.get(0));
    selector.setOnAction(e -> refresh());

    ListView<String> asks = list(askRows, "-fx-text-fill: #b3261e;", 280);
    ListView<String> bids = list(bidRows, "-fx-text-fill: #1b6e3a;", 280);
    ListView<String> trades = list(tradeRows, "", 280);
    ListView<String> validators = list(validatorRows, "", 320);
    validators.setPrefHeight(120);

    NumberAxis x = new NumberAxis();
    x.setLabel("price");
    x.setForceZeroInRange(false);
    NumberAxis y = new NumberAxis();
    y.setLabel("cumulative qty");
    AreaChart<Number, Number> depth = new AreaChart<>(x, y);
    depth.setAnimated(false);
    depth.setCreateSymbols(false);
    depth.setPrefHeight(240);
    bidSeries.setName("bids");
    askSeries.setName("asks");
    depth.getData().add(bidSeries);
    depth.getData().add(askSeries);

    revokeBanner.setPadding(new Insets(4, 8, 4, 8));
    revokeBanner.setVisible(false);
    revokeBanner.setStyle(
        "-fx-background-color: #b3261e; -fx-text-fill: white; -fx-font-weight: bold;");

    VBox chainPanel =
        new VBox(
            4,
            new Label("Chain"),
            chainHeader,
            quorum,
            validators,
            new HBox(8, new Label("Instrument"), selector, revokeBanner));
    chainPanel.setPadding(new Insets(8));
    VBox ladder = new VBox(4, new Label("Asks"), asks, new Label("Bids"), bids);
    VBox tape = new VBox(4, new Label("Last trades"), trades);
    HBox center = new HBox(12, ladder, tape);
    center.setPadding(new Insets(8));
    BorderPane root = new BorderPane(new VBox(4, center, depth));
    root.setTop(chainPanel);
    status.setPadding(new Insets(4, 8, 4, 8));
    root.setBottom(status);
    stage.setTitle("Mizan dashboard");
    stage.setScene(new Scene(root, 720, 980));
    stage.show();

    InetAddress addr = InetAddress.getByName(host);
    InetSocketAddress gateway = new InetSocketAddress(addr, gatewayPort);
    client =
        new MarketDataClient(
            new InetSocketAddress(addr, mdPort),
            new SnapshotFetcher(gateway),
            new MarketDataClient.Listener() {
              @Override
              public void onBook(int instr) {
                scheduleRefresh();
              }

              @Override
              public void onTrade(MdCodec.TradePrint t) {
                Platform.runLater(() -> models.values().forEach(m -> m.onTrade(t)));
                scheduleRefresh();
              }

              @Override
              public void onRevoke(long fromTradeSeq) {
                long now = System.currentTimeMillis();
                Platform.runLater(
                    () -> models.values().forEach(m -> m.onRevoke(fromTradeSeq, now)));
                scheduleRefresh();
              }

              @Override
              public void onGap(long expected, long got) {
                scheduleRefresh();
              }
            });
    Thread.ofVirtual().name("chain-info").start(() -> pollChain(new ChainInfoFetcher(gateway)));
  }

  /** Polls {@code CHAIN_INFO} once a second; also drives the refresh that dims the banner. */
  private void pollChain(ChainInfoFetcher fetcher) {
    while (running) {
      try {
        Messages.ChainInfo info = fetcher.fetch();
        Platform.runLater(() -> chain.update(info));
      } catch (IOException e) {
        String why = e.getMessage();
        Platform.runLater(() -> chain.failed(why));
      }
      scheduleRefresh();
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private static ListView<String> list(ObservableList<String> items, String style, int width) {
    ListView<String> v = new ListView<>(items);
    v.setStyle("-fx-font-family: monospace; " + style);
    v.setPrefHeight(260);
    v.setPrefWidth(width);
    return v;
  }

  /** Called on any thread; coalesces into a single pending FX update. */
  private void scheduleRefresh() {
    if (refreshPending.compareAndSet(false, true)) {
      Platform.runLater(this::refresh);
    }
  }

  private void refresh() {
    refreshPending.set(false);
    long now = System.currentTimeMillis();
    for (LadderModel m : models.values()) {
      int i = m.instrument();
      m.setBook(
          client.levels(i, Side.BUY, LadderModel.DEPTH * 2),
          client.levels(i, Side.SELL, LadderModel.DEPTH * 2));
      m.onStatus(client.lastSeq(), client.gapCount(), client.snapshotCount());
    }
    LadderModel model = models.get(selector.getValue());
    askRows.setAll(model.asks().stream().map(LadderModel.Row::text).toList());
    bidRows.setAll(model.bids().stream().map(LadderModel.Row::text).toList());
    tradeRows.setAll(model.trades());
    bidSeries.getData().setAll(points(model.bidCurve()));
    askSeries.getData().setAll(points(model.askCurve()));
    long spread = model.spread();
    status.setText(model.status() + (spread >= 0 ? " | spread " + spread : ""));
    revokeBanner.setVisible(model.revokeFlash(now));
    revokeBanner.setText(
        "REVOKE ×" + model.revokeCount() + " from trade #" + model.lastRevokeFrom());
    chainHeader.setText(chain.header());
    quorum.setText(chain.quorumLine());
    validatorRows.setAll(
        chain.validators().stream().map(ChainPanelModel.ValidatorRow::text).toList());
  }

  private static List<XYChart.Data<Number, Number>> points(List<LadderModel.DepthPoint> curve) {
    List<XYChart.Data<Number, Number>> out = new ArrayList<>(curve.size());
    for (LadderModel.DepthPoint p : curve) {
      out.add(new XYChart.Data<>(p.price(), p.cumulativeQty()));
    }
    return out;
  }

  @Override
  public void stop() {
    running = false;
    if (client != null) {
      client.close();
    }
  }
}
