package com.sajin.mizan.ui;

import java.util.Locale;

/** Display formatting. Prices are integer ticks shown with two decimals (100 ticks = 1.00). */
public final class Fmt {
  public static final int PRICE_SCALE = 100;

  private Fmt() {}

  public static String price(long ticks) {
    if (ticks < 0) {
      return "—";
    }
    return String.format(Locale.ROOT, "%,d.%02d", ticks / PRICE_SCALE, ticks % PRICE_SCALE);
  }

  /** Parses a displayed price ("250.25", "250") back to ticks; -1 if not a price. */
  public static long ticks(String text) {
    try {
      double v = Double.parseDouble(text.trim().replace(",", ""));
      if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v)) {
        return -1;
      }
      return Math.round(v * PRICE_SCALE);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  public static String qty(long q) {
    return String.format(Locale.ROOT, "%,d", q);
  }

  /** 950, 12.3K, 4.56M, 7.89B. */
  public static String compact(double v) {
    double a = Math.abs(v);
    if (a >= 1e9) {
      return String.format(Locale.ROOT, "%.2fB", v / 1e9);
    }
    if (a >= 1e6) {
      return String.format(Locale.ROOT, "%.2fM", v / 1e6);
    }
    if (a >= 1e4) {
      return String.format(Locale.ROOT, "%.1fK", v / 1e3);
    }
    return String.format(Locale.ROOT, "%,.0f", v);
  }

  public static String rate(double perSec) {
    return perSec >= 100
        ? String.format(Locale.ROOT, "%,.0f/s", perSec)
        : String.format(Locale.ROOT, "%.1f/s", perSec);
  }

  /** Microseconds as "850 µs", "12.3 ms", "1.20 s". */
  public static String micros(long us) {
    if (us <= 0) {
      return "—";
    }
    if (us < 1_000) {
      return us + " µs";
    }
    if (us < 1_000_000) {
      return String.format(Locale.ROOT, "%.1f ms", us / 1_000.0);
    }
    return String.format(Locale.ROOT, "%.2f s", us / 1e6);
  }

  public static String millis(long ms) {
    return ms < 0
        ? "—"
        : ms < 1_000 ? ms + " ms" : String.format(Locale.ROOT, "%.2f s", ms / 1_000.0);
  }

  /** Signed percentage change from {@code from} to {@code to}. */
  public static String change(long from, long to) {
    if (from <= 0 || to < 0) {
      return "—";
    }
    double pct = (to - from) * 100.0 / from;
    return String.format(Locale.ROOT, "%+.2f%%", pct);
  }

  /** Local wall-clock time "HH:mm:ss". */
  public static String clock(long epochMillis) {
    java.time.LocalTime t =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime();
    return String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
  }
}
