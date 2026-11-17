package com.eldros.market.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering inside core (docs/F1-core-plan.md §4). Server-freedom of core itself is enforced by the Maven
 * banned-dependency rule (those classes are not even on the classpath); these rules guard the internal layers.
 */
class ArchitectureTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.eldros.market.core");

    // Break: a domain value type starts reaching into the engine or services (cycles, hidden coupling).
    @Test
    void domainDependsOnNoOtherCorePackage() {
        noClasses().that().resideInAPackage("..core.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..core.engine..", "..core.service..")
                .check(CORE);
    }

    @Test
    void engineDependsOnlyOnDomainApiAndJdk() {
        classes().that().resideInAPackage("..core.engine..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("..core.engine..", "..core.domain..", "..market.api..", "java..")
                .check(CORE);
    }

    @Test
    void serviceDependsOnlyOnDomainApiAndJdk() {
        classes().that().resideInAPackage("..core.service..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("..core.service..", "..core.domain..", "..market.api..", "java..")
                .check(CORE);
    }
}
