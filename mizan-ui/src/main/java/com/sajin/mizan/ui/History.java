package com.sajin.mizan.ui;

import java.util.Arrays;

/** A fixed-size ring of samples for sparklines; oldest first when read. */
public final class History {
  private final double[] values;
  private int next;
  private int size;

  public History(int capacity) {
    values = new double[capacity];
  }

  public void add(double v) {
    values[next] = v;
    next = (next + 1) % values.length;
    size = Math.min(size + 1, values.length);
  }

  public double[] values() {
    double[] out = new double[size];
    for (int i = 0; i < size; i++) {
      out[i] = values[(next - size + i + values.length) % values.length];
    }
    return out;
  }

  public double max() {
    return Arrays.stream(values()).max().orElse(0);
  }

  public double last() {
    return size == 0 ? 0 : values[(next - 1 + values.length) % values.length];
  }
}
