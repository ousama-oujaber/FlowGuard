package com.flowguard.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.flowguard", importOptions = ImportOption.DoNotIncludeTests.class)
public class HexagonalArchitectureTest {

    @ArchTest
    public static final ArchRule domainMustNotDependOnFrameworks =
            noClasses()
                    .that().resideInAPackage("com.flowguard.core.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "java.sql..",
                            "com.fasterxml.jackson..",
                            "org.apache.commons..",
                            "org.hibernate.."
                    )
                    .because("The domain layer must remain pure POJOs without any framework coupling");

    @ArchTest
    public static final ArchRule domainMustNotDependOnOuterLayers =
            noClasses()
                    .that().resideInAPackage("com.flowguard.core.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.flowguard.adapters..",
                            "com.flowguard.core.application.."
                    )
                    .because("Domain model must not depend on outer application or adapter layers");

    @ArchTest
    public static final ArchRule applicationMustNotDependOnAdapters =
            noClasses()
                    .that().resideInAPackage("com.flowguard.core.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.flowguard.adapters.."
                    )
                    .because("Application core must only depend on domain and must communicate via ports");

    @ArchTest
    public static final ArchRule adaptersMustNotCrossCommunicate =
            noClasses()
                    .that().resideInAPackage("com.flowguard.adapters.inbound..")
                    .should().dependOnClassesThat().resideInAPackage(
                            "com.flowguard.adapters.outbound.."
                    )
                    .because("Inbound adapters must only invoke application ports, not outbound adapters directly");
}
