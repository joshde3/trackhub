package io.github.joshde3.trackhub.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR 0002: the domain is plain Java. If someone adds a Spring annotation or a JPA entity here,
 * the build fails with a message that says why.
 */
@AnalyzeClasses(packages = "io.github.joshde3.trackhub.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityTest {

    @ArchTest
    static final ArchRule domainUsesNoFrameworks = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax.persistence..",
                    "org.hibernate..",
                    "com.fasterxml.jackson..",
                    "java.net.http..")
            .because("business rules must be testable without a framework (ADR 0002)");
}
