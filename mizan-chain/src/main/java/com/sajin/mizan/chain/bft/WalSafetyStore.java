package com.sajin.mizan.chain.bft;

import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.store.ConsensusWal;
import java.io.UncheckedIOException;

/**
 * {@link SafetyStore} on a file-backed {@link ConsensusWal}. Failures are fatal (unchecked): a
 * validator that cannot persist its safety state must stop rather than vote.
 */
public final class WalSafetyStore implements SafetyStore, AutoCloseable {
  private final ConsensusWal wal;

  public WalSafetyStore(ConsensusWal wal) {
    this.wal = wal;
  }

  @Override
  public SafetyState load() {
    try {
      byte[] b = wal.read();
      return b == null ? null : SafetyState.decode(b);
    } catch (PersistenceException e) {
      throw new UncheckedIOException(e);
    } catch (ProtocolException e) {
      throw new UncheckedIOException(new PersistenceException("consensus WAL does not decode", e));
    }
  }

  @Override
  public void close() throws PersistenceException {
    wal.close();
  }

  @Override
  public void save(SafetyState s) {
    try {
      wal.write(s.encode());
    } catch (PersistenceException e) {
      throw new UncheckedIOException(e);
    }
  }
}
