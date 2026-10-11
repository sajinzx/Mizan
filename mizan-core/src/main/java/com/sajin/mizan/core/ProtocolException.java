package com.sajin.mizan.core;

import java.io.IOException;

/** Malformed bytes from the wire: bad envelope, frame or message encoding. */
public class ProtocolException extends IOException {
  private static final long serialVersionUID = 1L;

  public ProtocolException(String message) {
    super(message);
  }
}
