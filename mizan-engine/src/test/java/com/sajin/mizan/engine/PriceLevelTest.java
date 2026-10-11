package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class PriceLevelTest {

  static LimitOrder order(long id, long qty) {
    return new LimitOrder(id, 2, 1, Side.SELL, TimeInForce.GTC, 100, qty);
  }

  @Test
  void fifoAndTotals() {
    PriceLevel l = new PriceLevel(100);
    assertThat(l.isEmpty()).isTrue();
    assertThat(l.peekFirst()).isNull();
    LimitOrder a = order(1, 5);
    LimitOrder b = order(2, 7);
    l.add(a);
    l.add(b);
    assertThat(l.total()).isEqualTo(12);
    assertThat(l.size()).isEqualTo(2);
    assertThat(l.peekFirst()).isSameAs(a);
    a.fill(2);
    l.reduce(2);
    assertThat(l.total()).isEqualTo(10);
    assertThat(l.removeFirst()).isSameAs(a);
    assertThat(l.total()).isEqualTo(7);
    assertThat(l.remove(a)).isFalse();
    assertThat(l.remove(b)).isTrue();
    assertThat(l.isEmpty()).isTrue();
    assertThat(l.total()).isZero();
    assertThat(l.price()).isEqualTo(100);
  }

  @Test
  void rejectsWrongPriceAndNegativeTotal() {
    PriceLevel l = new PriceLevel(101);
    assertThatThrownBy(() -> l.add(order(1, 1))).isInstanceOf(InvariantViolationError.class);
    assertThatThrownBy(() -> l.reduce(1)).isInstanceOf(InvariantViolationError.class);
  }

  /**
   * Random add/fill/remove operations keep {@code total == sum(remaining)} and FIFO order, checked
   * against a plain list model.
   */
  @Property(tries = 300)
  void invariantsUnderRandomOps(
      @ForAll @Size(max = 80) List<@IntRange(min = 0, max = 99) Integer> ops) {
    PriceLevel l = new PriceLevel(100);
    List<LimitOrder> model = new ArrayList<>();
    long nextId = 1;
    for (int op : ops) {
      if (op < 45 || model.isEmpty()) {
        LimitOrder o = order(nextId++, 1 + op % 20);
        l.add(o);
        model.add(o);
      } else if (op < 70) {
        LimitOrder first = model.get(0);
        long q = 1 + op % first.remaining();
        first.fill(q);
        l.reduce(q);
        if (first.remaining() == 0) {
          assertThat(l.removeFirst()).isSameAs(first);
          model.remove(0);
        }
      } else if (op < 85) {
        assertThat(l.removeFirst()).isSameAs(model.remove(0));
      } else {
        LimitOrder victim = model.remove(op % model.size());
        assertThat(l.remove(victim)).isTrue();
      }
      assertThat(l.total()).isEqualTo(model.stream().mapToLong(Order::remaining).sum());
      List<Order> fifo = new ArrayList<>();
      l.orders().forEach(fifo::add);
      assertThat(fifo).containsExactlyElementsOf(model);
      assertThat(l.isEmpty()).isEqualTo(model.isEmpty());
    }
  }
}
