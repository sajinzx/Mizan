package com.sajin.mizan.engine;

/** Receives the keys of state that changed (state root v2, M10-T2). */
interface StateDirt {
  void ledger(long key);

  void meta(long account);

  void order(long orderId);

  StateDirt NONE =
      new StateDirt() {
        @Override
        public void ledger(long key) {}

        @Override
        public void meta(long account) {}

        @Override
        public void order(long orderId) {}
      };
}
