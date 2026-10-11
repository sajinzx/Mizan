package com.sajin.mizan.net;

import com.sajin.mizan.core.ProtocolException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Minimal blocking test client speaking raw frames. */
final class RawClient implements AutoCloseable {
  final Socket socket;
  final OutputStream out;
  final InputStream in;

  RawClient(int port) throws IOException {
    socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port);
    socket.setSoTimeout(5_000);
    socket.setTcpNoDelay(true);
    out = socket.getOutputStream();
    in = new BufferedInputStream(socket.getInputStream());
  }

  void send(byte[] bytes) throws IOException {
    out.write(bytes);
    out.flush();
  }

  Messages.ServerMessage next() throws IOException {
    FrameCodec.Frame f = FrameCodec.read(in);
    if (f == null) {
      throw new IOException("server closed the connection");
    }
    return Messages.decode(f);
  }

  /** Whether the server closed the connection (possibly after some frames) within the timeout. */
  boolean closedByServer() throws IOException {
    try {
      while (true) {
        if (FrameCodec.read(in) == null) {
          return true;
        }
      }
    } catch (SocketTimeoutException e) {
      return false;
    } catch (ProtocolException e) {
      return false;
    } catch (IOException e) {
      return true; // reset
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
