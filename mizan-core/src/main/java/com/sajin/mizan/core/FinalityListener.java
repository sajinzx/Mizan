package com.sajin.mizan.core;

import java.util.List;

/** Receives committed, fsynced blocks: the commands and their final results, index-aligned. */
public interface FinalityListener {
  void onFinal(long height, List<SignedCommand> cmds, List<ExecResult> results);
}
