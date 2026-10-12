package com.sajin.mizan.chain.bft;

/**
 * Point-to-point messaging between validators (M7-T2). Implemented by the simulator ({@code
 * SimNetwork}) and by TCP ({@code TcpNetwork}). Sends are fire-and-forget and may be lost; the
 * protocol retransmits.
 */
public interface Network {
  void send(int to, byte[] message);

  /** Sends to every other validator (not to self). */
  void broadcast(byte[] message);
}
