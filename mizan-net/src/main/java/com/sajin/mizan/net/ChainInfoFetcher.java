package com.sajin.mizan.net;

import com.sajin.mizan.core.ProtocolException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** One-shot {@code CHAIN_INFO_REQ} → {@code CHAIN_INFO} over a short TCP connection (M9-T6). */
public final class ChainInfoFetcher {
  private final InetSocketAddress gateway;

  public ChainInfoFetcher(InetSocketAddress gateway) {
    this.gateway = gateway;
  }

  public Messages.ChainInfo fetch() throws IOException {
    try (Socket s = new Socket()) {
      s.connect(gateway, 2_000);
      s.setSoTimeout(5_000);
      OutputStream out = s.getOutputStream();
      out.write(Messages.chainInfoRequest());
      out.flush();
      InputStream in = new BufferedInputStream(s.getInputStream());
      while (true) {
        FrameCodec.Frame f = FrameCodec.read(in);
        if (f == null) {
          throw new IOException("gateway closed before answering");
        }
        if (f.type() == FrameCodec.CHAIN_INFO) {
          return (Messages.ChainInfo) Messages.decode(f);
        }
        if (f.type() == FrameCodec.ERR) {
          throw new ProtocolException("gateway refused CHAIN_INFO_REQ");
        }
      }
    }
  }
}
