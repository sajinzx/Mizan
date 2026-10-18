package com.sajin.mizan.ui;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.node.SandboxView;
import java.util.List;
import java.util.function.LongConsumer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** Canvas-drawn widgets: fast to redraw ten times a second, no node churn. */
@SuppressWarnings("LongDoubleConversion") // pixel coordinates: precision is irrelevant
final class Canvases {
  static final Color BG = Color.web("#111723");
  static final Color GRID = Color.web("#1c2536");
  static final Color AXIS = Color.web("#2a364c");
  static final Color TEXT = Color.web("#dbe2ee");
  static final Color MUTED = Color.web("#7d8aa3");
  static final Color GREEN = Color.web("#19c37d");
  static final Color RED = Color.web("#f0465a");
  static final Color BLUE = Color.web("#4c8dff");
  static final Color AMBER = Color.web("#f5a524");
  static final Color[] VALIDATOR = {
    Color.web("#4c8dff"),
    Color.web("#a172ff"),
    Color.web("#22c3b0"),
    Color.web("#f5a524"),
    Color.web("#ff7ab6"),
    Color.web("#9bd14b")
  };
  static final Font MONO = Font.font("Consolas", 11);
  static final Font MONO_B = Font.font("Consolas", FontWeight.BOLD, 12);
  static final Font SMALL = Font.font("Segoe UI", 10);

  private Canvases() {}

  static Color validatorColor(int i) {
    return i < 0 ? MUTED : VALIDATOR[i % VALIDATOR.length];
  }

  /** A canvas that fills its pane and redraws on resize. */
  abstract static class Surface extends Pane {
    final Canvas canvas = new Canvas();

    Surface() {
      getChildren().add(canvas);
      widthProperty().addListener((o, a, b) -> redraw());
      heightProperty().addListener((o, a, b) -> redraw());
      setMinSize(80, 60);
    }

    @Override
    protected void layoutChildren() {
      canvas.setWidth(getWidth());
      canvas.setHeight(getHeight());
    }

    final void redraw() {
      double w = getWidth();
      double h = getHeight();
      if (w < 2 || h < 2) {
        return;
      }
      GraphicsContext g = canvas.getGraphicsContext2D();
      g.setFill(BG);
      g.fillRect(0, 0, w, h);
      draw(g, w, h);
    }

    abstract void draw(GraphicsContext g, double w, double h);
  }

  // ------------------------------------------------------------------ order book ladder

  /** Asks above, bids below, depth bars by cumulative size, the spread in between. */
  static final class Ladder extends Surface {
    private SandboxView.Instrument ins;
    private List<SandboxView.Trade> trades = List.of();
    private LongConsumer onPick = p -> {};
    private double rowH = 17;
    private long[] rowPrices = new long[0];

    Ladder() {
      setOnMouseClicked(
          e -> {
            int row = (int) (e.getY() / rowH);
            if (row >= 0 && row < rowPrices.length && rowPrices[row] > 0) {
              onPick.accept(rowPrices[row]);
            }
          });
      setStyle("-fx-cursor: hand;");
    }

    void onPick(LongConsumer c) {
      onPick = c;
    }

    void show(SandboxView.Instrument i, List<SandboxView.Trade> t) {
      ins = i;
      trades = t;
      redraw();
    }

