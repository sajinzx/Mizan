package com.sajin.mizan.node;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Threat-model claims that are checkable in bytecode (M9-T3), over every production class on the
 * node's classpath (all modules except ui and bench).
 */
class SecurityRulesTest {
  static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("com.sajin.mizan");

  @Test
  void noJavaSerialization() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.io.ObjectInputStream")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.io.ObjectOutputStream")
        .because("all encodings are hand-written canonical byte formats")
        .check(PRODUCTION);
  }

  @Test
  void noProcessExecutionOrReflection() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.ProcessBuilder")
        .orShould()
        .callMethod(Runtime.class, "exec", String.class)
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("java.lang.reflect..")
        .because("nothing decoded from the network may select code to run")
        .check(PRODUCTION);
  }

  @Test
  void productionClassesWereImported() {
    org.assertj.core.api.Assertions.assertThat(PRODUCTION.containPackage("com.sajin.mizan.engine"))
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(PRODUCTION.containPackage("com.sajin.mizan.net"))
        .isTrue();
  }
}
