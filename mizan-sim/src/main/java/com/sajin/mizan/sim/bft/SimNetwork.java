package com.sajin.mizan.sim.bft;

import com.sajin.mizan.chain.bft.Network;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Simulated network (M7-T2): per-message loss, duplication and a uniformly random delay (which
 * reorders), plus partitions (messages between different groups are dropped). Faults can be changed
 * at any time, e.g. to "heal". Deliveries are scheduled for the receiver's current incarnation, so
 * a crash discards messages in flight to it.
 */
public final class SimNetwork {
  private final SimWorld world;
  private final int n;

  private final List<BiConsumer<Integer, byte[]>> handlers;

  private final int[] group;
  private final java.util.Set<Long> cutLinks = new java.util.HashSet<>();
  private double loss;
  private double duplicate;
  private int minDelay = 1;
  private int maxDelay = 5;
  private long sent;
  private long dropped;
  private double mangle;
  private java.util.function.UnaryOperator<byte[]> mangler;
  private long mangled;

  public SimNetwork(SimWorld world, int n) {
    this.world = world;
    this.n = n;
    this.handlers = new ArrayList<>(Collections.nCopies(n, null));
    this.group = new int[n];
  }

  public void setHandler(int node, BiConsumer<Integer, byte[]> handler) {
    handlers.set(node, handler);
  }

  /** Sets loss and duplication probabilities and the delay range in virtual ms. */
  public void setFaults(double loss, double duplicate, int minDelay, int maxDelay) {
    this.loss = loss;
    this.duplicate = duplicate;
    this.minDelay = minDelay;
    this.maxDelay = Math.max(minDelay, maxDelay);
  }

  /** Assigns partition groups (same group ⇒ connected). */
  public void partition(int... groups) {
    System.arraycopy(groups, 0, group, 0, n);
  }

  /** Drops all traffic between {@code a} and {@code b}, both directions. */
  public void cut(int a, int b) {
    cutLinks.add(link(a, b));
  }

  public void restore(int a, int b) {
    cutLinks.remove(link(a, b));
  }

  private static long link(int a, int b) {
    return ((long) Math.min(a, b) << 32) | Math.max(a, b);
  }

  public void heal() {
    cutLinks.clear();
    Arrays.fill(group, 0);
    setFaults(0, 0, 1, 5);
  }

  public long sent() {
    return sent;
  }

  public long dropped() {
    return dropped;
  }

  /**
   * With probability {@code p}, also delivers a {@code mangler}-mutated copy of each message
   * (M9-T5: hostile bytes on the wire next to the genuine traffic). {@code p = 0} turns it off.
   */
  public void setMangler(double p, java.util.function.UnaryOperator<byte[]> mangler) {
    this.mangle = p;
    this.mangler = mangler;
  }

  public long mangled() {
    return mangled;
  }

  /** The endpoint of validator {@code me}. */
  public Network endpoint(int me) {
    return new Network() {
      @Override
      public void send(int to, byte[] message) {
        deliver(me, to, message);
      }

      @Override
      public void broadcast(byte[] message) {
        for (int to = 0; to < n; to++) {
          if (to != me) {
            deliver(me, to, message);
          }
        }
      }
    };
  }

  private void deliver(int from, int to, byte[] message) {
    sent++;
    if (!world.alive(from)
        || group[from] != group[to]
        || cutLinks.contains(link(from, to))
        || world.random().nextDouble() < loss) {
      dropped++;
      return;
    }
    int copies = world.random().nextDouble() < duplicate ? 2 : 1;
    for (int i = 0; i < copies; i++) {
      schedule(from, to, message);
    }
    if (mangle > 0 && world.random().nextDouble() < mangle) {
      mangled++;
      schedule(from, to, mangler.apply(message));
    }
  }

  private void schedule(int from, int to, byte[] message) {
    long delay = (long) minDelay + world.random().nextInt(maxDelay - minDelay + 1);
    world.atNode(
        to,
        world.now() + delay,
        () -> {
          BiConsumer<Integer, byte[]> h = handlers.get(to);
          if (h != null) {
            h.accept(from, message);
          }
        });
  }
}