    @Override
    void draw(GraphicsContext g, double w, double h) {
      if (ins == null) {
        return;
      }
      int rows = Math.max(5, (int) (h / rowH));
      int perSide = (rows - 1) / 2;
      rowPrices = new long[rows];
      List<SandboxView.Level> asks = ins.asks();
      List<SandboxView.Level> bids = ins.bids();
      long maxCum = 1;
      for (int k = 0; k < Math.min(perSide, asks.size()); k++) {
        maxCum = Math.max(maxCum, asks.get(k).cumulative());
      }
      for (int k = 0; k < Math.min(perSide, bids.size()); k++) {
        maxCum = Math.max(maxCum, bids.get(k).cumulative());
      }
      long lastPrice = ins.last();
      g.setTextBaseline(VPos.CENTER);
      g.setFont(MONO);
      double priceX = w * 0.30;
      double qtyX = w * 0.62;
      double cumX = w - 6;
      // asks: best ask just above the middle row
      for (int k = 0; k < perSide; k++) {
        int row = perSide - 1 - k;
        double y = row * rowH;
        if (k >= asks.size()) {
          continue;
        }
        SandboxView.Level l = asks.get(k);
        rowPrices[row] = l.price();
        double bw = (w - 4) * l.cumulative() / maxCum;
        g.setFill(RED.deriveColor(0, 1, 1, 0.12));
        g.fillRect(w - bw, y + 1, bw, rowH - 2);
        g.setFill(RED.deriveColor(0, 1, 1, 0.28));
        g.fillRect(w - (w - 4) * l.qty() / maxCum, y + 1, (w - 4) * l.qty() / maxCum, rowH - 2);
        row(g, y, l, RED, priceX, qtyX, cumX, l.price() == lastPrice);
      }
      // spread row
      double sy = perSide * rowH;
      g.setFill(Color.web("#0d1320"));
      g.fillRect(0, sy, w, rowH);
      g.setTextAlign(TextAlignment.LEFT);
      g.setFont(MONO_B);
      long spread = ins.bestAsk() > 0 && ins.bestBid() > 0 ? ins.bestAsk() - ins.bestBid() : -1;
      boolean up = trades.isEmpty() || trades.get(0).takerSide() == Side.BUY;
      g.setFill(lastPrice < 0 ? MUTED : up ? GREEN : RED);
      g.fillText((up ? "▲ " : "▼ ") + Fmt.price(lastPrice), 6, sy + rowH / 2);
      g.setFont(SMALL);
      g.setFill(MUTED);
      g.setTextAlign(TextAlignment.RIGHT);
      g.fillText(
          "spread " + (spread < 0 ? "—" : Fmt.price(spread)) + "   fair " + Fmt.price(ins.fair()),
          w - 6,
          sy + rowH / 2);
      g.setFont(MONO);
      for (int k = 0; k < perSide; k++) {
        int row = perSide + 1 + k;
        double y = row * rowH;
        if (k >= bids.size() || row >= rows) {
          continue;
        }
        SandboxView.Level l = bids.get(k);
        rowPrices[row] = l.price();
        double bw = (w - 4) * l.cumulative() / maxCum;
        g.setFill(GREEN.deriveColor(0, 1, 1, 0.12));
        g.fillRect(w - bw, y + 1, bw, rowH - 2);
        g.setFill(GREEN.deriveColor(0, 1, 1, 0.28));
        g.fillRect(w - (w - 4) * l.qty() / maxCum, y + 1, (w - 4) * l.qty() / maxCum, rowH - 2);
        row(g, y, l, GREEN, priceX, qtyX, cumX, l.price() == lastPrice);
      }
    }

    private void row(
        GraphicsContext g,
        double y,
        SandboxView.Level l,
        Color c,
        double priceX,
        double qtyX,
        double cumX,
        boolean last) {
      double cy = y + rowH / 2;
      g.setTextAlign(TextAlignment.RIGHT);
      g.setFill(c);
      g.setFont(last ? MONO_B : MONO);
      g.fillText(Fmt.price(l.price()), priceX, cy);
      g.setFont(MONO);
      g.setFill(TEXT);
      g.fillText(Fmt.qty(l.qty()), qtyX, cy);
      g.setFill(MUTED);
      g.fillText(Fmt.qty(l.cumulative()), cumX, cy);
    }
  }

  // ------------------------------------------------------------------ depth chart

  static final class Depth extends Surface {
    private SandboxView.Instrument ins;

    void show(SandboxView.Instrument i) {
      ins = i;
      redraw();
    }

