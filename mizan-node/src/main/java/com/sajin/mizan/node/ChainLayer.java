package com.sajin.mizan.node;

import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import java.util.function.Consumer;

/** What the node needs from a chain layer: a draft sink with a lifecycle. */
interface ChainLayer extends Consumer<Sequencer.BlockDraft>, AutoCloseable {
  void start(Sequencer sequencer);

  long height();

  /** What {@code CHAIN_INFO} reports (any thread). */
  com.sajin.mizan.net.Messages.ChainInfo chainInfo();

  /** The committed core; only safe to read after {@link #close()}. */
  ExecutionCore committedCore();

  @Override
  void close();
}
