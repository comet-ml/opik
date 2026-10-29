package com.comet.opik.domain.token_usage;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps token usage normalization an isolated, pure utility: it reads nothing from the rest of the backend, so it can be
 * tested and reasoned about on its own, and its formats stay behind {@link SpanUsageFactory}.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class TokenUsageArchTest {

    private static final String ROOT = "com.comet.opik.domain.token_usage";
    private static final String MODEL = "com.comet.opik.domain.token_usage.model..";
    private static final String FORMAT = "com.comet.opik.domain.token_usage.format..";

    @ArchTest
    static final ArchRule token_usage_depends_only_on_itself_and_the_jdk = classes()
            .that().resideInAPackage(ROOT + "..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(ROOT + "..", "java..", "lombok..",
                    "jakarta.annotation..", "org.apache.commons.lang3..")
            .because("""
                    usage normalization must stay a pure function of its input: no services, DAOs, configuration or
                    metrics, so every behavior is covered by plain unit tests
                    """);

    @ArchTest
    static final ArchRule formats_are_reached_only_through_the_factory = noClasses()
            .that().resideOutsideOfPackages(ROOT, FORMAT)
            .should().dependOnClassesThat().resideInAPackage(FORMAT)
            .because("""
                    callers build a SpanUsage with SpanUsageFactory; the formats and their input variants are an
                    implementation detail
                    """)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule model_and_formats_do_not_depend_on_the_factory = noClasses()
            .that().resideInAnyPackage(MODEL, FORMAT)
            .should().dependOnClassesThat().resideInAPackage(ROOT)
            .because("the factory composes formats into the model, so the dependency only goes one way");

    @ArchTest
    static final ArchRule model_does_not_depend_on_formats = noClasses()
            .that().resideInAPackage(MODEL)
            .should().dependOnClassesThat().resideInAPackage(FORMAT)
            .because("the model is what callers read, and must not expose how the usage was matched");
}
