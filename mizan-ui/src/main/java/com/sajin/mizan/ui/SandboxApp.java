package com.sajin.mizan.ui;

import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.node.Sandbox;
import com.sajin.mizan.node.SandboxView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The Mizan sandbox terminal (ADR 0017): starts a four-validator network in-process with simulated
 * market makers, and shows every part of it live: the book, prices, trades and blocks, your orders
 * going from provisional to final, the validators and their links, and the guarantees (determinism,
 * BFT safety, finality, recovery) with the evidence for each. Every fault can be injected from the
 * screen.
 *
 * <p>A background virtual thread takes a {@link SandboxView.Snapshot} every 100 ms; the JavaFX
 * thread renders the newest one, so the screen never waits on the network.
 */
// ReferenceEquality: "a new snapshot object arrived" is an identity test by design.
@SuppressWarnings({"ReferenceEquality", "LongDoubleConversion"})
public final class SandboxApp extends Application {
  private Sandbox sandbox;
  private volatile SandboxView.Snapshot latest;
  private SandboxView.Snapshot shown;
  private volatile boolean running = true;
  private int instrument = Sandbox.MZN_USD;
  private int timeframe = 5;
  private long lastRender;

  // widgets
  private final Canvases.Ladder ladder = new Canvases.Ladder();
  private final Canvases.Depth depth = new Canvases.Depth();
  private final Canvases.Candles candles = new Canvases.Candles();
  private final Canvases.Blocks blocks = new Canvases.Blocks();
  private final Canvases.Matrix matrix = new Canvases.Matrix();
  private final Canvases.Spark sparkOrders = new Canvases.Spark(Canvases.BLUE);
  private final Canvases.Spark sparkFinal = new Canvases.Spark(Canvases.AMBER);
  private final History histOrders = new History(120);
  private final History histFinal = new History(120);
  private final List<ToggleButton> instrumentButtons = new ArrayList<>();
  private final List<Label[]> cardLabels = new ArrayList<>();
  private final List<Button[]> cardButtons = new ArrayList<>();
  private final Label tHeight = tileValue();
  private final Label tOrders = tileValue();
  private final Label tTrades = tileValue();
  private final Label tFinal = tileValue();
  private final Label tVolume = tileValue();
  private final Label tLive = tileValue();
  private final Label determinismChip = new Label("DETERMINISM …");
  private final Label statsLine = new Label();
  private final Label ticketStatus = new Label("Click a price in the book to fill it in.");
  private final Label balances = new Label();
  private TableView<SandboxView.OpenOrder> openOrdersTable;
  private final Label revokeBanner = new Label();
  private final Label instrumentStats = new Label();
  private final Label verifyResult = new Label("verify-chain: not run yet");
  private final List<Label[]> guarantees = new ArrayList<>();
  private final ObservableList<SandboxView.Trade> tapeRows = FXCollections.observableArrayList();
  private final ObservableList<SandboxView.Event> eventRows = FXCollections.observableArrayList();
  private final ObservableList<SandboxView.MyOrder> myRows = FXCollections.observableArrayList();
  private final ObservableList<SandboxView.OpenOrder> openRows =
      FXCollections.observableArrayList();
  private final ObservableList<SandboxView.Block> blockRows = FXCollections.observableArrayList();

  // ticket
  private final ToggleGroup sideGroup = new ToggleGroup();
  private final ToggleButton buy = new ToggleButton("BUY");
  private final ToggleButton sell = new ToggleButton("SELL");
  private final ComboBox<OrderType> type =
      new ComboBox<>(FXCollections.observableArrayList(OrderType.values()));
  private final ComboBox<TimeInForce> tif =
      new ComboBox<>(FXCollections.observableArrayList(TimeInForce.values()));
  private final ComboBox<String> via = new ComboBox<>();
  private final TextField price = new TextField();
  private final TextField qty = new TextField("10");
  private final Button submit = new Button("BUY MZN");

  @Override
  public void start(Stage stage) {
    StackPane root = new StackPane(splash());
    Scene scene = new Scene(root, 1680, 1000);
    scene.getStylesheets().add(SandboxApp.class.getResource("mizan.css").toExternalForm());
    stage.setTitle("Mizan · Market Sandbox");
    stage.setScene(scene);
    stage.setMaximized(true);
    stage.show();
    Thread.ofPlatform()
        .name("sandbox-start")
        .daemon(true)
        .start(
            () -> {
              try {
                Path dir = Files.createTempDirectory("mizan-sandbox-");
                Sandbox s = Sandbox.start(4, dir);
                sandbox = s;
                Platform.runLater(
                    () -> {
                      root.getChildren().setAll(main());
                      startPolling();
                      scheduleScreenshot(scene);
                    });
              } catch (Exception e) {
                Platform.runLater(() -> root.getChildren().setAll(error(e)));
              }
            });
  }

