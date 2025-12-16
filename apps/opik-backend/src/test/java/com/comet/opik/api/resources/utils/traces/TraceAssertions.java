package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class TraceAssertions {

    public static final String[] IGNORED_FIELDS_TRACES = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "spanFeedbackScores", "createdBy", "lastUpdatedBy", "totalEstimatedCost",
            "spanCount", "llmSpanCount", "hasToolSpans", "duration", "comments", "threadId", "guardrailsValidations",
            "providers"};

    public static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy",
            "valueByAuthor"};

    private static final String[] IGNORED_FIELDS_THREADS = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy",
            "threadModelId", "feedbackScores.createdAt", "feedbackScores.lastUpdatedAt",
            "feedbackScores.valueByAuthor"};

    /**
     * Prepares a trace for assertion by injecting providers into metadata if providers are set.
     * This mirrors the backend behavior where providers are automatically injected into metadata.
     *
     * @param trace the trace to prepare
     * @return a new trace with providers injected into metadata if providers are present
     */
    private static Trace prepareTraceForAssertion(Trace trace) {
        if (trace.providers() == null || trace.providers().isEmpty()) {
            return trace;
        }

        JsonNode metadataWithProviders = JsonUtils.prependField(
                trace.metadata(), Trace.TraceField.PROVIDERS.getValue(), trace.providers());

        return trace.toBuilder()
                .metadata(metadataWithProviders)
                .build();
    }

    /**
     * Prepares a list of traces for assertion by injecting providers into metadata.
     *
     * @param traces the traces to prepare
     * @return a new list of traces with providers injected into metadata where applicable
     */
    private static List<Trace> prepareTracesForAssertion(List<Trace> traces) {
        return traces.stream()
                .map(TraceAssertions::prepareTraceForAssertion)
                .toList();
    }

    public static final Map<Trace.TraceField, Function<Trace, Trace>> EXCLUDE_FUNCTIONS = new EnumMap<>(
            Trace.TraceField.class);

    static {
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.NAME, it -> it.toBuilder().name(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.START_TIME, it -> it.toBuilder().startTime(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.END_TIME, it -> it.toBuilder().endTime(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.INPUT, it -> it.toBuilder().input(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.OUTPUT, it -> it.toBuilder().output(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.METADATA, it -> it.toBuilder().metadata(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.TAGS, it -> it.toBuilder().tags(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.USAGE, it -> it.toBuilder().usage(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.ERROR_INFO, it -> it.toBuilder().errorInfo(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.CREATED_AT, it -> it.toBuilder().createdAt(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.CREATED_BY, it -> it.toBuilder().createdBy(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.LAST_UPDATED_BY, it -> it.toBuilder().lastUpdatedBy(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.FEEDBACK_SCORES, it -> it.toBuilder().feedbackScores(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.SPAN_FEEDBACK_SCORES,
                it -> it.toBuilder().spanFeedbackScores(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.COMMENTS, it -> it.toBuilder().comments(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.GUARDRAILS_VALIDATIONS,
                it -> it.toBuilder().guardrailsValidations(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.SPAN_COUNT, it -> it.toBuilder().spanCount(0).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.LLM_SPAN_COUNT, it -> it.toBuilder().llmSpanCount(0).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.HAS_TOOL_SPANS, it -> it.toBuilder().hasToolSpans(false).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.TOTAL_ESTIMATED_COST,
                it -> it.toBuilder().totalEstimatedCost(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.THREAD_ID, it -> it.toBuilder().threadId(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.DURATION, it -> it.toBuilder().duration(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.VISIBILITY_MODE, it -> it.toBuilder().visibilityMode(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.PROVIDERS, it -> it.toBuilder().providers(null).build());
    }

    public static void assertErrorResponse(Response actualResponse, String message, int expectedStatus) {
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                .isEqualTo(message);
    }

    public static void assertTraces(List<Trace> actualTraces, List<Trace> expectedTraces, String user) {
        assertTraces(actualTraces, expectedTraces, List.of(), user);
    }

    public static void assertTraces(List<Trace> actualTraces, List<Trace> expectedTraces, List<Trace> unexpectedTraces,
            String user) {

        // Automatically prepare expected traces with actual providers injected into metadata
        // We need to use actual providers because they're calculated from spans in the database
        var preparedExpectedTraces = expectedTraces.stream()
                .map(expected -> {
                    var actual = actualTraces.stream()
                            .filter(a -> a.id().equals(expected.id()))
                            .findFirst()
                            .orElse(null);
                    if (actual == null) {
                        return prepareTraceForAssertion(expected);
                    }
                    // Use actual providers for metadata injection
                    var expectedWithActualProviders = expected.toBuilder()
                            .providers(actual.providers())
                            .build();
                    return prepareTraceForAssertion(expectedWithActualProviders);
                })
                .toList();

        assertThat(actualTraces)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                .containsExactlyElementsOf(preparedExpectedTraces);

        assertIgnoredFields(actualTraces, preparedExpectedTraces, user);

        if (!unexpectedTraces.isEmpty()) {
            var preparedUnexpectedTraces = prepareTracesForAssertion(unexpectedTraces);
            assertThat(actualTraces)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                    .doesNotContainAnyElementsOf(preparedUnexpectedTraces);
        }
    }

    private static void assertIgnoredFields(List<Trace> actualTraces, List<Trace> expectedTraces, String user) {
        assertThat(actualTraces).size().isEqualTo(expectedTraces.size());
        for (int i = 0; i < actualTraces.size(); i++) {
            var actualTrace = actualTraces.get(i);
            var expectedTrace = expectedTraces.get(i);

            if (expectedTrace.startTime() != null && expectedTrace.endTime() != null
                    && expectedTrace.duration() != null) {
                expectedTrace = expectedTrace.toBuilder()
                        .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(expectedTrace.startTime(),
                                expectedTrace.endTime()))
                        .build();
            }

            assertIgnoredFields(actualTrace, expectedTrace, user);
        }
    }

    private static void assertIgnoredFields(Trace actualTrace, Trace expectedTrace, String user) {
        assertThat(actualTrace.projectId()).isNotNull();
        assertThat(actualTrace.projectName()).isNull();

        if (actualTrace.createdAt() != null) {
            assertThat(actualTrace.createdAt()).isAfterOrEqualTo(expectedTrace.createdAt());
        }

        if (actualTrace.lastUpdatedAt() != null) {
            if (expectedTrace.lastUpdatedAt() != null) {
                assertThat(actualTrace.lastUpdatedAt())
                        // Some JVMs can resolve higher than microseconds, such as nanoseconds in the Ubuntu AMD64 JVM
                        .isAfterOrEqualTo(expectedTrace.lastUpdatedAt().truncatedTo(ChronoUnit.MICROS));
            } else {
                assertThat(actualTrace.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
            }
        }

        if (actualTrace.createdBy() != null) {
            assertThat(actualTrace.createdBy()).isEqualTo(user);
        }

        if (actualTrace.lastUpdatedBy() != null) {
            assertThat(actualTrace.lastUpdatedBy()).isEqualTo(user);
        }

        assertThat(actualTrace.threadId()).isEqualTo(expectedTrace.threadId());

        if (actualTrace.duration() == null || expectedTrace.duration() == null) {
            assertThat(actualTrace.duration()).isEqualTo(expectedTrace.duration());
        } else {
            assertThat(actualTrace.duration()).isEqualTo(expectedTrace.duration(), within(0.001));
        }

        RecursiveComparisonConfiguration config = new RecursiveComparisonConfiguration();
        config.ignoreFields(IGNORED_FIELDS_SCORES);
        config.registerComparatorForType(BigDecimal::compareTo, BigDecimal.class);

        if (expectedTrace.feedbackScores() == null) {
            assertThat(actualTrace.feedbackScores()).isNull();
        } else {
            assertThat(actualTrace.feedbackScores())
                    .usingRecursiveFieldByFieldElementComparator(config)
                    .containsExactlyInAnyOrderElementsOf(expectedTrace.feedbackScores());
        }

        if (expectedTrace.feedbackScores() != null) {
            Instant lastUpdatedAt = expectedTrace.lastUpdatedAt();
            actualTrace.feedbackScores().forEach(feedbackScore -> {
                assertThat(feedbackScore.createdAt()).isAfter(lastUpdatedAt);
                assertThat(feedbackScore.lastUpdatedAt()).isAfter(lastUpdatedAt);
                assertThat(feedbackScore.createdBy()).isEqualTo(user);
                assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(user);
            });
        }

        if (actualTrace.comments() != null) {
            assertComments(expectedTrace.comments(), actualTrace.comments());

            Instant lastUpdatedAt = actualTrace.lastUpdatedAt();

            actualTrace.comments().forEach(comment -> {
                assertThat(comment.createdAt()).isAfter(lastUpdatedAt);
                assertThat(comment.lastUpdatedAt()).isAfter(lastUpdatedAt);
                assertThat(comment.createdBy()).isEqualTo(user);
                assertThat(comment.lastUpdatedBy()).isEqualTo(user);
            });
        }

    }

    public static void assertStats(List<ProjectStatItem<?>> actualStats, List<ProjectStatItem<?>> expectedStats) {
        assertThat(actualStats).hasSize(expectedStats.size());

        assertThat(actualStats)
                .usingRecursiveComparison(StatsUtils.getRecursiveComparisonConfiguration())
                .ignoringCollectionOrder()
                .isEqualTo(expectedStats);
    }

    public static void assertPage(Trace.TracePage actualPage, int page, int expectedPageSize, int expectedTotal) {
        assertThat(actualPage.page()).isEqualTo(page);
        assertThat(actualPage.size()).isEqualTo(expectedPageSize);
        assertThat(actualPage.total()).isEqualTo(expectedTotal);
    }

    public static void assertThreads(List<TraceThread> expectedThreads, List<TraceThread> actualThreads) {
        assertThat(actualThreads)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_THREADS)
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .ignoringCollectionOrderInFields("feedbackScores")
                .isEqualTo(expectedThreads);
    }

}