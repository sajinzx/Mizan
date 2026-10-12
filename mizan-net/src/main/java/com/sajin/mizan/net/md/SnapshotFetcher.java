package com.sajin.mizan.net.md;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.net.FrameCodec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Fetches a market-data snapshot from a gateway over TCP ({@code SNAPSHOT_REQ} → {@code SNAPSHOT}).
 */
public final class SnapshotFetcher implements MarketDataClient.SnapshotSource {
  private final InetSocketAddress gateway;

  public SnapshotFetcher(InetSocketAddress gateway) {
    this.gateway = gateway;
  }

  @Override
  public byte[] fetch() throws IOException {
    try (Socket s = new Socket()) {
      s.connect(gateway, 2_000);
      s.setSoTimeout(5_000);
      OutputStream out = s.getOutputStream();
      out.write(FrameCodec.encode(FrameCodec.SNAPSHOT_REQ, new byte[0]));
      out.flush();
      InputStream in = new BufferedInputStream(s.getInputStream());
      while (true) {
        FrameCodec.Frame f = FrameCodec.read(in);
        if (f == null) {
          throw new IOException("gateway closed before sending a snapshot");
        }
        if (f.type() == FrameCodec.SNAPSHOT) {
          return f.payload();
        }
        if (f.type() == FrameCodec.ERR) {
          throw new ProtocolException("gateway refused the snapshot request");
        }
      }
    }
  }
}