    @Override
    void draw(GraphicsContext g, double w, double h) {
      if (ins == null || (ins.bids().isEmpty() && ins.asks().isEmpty())) {
        empty(g, w, h, "no resting liquidity yet");
        return;
      }
      List<SandboxView.Level> bids = ins.bids();
      List<SandboxView.Level> asks = ins.asks();
      long lo = bids.isEmpty() ? asks.get(0).price() : bids.get(bids.size() - 1).price();
      long hi = asks.isEmpty() ? bids.get(0).price() : asks.get(asks.size() - 1).price();
      long maxCum =
          Math.max(
              bids.isEmpty() ? 1 : bids.get(bids.size() - 1).cumulative(),
              asks.isEmpty() ? 1 : asks.get(asks.size() - 1).cumulative());
      double pad = 18;
      double span = Math.max(1, hi - lo);
      java.util.function.DoubleUnaryOperator x = p -> 4 + (p - lo) / span * (w - 8);
      java.util.function.DoubleUnaryOperator y = q -> h - pad - q / maxCum * (h - pad - 6);
      // bids: step area from best bid leftwards
      area(g, bids, x, y, h - pad, GREEN, true);
      area(g, asks, x, y, h - pad, RED, false);
      g.setStroke(AXIS);
      g.strokeLine(0, h - pad, w, h - pad);
      g.setFill(MUTED);
      g.setFont(SMALL);
      g.setTextBaseline(VPos.TOP);
      g.setTextAlign(TextAlignment.LEFT);
      g.fillText(Fmt.price(lo), 4, h - pad + 3);
      g.setTextAlign(TextAlignment.RIGHT);
      g.fillText(Fmt.price(hi), w - 4, h - pad + 3);
      g.setTextAlign(TextAlignment.CENTER);
      if (ins.last() > 0) {
        g.setFill(TEXT);
        g.fillText("last " + Fmt.price(ins.last()), w / 2, h - pad + 3);
      }
      g.setTextAlign(TextAlignment.LEFT);
      g.setFill(MUTED);
      g.fillText("depth · cumulative lots, " + Fmt.qty(maxCum) + " max", 6, 4);
    }

    private static void area(
        GraphicsContext g,
        List<SandboxView.Level> ls,
        java.util.function.DoubleUnaryOperator x,
        java.util.function.DoubleUnaryOperator y,
        double base,
        Color c,
        boolean bids) {
      if (ls.isEmpty()) {
        return;
      }
      g.beginPath();
      double startX = x.applyAsDouble(ls.get(0).price());
      g.moveTo(startX, base);
      double prevY = base;
      for (SandboxView.Level l : ls) {
        double px = x.applyAsDouble(l.price());
        g.lineTo(px, prevY);
        prevY = y.applyAsDouble(l.cumulative());
        g.lineTo(px, prevY);
      }
      double endX = bids ? 4 : x.applyAsDouble(ls.get(ls.size() - 1).price()) + 2;
      g.lineTo(endX, prevY);
      g.lineTo(endX, base);
      g.closePath();
      g.setFill(c.deriveColor(0, 1, 1, 0.18));
      g.fill();
      g.setStroke(c);
      g.setLineWidth(1.4);
      g.stroke();
    }
  }

  // ------------------------------------------------------------------ candles

  /** OHLC candles with a volume strip and the bots' fair price as a dashed reference. */
  static final class Candles extends Surface {
    private List<SandboxView.Candle> bars = List.of();
    private long fair = -1;
    private String label = "";

    void show(List<SandboxView.Candle> b, long fairPrice, String title) {
      bars = b;
      fair = fairPrice;
      label = title;
      redraw();
    }

