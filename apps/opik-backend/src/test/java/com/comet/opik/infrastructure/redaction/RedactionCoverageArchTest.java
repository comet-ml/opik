package com.comet.opik.infrastructure.redaction;

import com.comet.opik.domain.Streamer;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.glassfish.jersey.server.ChunkedOutput;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural guard for read-time redaction coverage.
 * <p>
 * Redaction is applied by serializers registered on the environment's {@code ObjectMapper}, so any response
 * Jackson writes through it is covered — including endpoints that do not exist yet. A chunked response is the
 * one shape that escapes: its items are serialized on a scheduler thread where the writer interceptor's
 * thread-local is not in force, which is why {@link Streamer} redacts each item explicitly. A new
 * {@code ChunkedOutput} built anywhere else would stream stored content with no rules applied, and nothing at
 * runtime would say so — the streamed-search leak this feature already shipped once.
 * <p>
 * The exclusion list is therefore the registry of known-uncovered streaming responses, and it is deliberately
 * awkward to extend: adding a class here is a decision to leave that response unredacted.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class RedactionCoverageArchTest {

    /**
     * allowEmptyShould: a negative rule, so in the healthy state the should-clause matches nothing, which
     * ArchUnit otherwise rejects. It still fails the build when a new constructor call appears.
     */
    @ArchTest
    static final ArchRule chunked_responses_must_be_built_by_the_redaction_aware_streamer = noClasses()
            .that().doNotBelongToAnyOf(Streamer.class,
                    com.comet.opik.api.resources.v1.priv.ChatCompletionsResource.class)
            .should().callConstructorWhere(DescribedPredicate.describe("a ChunkedOutput constructor",
                    target -> target.getTargetOwner().isAssignableTo(ChunkedOutput.class)))
            .because("""
                    a chunked response is serialized off the request thread, so the writer interceptor cannot \
                    reach it; only Streamer applies the rules explicitly. ChatCompletionsResource streams \
                    provider text through ChunkedOutput<String> and is a known, documented gap — every other \
                    chunked response must go through Streamer
                    """)
            .allowEmptyShould(true);
}
