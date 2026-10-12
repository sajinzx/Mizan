package com.sajin.mizan.sim.bft;

import com.sajin.mizan.chain.bft.Scheduler;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * Deterministic discrete-event simulator (M7-T2). One event queue ordered by {@code (time, order)},
 * one thread, virtual milliseconds, one seeded {@link SplittableRandom}: the same seed always
 * produces the same run.
 *
 * <p>Each simulated node has an <em>incarnation</em>. Crashing a node bumps it, and every event
 * scheduled for an older incarnation (timers, in-flight deliveries) is silently dropped, just as a
 * process's timers vanish when it dies.
 */
public final class SimWorld {
  private record Event(long time, long order, int node, long incarnation, Runnable task)
      implements Comparable<Event> {
    @Override
    public int compareTo(Event o) {
      int c = Long.compare(time, o.time);
      return c != 0 ? c : Long.compare(order, o.order);
    }
  }

  private final PriorityQueue<Event> queue = new PriorityQueue<>();
  private final SplittableRandom rnd;
  private final long[] incarnation;
  private final boolean[] alive;
  private long now;
  private long order;
  private long processed;

  public SimWorld(long seed, int nodes) {
    this.rnd = new SplittableRandom(seed);
    this.incarnation = new long[nodes];
    this.alive = new boolean[nodes];
    java.util.Arrays.fill(alive, true);
  }

  public long now() {
    return now;
  }

  public SplittableRandom random() {
    return rnd;
  }

  public long processed() {
    return processed;
  }

  public boolean alive(int node) {
    return alive[node];
  }

  public long incarnation(int node) {
    return incarnation[node];
  }

  /** Kills {@code node}: its pending events are dropped. */
  public void crash(int node) {
    alive[node] = false;
    incarnation[node]++;
  }

  /** Marks {@code node} alive again (the caller rebuilds its state). */
  public void revive(int node) {
    alive[node] = true;
    incarnation[node]++;
  }

  /** Schedules a world-level event (not tied to a node). */
  public void at(long time, Runnable task) {
    queue.add(new Event(Math.max(time, now), order++, -1, 0, task));
  }

  /** Schedules {@code task} for the current incarnation of {@code node}. */
  public void atNode(int node, long time, Runnable task) {
    queue.add(new Event(Math.max(time, now), order++, node, incarnation[node], task));
  }

  /** A scheduler bound to {@code node}'s current incarnation. */
  public Scheduler scheduler(int node) {
    long inc = incarnation[node];
    return new Scheduler() {
      @Override
      public long nowMillis() {
        return now;
      }

      @Override
      public Cancellable schedule(long delayMillis, Runnable task) {
        boolean[] cancelled = {false};
        queue.add(
            new Event(
                now + Math.max(0, delayMillis),
                order++,
                node,
                inc,
                () -> {
                  if (!cancelled[0]) {
                    task.run();
                  }
                }));
        return () -> cancelled[0] = true;
      }
    };
  }

  /**
   * Runs events in order until {@code stop} is true, virtual time passes {@code maxTime}, or the
   * queue is empty. Returns whether {@code stop} became true.
   */
  public boolean runUntil(long maxTime, BooleanSupplier stop) {
    while (!queue.isEmpty()) {
      if (stop.getAsBoolean()) {
        return true;
      }
      Event e = queue.peek();
      if (e.time() > maxTime) {
        now = maxTime;
        return stop.getAsBoolean();
      }
      queue.poll();
      now = e.time();
      if (e.node() >= 0 && (!alive[e.node()] || incarnation[e.node()] != e.incarnation())) {
        continue;
      }
      processed++;
      e.task().run();
    }
    return stop.getAsBoolean();
  }
}
