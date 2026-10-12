package com.sajin.mizan.ui;

import javafx.application.Application;

/**
 * Entry point that is not itself an {@link Application}, so the dashboard also runs from a plain
 * classpath (JavaFX refuses to launch an {@code Application} main class from the classpath).
 */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    Application.launch(DashboardApp.class, args);
  }
}
