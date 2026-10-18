package com.sajin.mizan.ui;

import java.util.Arrays;
import javafx.application.Application;

/**
 * Entry point that is not itself an {@link Application}, so the dashboard also runs from a plain
 * classpath (JavaFX refuses to launch an {@code Application} main class from the classpath).
 *
 * <p>By default it opens the sandbox terminal ({@link SandboxApp}: a whole network in-process).
 * {@code --connect --gateway-port=9000 --md-port=9001} opens the plain dashboard for a node that is
 * already running.
 */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    if (System.getProperty("mizan.log.level") == null) {
      System.setProperty("mizan.log.level", "WARN"); // the terminal shows events; keep stderr quiet
    }
    if (Arrays.asList(args).contains("--connect")) {
      Application.launch(DashboardApp.class, args);
    } else {
      Application.launch(SandboxApp.class, args);
    }
  }
}
