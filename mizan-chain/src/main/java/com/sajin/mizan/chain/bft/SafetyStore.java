package com.sajin.mizan.chain.bft;

/**
 * Durable storage of a validator's {@link SafetyState}. {@link #save} must not return before the
 * state is durable, because the next step is sending a message that depends on it.
 */
public interface SafetyStore {
  /** The last saved state, or {@code null} if none was ever saved. */
  SafetyState load();

  void save(SafetyState s);

  /** Volatile store for simulations, where "durable" means it survives a simulated crash. */
  final class InMemory implements SafetyStore {
    private SafetyState state;
    private long saves;

    @Override
    public SafetyState load() {
      return state;
    }

    @Override
    public void save(SafetyState s) {
      state = s;
      saves++;
    }

    public long saves() {
      return saves;
    }
  }
}
