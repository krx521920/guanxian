package com.guanxian.platform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureBoundaryTest {
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.guanxian.platform");

    @Test
    void businessModulesDoNotDependOnBootstrap() {
        noClasses()
                .that().resideInAnyPackage(
                        "..iam..", "..member..", "..policy..", "..ai..", "..ecosystem..", "..collaboration..")
                .should().dependOnClassesThat().resideInAnyPackage("..bootstrap..")
                .because("bootstrap composes modules and must never become a business dependency")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void sharedKernelDependsOnlyOnFrameworkAndItsOwnContracts() {
        classes()
                .that().resideInAnyPackage("..shared..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "jakarta..", "org.slf4j..", "org.springframework..",
                        "com.guanxian.platform.shared..")
                .because("the shared kernel must not acquire dependencies on business modules")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void memberPublicApiDoesNotDependOnMemberImplementation() {
        classes()
                .that().resideInAnyPackage("..member.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "com.guanxian.platform.member.api..", "com.guanxian.platform.shared.security..")
                .because("other modules consume member.api without depending on web or internal implementation")
                .check(PRODUCTION_CLASSES);
    }
}
