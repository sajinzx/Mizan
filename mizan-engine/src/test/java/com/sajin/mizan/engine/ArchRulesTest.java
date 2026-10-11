package com.sajin.mizan.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.crypto.Hashing;
import com.sajin.mizan.crypto.SimpleMerkle;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M2-T11: enforces the determinism rules (§2) on the engine package and the Merkle code. The
 * sequencer (M3) owns a thread and a clock, so classes named {@code Sequencer*} are exempt.
 * Bytecode rules use ArchUnit. Rules about operators and primitive types use a source scan ("grep
 * test"), because ArchUnit cannot see arithmetic operators.
 */
class ArchRulesTest {
  static JavaClasses classes;

  static final DescribedPredicate<JavaClass> DETERMINISTIC =
      DescribedPredicate.describe(
          "deterministic engine code",
          c ->
              (c.getPackageName().equals("com.sajin.mizan.engine")
                      && !c.getName().startsWith(Sequencer.class.getName()))
                  || c.isEquivalentTo(SimpleMerkle.class)
                  || c.getName().startsWith(SimpleMerkle.class.getName() + "$")
                  || c.isEquivalentTo(Hashing.class));

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.sajin.mizan.engine", "com.sajin.mizan.crypto");
  }

  @Test
  void noClock() {
    noClasses()
        .that(DETERMINISTIC)
        .should()
        .callMethod(System.class, "currentTimeMillis")
        .orShould()
        .callMethod(System.class, "nanoTime")
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("java.time..")
        .check(classes);
  }

  @Test
  void noRandomness() {
    noClasses()
        .that(DETERMINISTIC)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(
            java.util.Random.class,
            java.security.SecureRandom.class,
            java.util.SplittableRandom.class,
            java.util.concurrent.ThreadLocalRandom.class,
            java.util.UUID.class)
        .orShould()
        .callMethod(Math.class, "random")
        .check(classes);
  }

  @Test
  void noThreadsOrConcurrency() {
    noClasses()
        .that(DETERMINISTIC)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(Thread.class, ThreadLocal.class, Runnable.class)
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("java.util.concurrent..")
        .check(classes);
  }

  @Test
  void noIoOrLogging() {
    noClasses()
        .that(DETERMINISTIC)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "java.io..", "java.nio.file..", "java.net..", "org.slf4j..", "java.util.logging..")
        .orShould()
        .accessField(System.class, "out")
        .orShould()
        .accessField(System.class, "err")
        .check(classes);
  }

  /** Hash-ordered collections may be used for lookups but never iterated. */
  @Test
  void noHashOrderedIteration() {
    Set<String> iterating =
        Set.of(
            "keySet",
            "values",
            "entrySet",
            "forEach",
            "iterator",
            "spliterator",
            "stream",
            "parallelStream",
            "toArray",
            "replaceAll");
    DescribedPredicate<JavaCall<?>> hashIteration =
        DescribedPredicate.describe(
            "iteration over a hash-ordered collection",
            call ->
                iterating.contains(call.getName())
                    && Stream.of(
                            HashMap.class, HashSet.class, LinkedHashMap.class, LinkedHashSet.class)
                        .anyMatch(t -> call.getTargetOwner().isEquivalentTo(t)));
    noClasses().that(DETERMINISTIC).should().callCodeUnitWhere(hashIteration).check(classes);
    noClasses()
        .that(DETERMINISTIC)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(HashSet.class, LinkedHashSet.class)
        .check(classes);
  }

  @Test
  void noFloatingPointFieldsOrSignatures() {
    noFields()
        .that()
        .areDeclaredInClassesThat(DETERMINISTIC)
        .should()
        .haveRawType(double.class)
        .orShould()
        .haveRawType(float.class)
        .orShould()
        .haveRawType(java.math.BigDecimal.class)
        .check(classes);
    noMethods()
        .that()
        .areDeclaredInClassesThat(DETERMINISTIC)
        .should()
        .haveRawReturnType(double.class)
        .orShould()
        .haveRawReturnType(float.class)
        .check(classes);
  }

  // ------------------------------------------------------------------ source scans

  private static final Path ENGINE_SRC = Path.of("src/main/java/com/sajin/mizan/engine");
  private static final Path MERKLE_SRC =
      Path.of("../mizan-crypto/src/main/java/com/sajin/mizan/crypto/SimpleMerkle.java");

  static List<Path> deterministicSources() throws IOException {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(ENGINE_SRC)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().startsWith("Sequencer"))
          .sorted()
          .forEach(out::add);
    }
    out.add(MERKLE_SRC);
    assertThat(out).hasSizeGreaterThan(10);
    return out;
  }

  /** Removes comments, string and char literals, so only code remains. */
  static String codeOnly(String src) {
    String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
    s = s.replaceAll("//[^\n]*", " ");
    s = s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    s = s.replaceAll("'(?:\\\\.|[^'\\\\])'", "' '");
    return s;
  }

  /** Raw {@code *} (or {@code *=}) occurrences in code, i.e. multiplications. */
  static List<String> rawMultiplications(String file, String src) {
    List<String> hits = new ArrayList<>();
    String[] lines = codeOnly(src).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.trim().startsWith("import ")) {
        continue;
      }
      if (line.indexOf('*') >= 0) {
        hits.add(file + ":" + (i + 1) + ": " + line.trim());
      }
    }
    return hits;
  }

  /**
   * Defect 9: all money math must use {@code Math.multiplyExact}. Raw {@code *} is banned in the
   * deterministic sources, money or not, so no overflow can slip through.
   */
  @Test
  void noRawMultiplyOnMoney() throws IOException {
    List<String> hits = new ArrayList<>();
    for (Path p : deterministicSources()) {
      hits.addAll(rawMultiplications(p.toString(), Files.readString(p, StandardCharsets.UTF_8)));
    }
    assertThat(hits).as("raw multiplication; use Math.multiplyExact").isEmpty();
  }

  @Test
  void noFloatingPointInSource() throws IOException {
    Pattern fp = Pattern.compile("\\b(double|float|Double|Float|BigDecimal)\\b");
    List<String> hits = new ArrayList<>();
    for (Path p : deterministicSources()) {
      Matcher m = fp.matcher(codeOnly(Files.readString(p, StandardCharsets.UTF_8)));
      while (m.find()) {
        hits.add(p + ": " + m.group());
      }
    }
    assertThat(hits).isEmpty();
  }

  /** The scanner itself must catch what it is meant to catch. */
  @Test
  void scannerSelfCheck() {
    assertThat(rawMultiplications("X", "long a = b * c;")).hasSize(1);
    assertThat(rawMultiplications("X", "a *= 2;")).hasSize(1);
    assertThat(rawMultiplications("X", "/** a * b */ long x; // y * z\nString s = \"*\";"))
        .isEmpty();
    assertThat(rawMultiplications("X", "import java.util.*;")).isEmpty();
    assertThat(rawMultiplications("X", "long a = Math.multiplyExact(b, c);")).isEmpty();
  }
}
