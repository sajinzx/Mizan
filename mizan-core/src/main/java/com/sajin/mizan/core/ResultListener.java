package com.sajin.mizan.core;

/** Receives the provisional result of each command executed on the speculative core. */
public interface ResultListener {
  void onResult(SignedCommand c, ExecResult r);
}