  /**
   * {@code --screenshot=out.png --screenshot-after=20 [--exit]}: saves the window as a PNG after
   * the given seconds (for docs and for checking the layout without a person at the screen).
   */
  private void scheduleScreenshot(Scene scene) {
    var named = getParameters().getNamed();
    if (getParameters().getUnnamed().contains("--demo")) {
      runDemo();
    }
    String file = named.get("screenshot");
    if (file == null) {
      return;
    }
    long after = Long.parseLong(named.getOrDefault("screenshot-after", "20"));
    boolean exit = getParameters().getUnnamed().contains("--exit");
    background(
        () -> {
          try {
            Thread.sleep(after * 1_000);
          } catch (InterruptedException e) {
            return;
          }
          Platform.runLater(
              () -> {
                try {
                  javafx.scene.image.WritableImage img = scene.snapshot(null);
                  int w = (int) img.getWidth();
                  int h = (int) img.getHeight();
                  java.awt.image.BufferedImage out =
                      new java.awt.image.BufferedImage(
                          w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                  javafx.scene.image.PixelReader pr = img.getPixelReader();
                  for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                      out.setRGB(x, y, pr.getArgb(x, y));
                    }
                  }
                  javax.imageio.ImageIO.write(out, "png", new java.io.File(file));
                } catch (java.io.IOException e) {
                  ticketStatus.setText("screenshot failed: " + e);
                }
                if (exit) {
                  stop();
                }
              });
        });
  }

  /**
   * {@code --demo}: a scripted tour on top of the live market: your buy order at the best ask
   * (watch it go PROVISIONAL then FINAL), then the next proposer is stopped (watch the round bump
   * and the chain carry on with 3 of 4), then restarted (watch it recover and sync).
   */
  private void runDemo() {
    background(
        () -> {
          try {
            Thread.sleep(6_000);
            Platform.runLater(
                () -> {
                  fillBest();
                  qty.setText("25");
                  submitOrder();
                });
            Thread.sleep(3_000);
            int p = sandbox.stopNextProposer();
            Thread.sleep(8_000);
            sandbox.restart(p);
          } catch (Exception e) {
            Platform.runLater(() -> ticketStatus.setText("demo stopped: " + e.getMessage()));
          }
        });
  }

  private Node splash() {
    Label title = new Label("MIZAN");
    title.getStyleClass().add("splash-title");
    Label sub = new Label("Deterministic order book on a Byzantine-fault-tolerant chain");
    sub.getStyleClass().add("splash-step");
    Label steps =
        new Label(
            "Starting 4 validators over localhost TCP · recovering block logs · authenticating peer links\n"
                + "Registering and funding 33 accounts · launching market makers");
    steps.getStyleClass().add("splash-step");
    ProgressIndicator p = new ProgressIndicator();
    p.setPrefSize(46, 46);
    Label chip = chip("SANDBOX · SIMULATED ASSETS ONLY", "chip");
    VBox box = new VBox(14, title, sub, p, steps, chip);
    box.setAlignment(Pos.CENTER);
    box.getStyleClass().add("splash");
    return box;
  }

  private Node error(Exception e) {
    Label l = new Label("The sandbox could not start:\n" + e);
    l.getStyleClass().add("bad");
    VBox box = new VBox(l);
    box.setAlignment(Pos.CENTER);
    return box;
  }

  // ------------------------------------------------------------------ layout

  private Node main() {
    BorderPane bp = new BorderPane();
    bp.setTop(topBar());
    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.setPadding(new Insets(8));
    for (double pct : new double[] {19, 43, 19, 19}) {
      ColumnConstraints c = new ColumnConstraints();
      c.setPercentWidth(pct);
      c.setHgrow(Priority.ALWAYS);
      grid.getColumnConstraints().add(c);
    }
    RowConstraints r0 = new RowConstraints();
    r0.setPercentHeight(60);
    r0.setVgrow(Priority.ALWAYS);
    RowConstraints r1 = new RowConstraints();
    r1.setPercentHeight(40);
    r1.setVgrow(Priority.ALWAYS);
    grid.getRowConstraints().addAll(r0, r1);
    grid.add(bookPanel(), 0, 0);
    grid.add(chartPanel(), 1, 0);
    grid.add(tapePanel(), 2, 0);
    grid.add(ticketPanel(), 3, 0);
    GridPane bottom = new GridPane();
    bottom.setHgap(8);
    for (double pct : new double[] {53, 19, 28}) {
      ColumnConstraints c = new ColumnConstraints();
      c.setPercentWidth(pct);
      c.setHgrow(Priority.ALWAYS);
      bottom.getColumnConstraints().add(c);
    }
    RowConstraints br = new RowConstraints();
    br.setPercentHeight(100);
    br.setVgrow(Priority.ALWAYS);
    bottom.getRowConstraints().add(br);
    bottom.add(validatorsPanel(), 0, 0);
    bottom.add(guaranteesPanel(), 1, 0);
    bottom.add(activityPanel(), 2, 0);
    grid.add(bottom, 0, 1, 4, 1);
    bp.setCenter(grid);
    return bp;
  }

  private Node topBar() {
    Label brand = new Label("MIZAN");
    brand.getStyleClass().add("brand");
    Label sub = new Label("market sandbox · Sajin MK");
    sub.getStyleClass().add("brand-sub");
    VBox b = new VBox(0, brand, sub);
    b.setMinWidth(Region.USE_PREF_SIZE);
    HBox ins = new HBox(6);
    ToggleGroup g = new ToggleGroup();
    for (int id : new int[] {Sandbox.MZN_USD, Sandbox.ORB_USD}) {
      ToggleButton t = new ToggleButton(id == Sandbox.MZN_USD ? "MZN/USD" : "ORB/USD");
      t.getStyleClass().add("instrument-button");
      t.setMinWidth(Region.USE_PREF_SIZE);
      t.setToggleGroup(g);
      t.setUserData(id);
      t.setSelected(id == instrument);
      t.setOnAction(
          e -> {
            if (!t.isSelected()) {
              t.setSelected(true);
            }
            instrument = id;
            submit.setText(submitText());
            shown = null;
          });
      instrumentButtons.add(t);
      ins.getChildren().add(t);
    }
    determinismChip.getStyleClass().addAll("chip", "chip-good");
    determinismChip.setTooltip(
        new Tooltip(
            "A shadow replica re-executes every committed block on its own.\n"
                + "Each validator's reported state root is compared with the shadow's root at that height."));
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar =
        new HBox(
            10,
            b,
            gap(12),
            ins,
            spacer,
            tile("HEIGHT · BLK/S", tHeight, null),
            tile("ORDERS/S", tOrders, sparkOrders),
            tile("TRADES/S", tTrades, null),
            tile("ACK → FINAL p50", tFinal, sparkFinal),
            tile("NOTIONAL", tVolume, null),
            tile("VALIDATORS", tLive, null),
            chipsBox());
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("topbar");
    return bar;
  }

  private Node chipsBox() {
    Label sandboxChip = chip("SANDBOX · SIMULATED ASSETS", "chip");
    sandboxChip.setMinWidth(Region.USE_PREF_SIZE);
    determinismChip.setMinWidth(Region.USE_PREF_SIZE);
    VBox v = new VBox(4, sandboxChip, determinismChip);
    v.setMinWidth(Region.USE_PREF_SIZE);
    return v;
  }

  private Node bookPanel() {
    ladder.onPick(
        p -> {
          price.setText(Fmt.price(p).replace(",", ""));
          type.setValue(OrderType.LIMIT);
          ticketStatus.setText("Price " + Fmt.price(p) + " picked from the book.");
        });
    VBox.setVgrow(ladder, Priority.ALWAYS);
    ladder.setMinHeight(120);
    depth.setMinHeight(70);
    HBox head = new HBox(new Label("PRICE"), grow(), new Label("SIZE"), grow(), new Label("TOTAL"));
    head.getChildren().forEach(n -> n.getStyleClass().add("muted"));
    VBox box = new VBox(4, head, ladder, depth);
    depth.prefHeightProperty().bind(box.heightProperty().multiply(0.27));
    return panel("ORDER BOOK", box);
  }

  private Node chartPanel() {
    HBox tf = new HBox(4);
    ToggleGroup g = new ToggleGroup();
    for (int s : new int[] {1, 5, 15, 60}) {
      ToggleButton t = new ToggleButton(s < 60 ? s + "s" : "1m");
      t.setToggleGroup(g);
      t.setSelected(s == timeframe);
      t.setOnAction(
          e -> {
            if (!t.isSelected()) {
              t.setSelected(true);
            }
            timeframe = s;
            shown = null;
          });
      tf.getChildren().add(t);
    }
    instrumentStats.getStyleClass().add("mono");
    instrumentStats.setMinWidth(0);
    tf.setMinWidth(Region.USE_PREF_SIZE);
    HBox head = new HBox(10, instrumentStats, grow(), tf);
    head.setAlignment(Pos.CENTER_LEFT);
    VBox.setVgrow(candles, Priority.ALWAYS);
    blocks.setPrefHeight(80);
    blocks.setMinHeight(60);
    revokeBanner.getStyleClass().addAll("chip", "chip-warn");
    revokeBanner.setVisible(false);
    revokeBanner.setManaged(false);
    return panel(
        "PRICE · CANDLES, VOLUME, FAIR VALUE", new VBox(6, head, revokeBanner, candles, blocks));
  }

  private Node tapePanel() {
    TableView<SandboxView.Trade> tape = new TableView<>(tapeRows);
    tape.getColumns().add(col("TIME", 58, t -> Fmt.clock(t.timeMillis()), null));
    tape.getColumns()
        .add(
            col(
                "PRICE",
                64,
                t -> Fmt.price(t.price()),
                t -> t.takerSide() == Side.BUY ? "good" : "bad"));
    tape.getColumns().add(col("QTY", 40, t -> Fmt.qty(t.qty()), t -> t.mine() ? "accent" : null));
    tape.getColumns().add(col("SYM", 36, t -> t.symbol().substring(0, 3), null));
    tape.getColumns().add(col("BLOCK", 44, t -> Long.toString(t.height()), null));
    tape.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    tape.setPlaceholder(new Label("no trades yet"));

    ListView<SandboxView.Event> events = new ListView<>(eventRows);
    events.setCellFactory(
        lv ->
            new ListCell<>() {
              @Override
              protected void updateItem(SandboxView.Event e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("good", "bad", "warn", "accent");
                if (empty || e == null) {
                  setText(null);
                  return;
                }
                setText(Fmt.clock(e.timeMillis()) + "  " + e.source() + "  " + e.text());
                setWrapText(true);
                setPrefWidth(0);
                switch (e.severity()) {
                  case GOOD -> getStyleClass().add("good");
                  case WARN -> getStyleClass().add("warn");
                  case CRITICAL -> getStyleClass().add("bad");
                  default -> {}
                }
              }
            });

    TableView<SandboxView.Block> bl = new TableView<>(blockRows);
    bl.getColumns().add(col("HEIGHT", 60, b -> Long.toString(b.height()), null));
    bl.getColumns().add(col("PROP", 44, b -> b.proposer() < 0 ? "?" : "v" + b.proposer(), null));
    bl.getColumns()
        .add(col("RND", 36, b -> Integer.toString(b.round()), b -> b.round() > 0 ? "warn" : null));
    bl.getColumns().add(col("CMDS", 46, b -> Integer.toString(b.commands()), null));
    bl.getColumns().add(col("FILLS", 46, b -> Integer.toString(b.trades()), null));
    bl.getColumns().add(col("STATE ROOT", 120, SandboxView.Block::stateRoot, null));
    bl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

    TabPane tabs =
        new TabPane(new Tab("TRADES", tape), new Tab("BLOCKS", bl), new Tab("EVENTS", events));
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    VBox.setVgrow(tabs, Priority.ALWAYS);
    return panel("MARKET ACTIVITY", new VBox(tabs));
  }

  private Node ticketPanel() {
    buy.setToggleGroup(sideGroup);
    sell.setToggleGroup(sideGroup);
    buy.getStyleClass().add("side-buy");
    sell.getStyleClass().add("side-sell");
    buy.setSelected(true);
    buy.setMaxWidth(Double.MAX_VALUE);
    sell.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(buy, Priority.ALWAYS);
    HBox.setHgrow(sell, Priority.ALWAYS);
    sideGroup
        .selectedToggleProperty()
        .addListener(
            (o, a, b) -> {
              if (b == null) {
                a.setSelected(true);
              }
              submit.getStyleClass().removeAll("buy-button", "sell-button");
              submit.getStyleClass().add(buy.isSelected() ? "buy-button" : "sell-button");
              submit.setText(submitText());
            });
    type.setValue(OrderType.LIMIT);
    tif.setValue(TimeInForce.GTC);
    type.valueProperty()
        .addListener(
            (o, a, b) -> {
              if (b == OrderType.MARKET) {
                tif.setValue(TimeInForce.IOC);
              }
            });
    via.setItems(FXCollections.observableArrayList("auto", "v0", "v1", "v2", "v3"));
    via.setValue("auto");
    price.setPromptText("price, e.g. 250.00");
    qty.setPromptText("lots");
    submit.getStyleClass().add("buy-button");
    submit.setMaxWidth(Double.MAX_VALUE);
    submit.setOnAction(e -> submitOrder());
    HBox quick = new HBox(4);
    for (String q : new String[] {"1", "10", "50", "250"}) {
      Button b = new Button(q);
      b.setOnAction(e -> qty.setText(q));
      quick.getChildren().add(b);
    }
    Button best = new Button("best");
    best.setTooltip(new Tooltip("Fill the price with the opposite best (crosses the spread)"));
    best.setOnAction(e -> fillBest());
    quick.getChildren().add(best);
    GridPane f = new GridPane();
    f.setHgap(6);
    f.setVgap(6);
    f.addRow(0, muted("Type"), type, muted("TIF"), tif);
    f.addRow(1, muted("Price"), price, muted("Lots"), qty);
    f.addRow(2, muted("Route"), via);
    GridPane.setColumnSpan(price, 1);
    ColumnConstraints c0 = new ColumnConstraints(38);
    ColumnConstraints c1 = new ColumnConstraints();
    c1.setHgrow(Priority.ALWAYS);
    ColumnConstraints c2 = new ColumnConstraints(30);
    ColumnConstraints c3 = new ColumnConstraints();
    c3.setHgrow(Priority.ALWAYS);
    f.getColumnConstraints().addAll(c0, c1, c2, c3);
    type.setMaxWidth(Double.MAX_VALUE);
    tif.setMaxWidth(Double.MAX_VALUE);
    via.setMaxWidth(Double.MAX_VALUE);
    ticketStatus.setWrapText(true);
    ticketStatus.getStyleClass().add("muted");
    balances.getStyleClass().add("mono");
    balances.setWrapText(true);

    TableView<SandboxView.OpenOrder> open = new TableView<>(openRows);
    open.getColumns().add(col("ID", 54, o -> Long.toString(o.orderId()), null));
    open.getColumns()
        .add(col("SIDE", 40, o -> o.side().name(), o -> o.side() == Side.BUY ? "good" : "bad"));
    open.getColumns().add(col("PRICE", 70, o -> Fmt.price(o.price()), null));
    open.getColumns().add(col("REM", 46, o -> Fmt.qty(o.remaining()), null));
    TableColumn<SandboxView.OpenOrder, SandboxView.OpenOrder> cancel = new TableColumn<>("");
    cancel.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
    cancel.setCellFactory(
        c ->
            new TableCell<>() {
              private final Button b = new Button("✕");

              {
                b.getStyleClass().add("danger-button");
                b.setStyle("-fx-padding: 0 6 0 6;");
              }

              @Override
              protected void updateItem(SandboxView.OpenOrder o, boolean empty) {
                super.updateItem(o, empty);
                if (empty || o == null) {
                  setGraphic(null);
                  return;
                }
                b.setOnAction(
                    e -> {
                      sandbox.cancel(route(), o.orderId(), o.instrument());
                      ticketStatus.setText("Cancel sent for order " + o.orderId() + ".");
                    });
                setGraphic(b);
              }
            });
    cancel.setPrefWidth(36);
    open.getColumns().add(cancel);
    open.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    open.setPlaceholder(new Label("no resting orders"));

    VBox v =
        new VBox(
            8,
            new HBox(4, buy, sell),
            f,
            quick,
            submit,
            ticketStatus,
            sectionTitle("YOUR BALANCES · account " + Sandbox.YOU),
            balances);
    openOrdersTable = open;
    javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(v);
    sp.setFitToWidth(true);
    VBox.setVgrow(sp, Priority.ALWAYS);
    return panel("ORDER TICKET", new VBox(sp));
  }

  private Node validatorsPanel() {
    GridPane t = new GridPane();
    t.setHgap(8);
    t.setVgap(4);
    String[] heads = {
      "", "", "STATUS", "HEIGHT", "STATE ROOT", "POOL", "BUMPS", "REVOKES", "FINAL p50", "RESTARTS"
    };
    for (int c = 0; c < heads.length; c++) {
      Label h = new Label(heads[c]);
      h.getStyleClass().add("panel-title");
      h.setMinWidth(Region.USE_PREF_SIZE);
      t.add(h, c, 0);
    }
    for (int i = 0; i < 4; i++) {
      Label name = new Label("v" + i);
      name.getStyleClass().add("vcard-name");
      name.setStyle("-fx-text-fill: " + toWeb(Canvases.validatorColor(i)) + ";");
      Label status = chip("…", "chip");
      Label role = chip("PROPOSER", "chip");
      role.setVisible(false);
      HBox st = new HBox(4, status, role);
      st.setAlignment(Pos.CENTER_LEFT);
      Label height = mono();
      Label head = mono();
      Label root = mono();
      Label pool = mono();
      Label bumps = mono();
      Label revokes = mono();
      Label fin = mono();
      Label restarts = mono();
      Button stop = new Button("Stop");
      stop.getStyleClass().add("danger-button");
      stop.setStyle("-fx-padding: 1 7 1 7; -fx-font-size: 11px;");
      stop.setMinWidth(Region.USE_PREF_SIZE);
      Button iso = new Button("Isolate");
      iso.setStyle("-fx-padding: 1 7 1 7; -fx-font-size: 11px;");
      iso.setMinWidth(Region.USE_PREF_SIZE);
      int me = i;
      stop.setOnAction(e -> toggleNode(me));
      iso.setOnAction(e -> toggleIsolate(me));
      HBox act = new HBox(4, stop, iso);
      name.setMinWidth(Region.USE_PREF_SIZE);
      t.addRow(i + 1, name, act, st, height, root, pool, bumps, revokes, fin, restarts);
      cardLabels.add(
          new Label[] {
            status, role, height, head, root, pool, bumps, revokes, fin, restarts, name
          });
      cardButtons.add(new Button[] {stop, iso});
    }
    Button killProposer = new Button("Stop next proposer");
    killProposer.getStyleClass().add("danger-button");
    killProposer.setTooltip(
        new Tooltip(
            "Stops the validator that proposes the next height: watch a round bump, then the next proposer take over"));
    killProposer.setOnAction(
        e ->
            background(
                () -> {
                  int p = sandbox.stopNextProposer();
                  Platform.runLater(
                      () -> ticketStatus.setText("Stopped v" + p + ", the next proposer."));
                }));
    Button split = new Button("Partition 2 | 2");
    split.getStyleClass().add("danger-button");
    split.setTooltip(
        new Tooltip(
            "{v0,v1} | {v2,v3}: neither side has 3 of 4 signatures, so no block commits — and none can fork"));
    split.setOnAction(e -> sandbox.partition(Set.of(0, 1)));
    Button heal = new Button("Heal network");
    heal.getStyleClass().add("good-button");
    heal.setOnAction(e -> sandbox.heal());
    Button verify = new Button("Verify all chains");
    verify.getStyleClass().add("good-button");
    verify.setTooltip(
        new Tooltip(
            "verify-chain: replays every validator's block log from genesis, checking each certificate and state root"));
    verify.setOnAction(
        e -> {
          verifyResult.setText("verify-chain: replaying every block log from genesis…");
          background(
              () -> {
                List<String> r = sandbox.verifyAll();
                boolean ok = r.stream().allMatch(x -> x.contains("OK"));
                Platform.runLater(
                    () -> {
                      verifyResult.setText(
                          (ok ? "verify-chain OK on all 4 · " : "verify-chain FAILED · ")
                              + r.get(0));
                      verifyResult.getStyleClass().removeAll("good", "bad");
                      verifyResult.getStyleClass().add(ok ? "good" : "bad");
                    });
              });
        });
    verifyResult.getStyleClass().add("muted");
    verifyResult.setWrapText(true);
    HBox chaos = new HBox(6, killProposer, split, heal, verify);
    chaos.setAlignment(Pos.CENTER_LEFT);
    VBox left = new VBox(8, t, chaos, verifyResult);
    javafx.scene.control.ScrollPane leftScroll = new javafx.scene.control.ScrollPane(left);
    leftScroll.setFitToWidth(true);
    HBox.setHgrow(leftScroll, Priority.ALWAYS);
    matrix.setPrefSize(150, 140);
    matrix.setMinSize(140, 120);
    VBox net = new VBox(4, sectionTitle("PEER LINKS · last heard"), matrix);
    VBox.setVgrow(matrix, Priority.ALWAYS);
    net.setMinWidth(150);
    net.setPrefWidth(170);
    HBox all = new HBox(10, leftScroll, net);
    return panel("VALIDATORS · 4 nodes, f = 1, quorum 3 · proposer (h + r) mod 4", all);
  }

  private Node guaranteesPanel() {
    String[][] rows = {
      {"Deterministic replicas", "Every validator's state root equals an independent replay"},
      {"BFT safety (f = 1)", "3 of 4 must sign; a 2 | 2 split halts instead of forking"},
      {"Rotating proposer", "Proposer (h + r) mod 4; a dead proposer costs one round"},
      {"Provisional → FINAL", "Fast ack first; FINAL only once fsynced with 2f+1 signatures"},
      {"Crash recovery", "A stopped validator replays its block log and syncs"},
      {"Authenticated peers", "Ed25519 challenge on every validator link"},
      {"Verifiable chain", "verify-chain replays each data directory from genesis"},
      {"State root v2", "Sparse Merkle tree, only changed keys rehashed"},
      {"Conservation + escrow", "Funds held by resting orders; supply never changes in trading"},
    };
    VBox list = new VBox(4);
    for (String[] r : rows) {
      Label dot = new Label("●");
      dot.getStyleClass().add("good");
      Label name = new Label(r[0]);
      name.getStyleClass().add("guarantee-name");
      Label ev = new Label(r[1]);
      ev.getStyleClass().add("guarantee-evidence");
      ev.setWrapText(true);
      VBox text = new VBox(0, name, ev);
      HBox row = new HBox(8, dot, text);
      row.getStyleClass().add("guarantee-row");
      guarantees.add(new Label[] {dot, ev});
      list.getChildren().add(row);
    }
    javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(list);
    sp.setFitToWidth(true);
    VBox.setVgrow(sp, Priority.ALWAYS);
    return panel("GUARANTEES · LIVE EVIDENCE", new VBox(sp));
  }

  private Node activityPanel() {
    TableView<SandboxView.MyOrder> mine = new TableView<>(myRows);
    mine.getColumns().add(col("#", 26, o -> Long.toString(o.nonce()), null));
    mine.getColumns()
        .add(
            col(
                "ORDER",
                104,
                o ->
                    o.kind().equals("CANCEL")
                        ? "CANCEL " + o.orderId()
                        : o.side()
                            + " "
                            + o.qty()
                            + " "
                            + (o.type().equals("MARKET") ? "MKT" : Fmt.price(o.price())),
                o -> o.side() == Side.BUY ? "good" : o.side() == Side.SELL ? "bad" : null));
    mine.getColumns()
        .add(
            col(
                "STATUS",
                78,
                SandboxView.MyOrder::status,
                o ->
                    o.status().equals("FINAL")
                        ? "good"
                        : o.status().contains("REJECT")
                                || o.status().equals("LOST")
                                || o.status().equals("ERROR")
                            ? "bad"
                            : "warn"));
    mine.getColumns().add(col("ACK", 50, o -> Fmt.millis(o.ackMillis()), null));
    mine.getColumns().add(col("FINAL", 50, o -> Fmt.millis(o.finalMillis()), null));
    mine.getColumns()
        .add(col("BLOCK", 46, o -> o.height() > 0 ? Long.toString(o.height()) : "", null));
    mine.getColumns().add(col("VIA", 30, o -> "v" + o.via(), null));
    mine.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    mine.setPlaceholder(
        new Label("Place an order: it shows PROVISIONAL, then FINAL with its block height."));
    mine.setRowFactory(
        tv -> {
          javafx.scene.control.TableRow<SandboxView.MyOrder> row =
              new javafx.scene.control.TableRow<>();
          row.itemProperty()
              .addListener(
                  (o, a, b) ->
                      row.setTooltip(
                          b == null || b.reason().isEmpty() ? null : new Tooltip(b.reason())));
          return row;
        });

    Slider makers = slider(0, 16, 6);
    Slider takers = slider(0, 16, 4);
    Slider rate = slider(0, 1_500, 120);
    Slider vol = slider(0, 60, 8);
    ToggleButton run = new ToggleButton("Running");
    run.setSelected(true);
    Runnable apply =
        () ->
            sandbox.configureBots(
                (int) makers.getValue(),
                (int) takers.getValue(),
                (int) rate.getValue(),
                (int) vol.getValue(),
                run.isSelected());
    for (Slider s : new Slider[] {makers, takers, rate, vol}) {
      s.valueChangingProperty().addListener((o, a, b) -> apply.run());
      s.setOnMouseReleased(e -> apply.run());
    }
    run.setOnAction(
        e -> {
          run.setText(run.isSelected() ? "Running" : "Paused");
          apply.run();
        });
    Label makersL = new Label();
    Label takersL = new Label();
    Label rateL = new Label();
    Label volL = new Label();
    makersL.textProperty().bind(makers.valueProperty().asString("%.0f market makers"));
    takersL.textProperty().bind(takers.valueProperty().asString("%.0f takers"));
    rateL.textProperty().bind(rate.valueProperty().asString("%.0f orders / second"));
    volL.textProperty().bind(vol.valueProperty().asString("%.0f bps volatility per 200 ms"));
    Button burst = new Button("Rush: 1,000/s for 10 s");
    burst.setOnAction(
        e -> {
          rate.setValue(1_000);
          apply.run();
          background(
              () -> {
                try {
                  Thread.sleep(10_000);
                } catch (InterruptedException ex) {
                  return;
                }
                Platform.runLater(
                    () -> {
                      rate.setValue(120);
                      apply.run();
                    });
              });
        });
    statsLine.getStyleClass().add("mono");
    statsLine.setWrapText(true);
    VBox sim =
        new VBox(
            6,
            run,
            makersL,
            makers,
            takersL,
            takers,
            rateL,
            rate,
            volL,
            vol,
            burst,
            sectionTitle("TOTALS"),
            statsLine);
    sim.setPadding(new Insets(6, 2, 2, 2));
    javafx.scene.control.ScrollPane simScroll = new javafx.scene.control.ScrollPane(sim);
    simScroll.setFitToWidth(true);
    TabPane tabs =
        new TabPane(
            new Tab("YOUR ORDERS", mine),
            new Tab("OPEN", openOrdersTable),
            new Tab("MARKET SIM", simScroll));
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    VBox.setVgrow(tabs, Priority.ALWAYS);
    return panel("ACTIVITY", new VBox(tabs));
  }

  // ------------------------------------------------------------------ actions

  private String submitText() {
    String sym = instrument == Sandbox.MZN_USD ? "MZN" : "ORB";
    return (buy.isSelected() ? "BUY " : "SELL ") + sym;
  }

  private int route() {
    String v = via.getValue();
    if (v == null || v.equals("auto")) {
      SandboxView.Snapshot s = latest;
      if (s != null) {
        for (SandboxView.Validator x : s.validators()) {
          if (x.up() && !x.isolated()) {
            return x.index();
          }
        }
      }
      return 0;
    }
    return Integer.parseInt(v.substring(1));
  }

  private void fillBest() {
    SandboxView.Snapshot s = latest;
    if (s == null) {
      return;
    }
    SandboxView.Instrument i = find(s, instrument);
    long p = buy.isSelected() ? i.bestAsk() : i.bestBid();
    if (p > 0) {
      price.setText(Fmt.price(p).replace(",", ""));
    }
  }

  private void submitOrder() {
    long q;
    try {
      q = Long.parseLong(qty.getText().trim().replace(",", ""));
    } catch (NumberFormatException e) {
      ticketStatus.setText("Lots must be a whole number.");
      return;
    }
    OrderType t = type.getValue();
    long p = Fmt.ticks(price.getText());
    if (t == OrderType.MARKET) {
      SandboxView.Instrument i = find(latest, instrument);
      long ref = i.last() > 0 ? i.last() : i.fair();
      p = buy.isSelected() ? ref + ref / 20 : Math.max(1, ref - ref / 20); // 5% collar
    }
    if (q <= 0 || p <= 0) {
      ticketStatus.setText("Enter a price and a positive number of lots.");
      return;
    }
    TimeInForce f = tif.getValue();
    int r = route();
    long nonce = sandbox.place(r, instrument, buy.isSelected() ? Side.BUY : Side.SELL, t, f, p, q);
    ticketStatus.setText(
        "Order #"
            + nonce
            + " sent via v"
            + r
            + ": watch it go PROVISIONAL → FINAL in YOUR ORDERS.");
  }

  private void toggleNode(int i) {
    SandboxView.Snapshot s = latest;
    boolean up = s != null && s.validators().get(i).up();
    background(
        () -> {
          try {
            if (up) {
              sandbox.stop(i);
            } else {
              sandbox.restart(i);
            }
          } catch (Exception e) {
            Platform.runLater(() -> ticketStatus.setText("v" + i + ": " + e.getMessage()));
          }
        });
  }

  private void toggleIsolate(int i) {
    SandboxView.Snapshot s = latest;
    boolean iso = s != null && s.validators().get(i).isolated();
    sandbox.isolate(i, !iso);
  }

  private static void background(Runnable r) {
    Thread.ofVirtual().start(r);
  }

  // ------------------------------------------------------------------ refresh

  private void startPolling() {
    Thread.ofVirtual()
        .name("snapshot")
        .start(
            () -> {
              while (running) {
                try {
                  latest = sandbox.snapshot();
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  return;
                } catch (RuntimeException e) {
                  // a validator mid-restart; next round
                }
              }
            });
    new AnimationTimer() {
      @Override
      public void handle(long now) {
        SandboxView.Snapshot s = latest;
        if (s != null && s != shown && now - lastRender > 90_000_000L) {
          lastRender = now;
          render(s);
          shown = s;
        }
      }
    }.start();
  }

  private void render(SandboxView.Snapshot s) {
    SandboxView.Stats st = s.stats();
    SandboxView.Instrument ins = find(s, instrument);
    histOrders.add(st.commandsPerSec());
    histFinal.add(st.finalP50Micros());
    sparkOrders.show(histOrders.values());
    sparkFinal.show(histFinal.values());
    tHeight.setText(
        Fmt.qty(s.height())
            + " · "
            + String.format(java.util.Locale.ROOT, "%.0f", st.blocksPerSec()));
    tOrders.setText(Fmt.rate(st.commandsPerSec()));
    tTrades.setText(Fmt.rate(st.tradesPerSec()));
    tFinal.setText(Fmt.micros(st.ackP50Micros()) + " → " + Fmt.micros(st.finalP50Micros()));
    long notional = 0;
    for (SandboxView.Instrument i : s.instruments()) {
      notional += i.notional();
    }
    tVolume.setText("$" + Fmt.compact(notional / (double) Fmt.PRICE_SCALE));
    long live = s.validators().stream().filter(v -> v.up() && !v.isolated()).count();
    tLive.setText(live + " / " + s.validators().size() + " live");
    tLive.getStyleClass().removeAll("good", "warn", "bad");
    tLive.getStyleClass().add(live >= 3 ? (live == 4 ? "good" : "warn") : "bad");
    SandboxView.Determinism d = s.determinism();
    determinismChip.setText(
        (d.ok() ? "✓ DETERMINISTIC " : "✗ ROOT MISMATCH ")
            + d.matching()
            + "/"
            + d.compared()
            + " roots = replay");
    determinismChip.getStyleClass().removeAll("chip-good", "chip-bad");
    determinismChip.getStyleClass().add(d.ok() ? "chip-good" : "chip-bad");

    for (ToggleButton b : instrumentButtons) {
      SandboxView.Instrument i = find(s, (int) b.getUserData());
      b.setText(i.symbol() + "   " + Fmt.price(i.last()) + "   " + Fmt.change(i.open(), i.last()));
    }
    instrumentStats.setText(
        ins.symbol()
            + "  last "
            + Fmt.price(ins.last())
            + "  "
            + Fmt.change(ins.open(), ins.last())
            + "   high "
            + Fmt.price(ins.high())
            + "  low "
            + Fmt.price(ins.low())
            + "   vol "
            + Fmt.compact(ins.volume())
            + "  trades "
            + Fmt.qty(ins.trades())
            + "   vwap "
            + (ins.volume() > 0 ? Fmt.price(ins.notional() / ins.volume()) : "—"));
    ladder.show(ins, s.trades());
    depth.show(ins);
    candles.show(
        CandleSeries.aggregate(ins.candles(), timeframe, 120),
        ins.fair(),
        ins.symbol()
            + " · "
            + (timeframe < 60 ? timeframe + "s" : "1m")
            + " candles · dashed = bots' fair value");
    blocks.show(s.blocks());
    matrix.show(s.validators());

    List<SandboxView.Trade> tape = new ArrayList<>();
    for (SandboxView.Trade t : s.trades()) {
      if (tape.size() >= 150) {
        break;
      }
      tape.add(t);
    }
    tapeRows.setAll(tape);
    eventRows.setAll(s.events().size() > 150 ? s.events().subList(0, 150) : s.events());
    blockRows.setAll(s.blocks().size() > 100 ? s.blocks().subList(0, 100) : s.blocks());
    myRows.setAll(s.myOrders());
    openRows.setAll(s.myOpenOrders());
    StringBuilder bal = new StringBuilder();
    for (SandboxView.Balance b : s.myBalances()) {
      long scale = b.name().equals("USD") ? Fmt.PRICE_SCALE : 1;
      bal.append(
          String.format(
              java.util.Locale.ROOT,
              "%-4s free %14s   held %12s%n",
              b.name(),
              scale == 1 ? Fmt.qty(b.free()) : Fmt.price(b.free()),
              scale == 1 ? Fmt.qty(b.held()) : Fmt.price(b.held())));
    }
    balances.setText(bal.toString().trim());

    long revokes = st.revokes();
    revokeBanner.setText(
        "REVOKE × "
            + revokes
            + ": a proposer's block lost; its provisional fills were withdrawn, the committed chain decides");
    boolean showRevoke =
        revokes > 0
            && s.events().stream()
                .anyMatch(
                    e -> e.text().startsWith("REVOKE") && s.nowMillis() - e.timeMillis() < 4_000);
    revokeBanner.setVisible(showRevoke);
    revokeBanner.setManaged(showRevoke);

    for (int i = 0; i < cardLabels.size() && i < s.validators().size(); i++) {
      renderCard(i, s.validators().get(i));
    }
    renderGuarantees(s);
    statsLine.setText(
        String.format(
            java.util.Locale.ROOT,
            "commands %s  accepted %s  rejected %s%ntrades %s  blocks %s  bot orders %s%nbusy %s  REVOKEs %d  round bumps %d%nequivocations %d  blocks synced %d%nstate root v%d · data %s",
            Fmt.qty(st.commands()),
            Fmt.qty(st.accepted()),
            Fmt.qty(st.rejected()),
            Fmt.qty(st.trades()),
            Fmt.qty(st.blocks()),
            Fmt.qty(st.botOrders()),
            Fmt.qty(st.busy()),
            st.revokes(),
            st.roundBumps(),
            st.equivocations(),
            st.syncedBlocks(),
            s.stateRootVersion(),
            s.dataDir()));
  }

  private void renderCard(int i, SandboxView.Validator v) {
    Label[] l = cardLabels.get(i);
    Button[] b = cardButtons.get(i);
    l[0].getStyleClass().removeAll("chip-good", "chip-bad", "chip-warn");
    l[1].setVisible(v.up() && v.nextProposer());
    if (!v.up()) {
      l[0].setText("STOPPED");
      l[0].getStyleClass().add("chip-bad");
      for (int k = 2; k <= 8; k++) {
        l[k].setText(k == 2 ? "on disk" : "");
      }
      l[9].setText(Long.toString(v.restarts()));
      b[0].setText("Restart");
      b[0].getStyleClass().setAll("button", "good-button");
      b[1].setDisable(true);
      return;
    }
    b[0].setText("Stop");
    b[0].getStyleClass().setAll("button", "danger-button");
    b[1].setDisable(false);
    b[1].setText(v.isolated() ? "Reconnect" : "Isolate");
    l[0].setText(v.isolated() ? "ISOLATED" : "LIVE");
    l[0].getStyleClass().add(v.isolated() ? "chip-warn" : "chip-good");
    l[2].setText(Fmt.qty(v.height()));
    l[3].setText(v.headHash());
    l[4].setText(v.stateRoot().substring(0, 10) + (v.rootMatchesShadow() ? " ✓" : " …"));
    l[4].setTooltip(new Tooltip("state root " + v.stateRoot() + "…, head " + v.headHash() + "…"));
    l[4].getStyleClass().removeAll("good");
    if (v.rootMatchesShadow()) {
      l[4].getStyleClass().add("good");
    }
    l[5].setText(Fmt.qty(v.poolDepth()));
    l[6].setText(Long.toString(v.roundBumps()));
    l[7].setText(Long.toString(v.revokes()));
    l[8].setText(Fmt.micros(v.finalP50Micros()));
    l[8].setTooltip(new Tooltip("gateway ingest → FINAL, p99 " + Fmt.micros(v.finalP99Micros())));
    l[9].setText(
        v.restarts() + (v.restarts() > 0 ? " · " + Fmt.millis(v.lastRecoveryMillis()) : ""));
  }

  private void renderGuarantees(SandboxView.Snapshot s) {
    SandboxView.Stats st = s.stats();
    SandboxView.Determinism d = s.determinism();
    long live = s.validators().stream().filter(v -> v.up() && !v.isolated()).count();
    int nv = s.validators().size();
    int next = (int) ((s.height() + 1) % nv);
    boolean nextUp = s.validators().get(next).up();
    long restarts = s.validators().stream().mapToLong(SandboxView.Validator::restarts).sum();
    long lastRec =
        s.validators().stream()
            .filter(v -> v.restarts() > 0)
            .mapToLong(SandboxView.Validator::lastRecoveryMillis)
            .max()
            .orElse(-1);
    long liquid = 0;
    for (SandboxView.Instrument i : s.instruments()) {
      liquid += i.bids().size() + i.asks().size();
    }
    String[] evidence = {
      d.matching()
          + " of "
          + d.compared()
          + " validator roots equal the shadow replay · shadow h "
          + Fmt.qty(d.shadowHeight())
          + " root "
          + d.shadowRoot(),
      live
          + " of "
          + s.validators().size()
          + " reachable · "
          + (live >= 3
              ? "quorum available, blocks commit"
              : "below quorum: the chain waits, it cannot fork")
          + " · equivocations "
          + st.equivocations(),
      "next proposer v"
          + next
          + (nextUp ? "" : " (stopped: its rounds time out, the next proposes)")
          + " · round bumps "
          + st.roundBumps()
          + " · blocks/s "
          + String.format(java.util.Locale.ROOT, "%.1f", st.blocksPerSec()),
      "ack p50 "
          + Fmt.micros(st.ackP50Micros())
          + " → final p50 "
          + Fmt.micros(st.finalP50Micros())
          + " · REVOKEs "
          + st.revokes(),
      "restarts "
          + restarts
          + (lastRec >= 0 ? " · last recovery " + Fmt.millis(lastRec) : "")
          + " · blocks synced "
          + Fmt.qty(st.syncedBlocks()),
      "unauthenticated peers are dropped before any message is read",
      verifyResult.getText(),
      "shadow root " + d.shadowRoot() + " after " + Fmt.qty(st.commands()) + " commands",
      Fmt.qty(liquid)
          + " price levels resting · rejected orders "
          + Fmt.qty(st.rejected())
          + " (insufficient funds, FOK, post-only…)",
    };
    boolean[] ok = {
      d.ok(),
      live >= 3,
      true,
      true,
      true,
      true,
      !verifyResult.getText().contains("FAILED"),
      true,
      true
    };
    for (int k = 0; k < guarantees.size(); k++) {
      Label[] g = guarantees.get(k);
      g[1].setText(evidence[k]);
      g[0].getStyleClass().removeAll("good", "bad", "warn");
      g[0].getStyleClass().add(ok[k] ? "good" : (k == 1 ? "warn" : "bad"));
    }
  }

  // ------------------------------------------------------------------ helpers

  private static SandboxView.Instrument find(SandboxView.Snapshot s, int id) {
    for (SandboxView.Instrument i : s.instruments()) {
      if (i.id() == id) {
        return i;
      }
    }
    return s.instruments().get(0);
  }

  private static <T> TableColumn<T, String> col(
      String title, double width, Function<T, String> value, Function<T, String> style) {
    TableColumn<T, String> c = new TableColumn<>(title);
    c.setCellValueFactory(x -> new ReadOnlyStringWrapper(value.apply(x.getValue())));
    c.setPrefWidth(width);
    c.setSortable(false);
    if (style != null) {
      c.setCellFactory(
          x ->
              new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                  super.updateItem(item, empty);
                  getStyleClass().removeAll("good", "bad", "warn", "accent");
                  setText(empty ? null : item);
                  T row = empty || getTableRow() == null ? null : getTableRow().getItem();
                  if (row != null) {
                    String cls = style.apply(row);
                    if (cls != null) {
                      getStyleClass().add(cls);
                    }
                  }
                }
              });
    }
    return c;
  }

  private static VBox panel(String title, Node content) {
    Label t = new Label(title);
    t.getStyleClass().add("panel-title");
    VBox v = new VBox(6, t, content);
    VBox.setVgrow(content, Priority.ALWAYS);
    v.getStyleClass().add("panel");
    v.setMinSize(0, 0);
    return v;
  }

  private static Label sectionTitle(String s) {
    Label l = new Label(s);
    l.getStyleClass().add("panel-title");
    return l;
  }

  private static Label muted(String s) {
    Label l = new Label(s);
    l.getStyleClass().add("muted");
    return l;
  }

  private static Label chip(String s, String cls) {
    Label l = new Label(s);
    l.getStyleClass().addAll("chip", cls);
    l.setMinWidth(Region.USE_PREF_SIZE);
    return l;
  }

  private static Label mono() {
    Label l = new Label("—");
    l.getStyleClass().add("mono");
    l.setMinWidth(Region.USE_PREF_SIZE);
    return l;
  }

  private static Label tileValue() {
    Label l = new Label("—");
    l.getStyleClass().add("tile-value");
    l.setMinWidth(Region.USE_PREF_SIZE);
    return l;
  }

  private static Node tile(String label, Label value, Node spark) {
    Label l = new Label(label);
    l.getStyleClass().add("tile-label");
    l.setMinWidth(Region.USE_PREF_SIZE);
    VBox v = spark == null ? new VBox(0, l, value) : new VBox(0, l, value, spark);
    v.getStyleClass().add("tile");
    return v;
  }

  private static Region grow() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    VBox.setVgrow(r, Priority.ALWAYS);
    return r;
  }

  private static Region gap(double w) {
    Region r = new Region();
    r.setMinWidth(w);
    return r;
  }

  private static Slider slider(double min, double max, double value) {
    Slider s = new Slider(min, max, value);
    s.setMaxWidth(Double.MAX_VALUE);
    return s;
  }

  private static String toWeb(javafx.scene.paint.Color c) {
    return String.format(
        java.util.Locale.ROOT,
        "#%02x%02x%02x",
        (int) (c.getRed() * 255),
        (int) (c.getGreen() * 255),
        (int) (c.getBlue() * 255));
  }

  @Override
  public void stop() {
    running = false;
    if (sandbox != null) {
      sandbox.close();
    }
    Platform.exit();
    System.exit(0);
  }
}