    @Override
    void draw(GraphicsContext g, double w, double h) {
      if (bars.isEmpty()) {
        empty(g, w, h, "waiting for the first trade…");
        return;
      }
      double right = 62;
      double volH = h * 0.18;
      double priceBottom = h - volH - 18;
      double top = 20;
      long hi = Long.MIN_VALUE;
      long lo = Long.MAX_VALUE;
      long maxVol = 1;
      for (SandboxView.Candle c : bars) {
        hi = Math.max(hi, c.high());
        lo = Math.min(lo, c.low());
        maxVol = Math.max(maxVol, c.volume());
      }
      if (fair > 0) {
        hi = Math.max(hi, fair);
        lo = Math.min(lo, fair);
      }
      long pad = Math.max(1, (hi - lo) / 10);
      hi += pad;
      lo -= pad;
      double span = Math.max(1, hi - lo);
      long loF = lo;
      java.util.function.DoubleUnaryOperator y =
          p -> top + (1 - (p - loF) / span) * (priceBottom - top);
      // grid + price axis
      g.setFont(SMALL);
      g.setTextBaseline(VPos.CENTER);
      g.setTextAlign(TextAlignment.LEFT);
      for (int k = 0; k <= 5; k++) {
        double p = lo + span * k / 5;
        double gy = y.applyAsDouble(p);
        g.setStroke(GRID);
        g.setLineWidth(1);
        g.strokeLine(0, gy, w - right, gy);
        g.setFill(MUTED);
        g.fillText(Fmt.price(Math.round(p)), w - right + 6, gy);
      }
      int n = bars.size();
      double slot = (w - right - 8) / Math.max(n, 30);
      double body = Math.max(1, slot * 0.62);
      double x0 = w - right - 4 - n * slot;
      for (int k = 0; k < n; k++) {
        SandboxView.Candle c = bars.get(k);
        double cx = x0 + k * slot + slot / 2;
        boolean up = c.close() >= c.open();
        Color col = up ? GREEN : RED;
        g.setStroke(col);
        g.setLineWidth(1);
        g.strokeLine(cx, y.applyAsDouble(c.high()), cx, y.applyAsDouble(c.low()));
        double yo = y.applyAsDouble(c.open());
        double yc = y.applyAsDouble(c.close());
        g.setFill(col);
        g.fillRect(cx - body / 2, Math.min(yo, yc), body, Math.max(1, Math.abs(yc - yo)));
        double vh = (volH - 4) * c.volume() / maxVol;
        g.setFill(col.deriveColor(0, 1, 1, 0.35));
        g.fillRect(cx - body / 2, h - 16 - vh, body, vh);
      }
      if (fair > 0) {
        double fy = y.applyAsDouble(fair);
        g.setStroke(AMBER.deriveColor(0, 1, 1, 0.7));
        g.setLineDashes(5, 5);
        g.strokeLine(0, fy, w - right, fy);
        g.setLineDashes();
        g.setFill(AMBER);
        g.fillText("fair " + Fmt.price(fair), w - right + 6, fy - 11);
      }
      SandboxView.Candle last = bars.get(n - 1);
      double ly = y.applyAsDouble(last.close());
      g.setFill(last.close() >= last.open() ? GREEN : RED);
      g.fillRect(w - right + 2, ly - 8, right - 4, 16);
      g.setFill(Color.WHITE);
      g.setFont(MONO_B);
      g.fillText(Fmt.price(last.close()), w - right + 6, ly);
      g.setFont(SMALL);
      g.setFill(MUTED);
      g.setTextBaseline(VPos.TOP);
      g.fillText(label, 6, 4);
      g.fillText(Fmt.clock(bars.get(0).startMillis()), 6, h - 14);
      g.setTextAlign(TextAlignment.RIGHT);
      g.fillText(Fmt.clock(last.startMillis()), w - right - 4, h - 14);
    }
  }

  // ------------------------------------------------------------------ recent blocks

  /**
   * One bar per block, newest right: height = commands, colour = proposer, amber dot = round > 0.
   */
  static final class Blocks extends Surface {
    private List<SandboxView.Block> blocks = List.of();

    void show(List<SandboxView.Block> b) {
      blocks = b;
      redraw();
    }

    @Override
    void draw(GraphicsContext g, double w, double h) {
      if (blocks.isEmpty()) {
        empty(g, w, h, "no blocks yet");
        return;
      }
      int n = Math.min(blocks.size(), Math.max(10, (int) (w / 7)));
      int max = 1;
      for (int k = 0; k < n; k++) {
        max = Math.max(max, blocks.get(k).commands());
      }
      double slot = w / n;
      double base = h - 14;
      for (int k = 0; k < n; k++) {
        SandboxView.Block b = blocks.get(k);
        double x = w - (k + 1) * slot;
        double bh = Math.max(2, (base - 16) * b.commands() / max);
        g.setFill(
            validatorColor(b.proposer()).deriveColor(0, 1, 1, b.commands() == 0 ? 0.35 : 0.9));
        g.fillRect(x + 1, base - bh, Math.max(1, slot - 2), bh);
        if (b.round() > 0) {
          g.setFill(AMBER);
          g.fillOval(x + slot / 2 - 2.5, base - bh - 7, 5, 5);
        }
      }
      g.setFont(SMALL);
      g.setFill(MUTED);
      g.setTextBaseline(VPos.TOP);
      g.setTextAlign(TextAlignment.LEFT);
      g.fillText("blocks · bar = commands, colour = proposer, ● = round > 0", 4, 2);
      g.setTextAlign(TextAlignment.RIGHT);
      g.fillText("h " + blocks.get(0).height(), w - 4, base + 1);
      g.setTextAlign(TextAlignment.LEFT);
      g.fillText("h " + blocks.get(n - 1).height(), 4, base + 1);
    }
  }

