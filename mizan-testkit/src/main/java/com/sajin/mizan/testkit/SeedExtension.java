package com.sajin.mizan.testkit;

import java.util.SplittableRandom;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Supplies a {@link Seed} parameter to tests. The seed comes from system property {@code
 * mizan.seed} (set by {@code -Pseed=<n>}) or is drawn fresh. On failure the seed and a one-line
 * replay command are printed to stderr and the failure is rethrown as an {@link AssertionError}
 * whose message is that line (original failure as the cause), so it shows up in the Gradle console.
 */
public final class SeedExtension implements ParameterResolver, TestExecutionExceptionHandler {
  private static final ExtensionContext.Namespace NS =
      ExtensionContext.Namespace.create(SeedExtension.class);

  @Override
  public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
    return pc.getParameter().getType() == Seed.class;
  }

  @Override
  public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
    return ec.getStore(NS).getOrComputeIfAbsent("seed", k -> new Seed(initialSeed()), Seed.class);
  }

  @Override
  public void handleTestExecutionException(ExtensionContext ec, Throwable t) throws Throwable {
    Seed seed = ec.getStore(NS).get("seed", Seed.class);
    if (seed != null) {
      String hint = replayHint(seed.value(), ec);
      System.err.println(hint);
      throw new AssertionError(hint, t);
    }
    throw t;
  }

  /** The replay line for a seed, e.g. {@code SEED=42 replay: ./gradlew :m:test --tests ...}. */
  public static String replayHint(long seed, ExtensionContext ec) {
    String task = System.getProperty("mizan.gradleTask", "test");
    String test =
        ec.getRequiredTestClass().getName()
            + ec.getTestMethod().map(m -> "." + m.getName()).orElse("");
    return "SEED=" + seed + " replay: ./gradlew " + task + " --tests '" + test + "' -Pseed=" + seed;
  }

  private static long initialSeed() {
    String fixed = System.getProperty("mizan.seed");
    return fixed != null ? Long.parseLong(fixed.trim()) : new SplittableRandom().nextLong();
  }
}