  // ------------------------------------------------------------------ peer matrix

  /** Row i, column j: how long ago validator i last heard validator j. */
  static final class Matrix extends Surface {
    private List<SandboxView.Validator> vs = List.of();

    void show(List<SandboxView.Validator> v) {
      vs = v;
      redraw();
    }

    @Override
    void draw(GraphicsContext g, double w, double h) {
      int n = vs.size();
      if (n == 0) {
        return;
      }
      double lab = 26;
      double cell = Math.min((w - lab - 4) / n, (h - lab - 4) / n);
      g.setFont(SMALL);
      g.setTextBaseline(VPos.CENTER);
      g.setTextAlign(TextAlignment.CENTER);
      for (int j = 0; j < n; j++) {
        g.setFill(validatorColor(j));
        g.fillText("v" + j, lab + j * cell + cell / 2, lab / 2);
      }
      for (int i = 0; i < n; i++) {
        SandboxView.Validator v = vs.get(i);
        g.setFill(validatorColor(i));
        g.fillText("v" + i, lab / 2, lab + i * cell + cell / 2);
        for (int j = 0; j < n; j++) {
          double x = lab + j * cell + 1;
          double y = lab + i * cell + 1;
          Color c;
          String t;
          if (!v.up()) {
            c = Color.web("#3a1520");
            t = "down";
          } else if (i == j) {
            c = Color.web("#1b2231");
            t = "self";
          } else {
            long age = j < v.peerAgeMillis().size() ? v.peerAgeMillis().get(j) : -1;
            c =
                age < 0
                    ? Color.web("#3a1520")
                    : age < 1_500
                        ? Color.web("#0f3326")
                        : age < 4_000 ? Color.web("#3a2a0d") : Color.web("#3a1520");
            t = age < 0 ? "never" : age < 1_000 ? age + "ms" : (age / 1_000) + "s";
          }
          g.setFill(c);
          g.fillRoundRect(x, y, cell - 2, cell - 2, 6, 6);
          g.setFill(TEXT);
          g.fillText(t, x + (cell - 2) / 2, y + (cell - 2) / 2);
        }
      }
    }
  }

  // ------------------------------------------------------------------ sparkline

  static final class Spark extends Surface {
    private double[] values = new double[0];
    private Color color = BLUE;

    Spark(Color c) {
      color = c;
      setMinSize(60, 18);
      setPrefSize(108, 22);
    }

    void show(double[] v) {
      values = v;
      redraw();
    }

    @Override
    void draw(GraphicsContext g, double w, double h) {
      g.setFill(Color.web("#111723"));
      g.fillRect(0, 0, w, h);
      if (values.length < 2) {
        return;
      }
      double max = 1e-9;
      for (double v : values) {
        max = Math.max(max, v);
      }
      g.beginPath();
      for (int k = 0; k < values.length; k++) {
        double x = k * (w - 2) / (values.length - 1) + 1;
        double y = h - 2 - values[k] / max * (h - 4);
        if (k == 0) {
          g.moveTo(x, y);
        } else {
          g.lineTo(x, y);
        }
      }
      g.setStroke(color);
      g.setLineWidth(1.4);
      g.stroke();
    }
  }

  static void empty(GraphicsContext g, double w, double h, String text) {
    g.setFill(MUTED);
    g.setFont(SMALL);
    g.setTextAlign(TextAlignment.CENTER);
    g.setTextBaseline(VPos.CENTER);
    g.fillText(text, w / 2, h / 2);
  }
}
