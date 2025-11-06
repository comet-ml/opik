package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.comet.opik.api.Span.SpanPage;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class SpanAssertions {

    public static final Map<Span.SpanField, Function<Span, Span>> EXCLUDE_FUNCTIONS = new EnumMap<>(
            Span.SpanField.class);

    static {
        EXCLUDE_FUNCTIONS.put(Span.SpanField.NAME, it -> it.toBuilder().name(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.TYPE, it -> it.toBuilder().type(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.START_TIME, it -> it.toBuilder().startTime(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.END_TIME, it -> it.toBuilder().endTime(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.INPUT, it -> it.toBuilder().input(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.OUTPUT, it -> it.toBuilder().output(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.METADATA, it -> it.toBuilder().metadata(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.MODEL, it -> it.toBuilder().model(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.PROVIDER, it -> it.toBuilder().provider(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.TAGS, it -> it.toBuilder().tags(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.USAGE, it -> it.toBuilder().usage(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.ERROR_INFO, it -> it.toBuilder().errorInfo(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.CREATED_AT, it -> it.toBuilder().createdAt(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.CREATED_BY, it -> it.toBuilder().createdBy(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.LAST_UPDATED_BY, it -> it.toBuilder().lastUpdatedBy(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.FEEDBACK_SCORES, it -> it.toBuilder().feedbackScores(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.COMMENTS, it -> it.toBuilder().comments(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.TOTAL_ESTIMATED_COST,
                it -> it.toBuilder().totalEstimatedCost(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.TOTAL_ESTIMATED_COST_VERSION,
                it -> it.toBuilder().totalEstimatedCostVersion(null).build());
        EXCLUDE_FUNCTIONS.put(Span.SpanField.DURATION, it -> it.toBuilder().duration(null).build());
    }

    public static final String[] IGNORED_FIELDS = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy", "totalEstimatedCost", "duration",
            "totalEstimatedCostVersion", "comments"};

    public static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy",
            "valueByAuthor"};

    /**
     * Prepares a span for assertion by injecting provider into metadata if provider is set.
     * This mirrors the backend behavior where provider is automatically injected into metadata.
     *
     * @param span the span to prepare
     * @return a new span with provider injected into metadata if provider is present
     */
    private static Span prepareSpanForAssertion(Span span) {
        if (span.provider() == null || span.provider().isBlank()) {
            return span;
        }

        JsonNode metadataWithProvider = JsonUtils.prependField(
                span.metadata(), Span.SpanField.PROVIDER.getValue(), span.provider());

        return span.toBuilder()
                .metadata(metadataWithProvider)
                .build();
    }

    /**
     * Prepares a list of spans for assertion by injecting provider into metadata.
     *
     * @param spans the spans to prepare
     * @return a new list of spans with provider injected into metadata where applicable
     */
    private static List<Span> prepareSpansForAssertion(List<Span> spans) {
        return spans.stream()
                .map(SpanAssertions::prepareSpanForAssertion)
                .toList();
    }

    public static void assertPage(SpanPage actualPage, int page, int expectedPageSize, int expectedTotal) {
        assertThat(actualPage.page()).isEqualTo(page);
        assertThat(actualPage.size()).isEqualTo(expectedPageSize);
        assertThat(actualPage.total()).isEqualTo(expectedTotal);
    }

    public static void assertSpan(List<Span> actualSpans, List<Span> expectedSpans, String userName) {
        assertSpan(actualSpans, expectedSpans, List.of(), userName);
    }

    public static void assertSpan(List<Span> actualSpans, List<Span> expectedSpans, List<Span> unexpectedSpans,
            String userName) {

        // Automatically prepare expected spans with actual provider injected into metadata
        // We need to use actual provider because it's from the database
        var preparedExpectedSpans = expectedSpans.stream()
                .map(expected -> {
                    var actual = actualSpans.stream()
                            .filter(a -> a.id().equals(expected.id()))
                            .findFirst()
                            .orElse(null);
                    if (actual == null) {
                        return prepareSpanForAssertion(expected);
                    }
                    // Use actual provider for metadata injection
                    var expectedWithActualProvider = expected.toBuilder()
                            .provider(actual.provider())
                            .build();
                    return prepareSpanForAssertion(expectedWithActualProvider);
                })
                .toList();

        assertThat(actualSpans).hasSize(expectedSpans.size());
        assertThat(actualSpans)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .ignoringCollectionOrderInFields("tags")
                .isEqualTo(preparedExpectedSpans);

        assertIgnoredFields(actualSpans, preparedExpectedSpans, userName);

        if (!unexpectedSpans.isEmpty()) {
            var preparedUnexpectedSpans = prepareSpansForAssertion(unexpectedSpans);
            assertThat(actualSpans)
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS)
                    .ignoringCollectionOrderInFields("tags")
                    .isNotEqualTo(preparedUnexpectedSpans);
        }
    }

    public static void assertIgnoredFields(List<Span> actualSpans, List<Span> expectedSpans, String userName) {
        for (int i = 0; i < actualSpans.size(); i++) {
            var actualSpan = actualSpans.get(i);
            var expectedSpan = expectedSpans.get(i);

            if (expectedSpan.startTime() != null && expectedSpan.endTime() != null && expectedSpan.duration() != null) {
                expectedSpan = expectedSpan.toBuilder()
                        .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(expectedSpan.startTime(),
                                expectedSpan.endTime()))
                        .build();
            }

            var expectedFeedbackScores = expectedSpan.feedbackScores() == null
                    ? null
                    : expectedSpan.feedbackScores().reversed();
            assertThat(actualSpan.projectId()).isNotNull();

            // Pagination endpoint doesn't resolve projectName, whereas get by id does
            if (actualSpan.projectName() != null) {
                assertThat(actualSpan.projectName()).isEqualTo(expectedSpan.projectName());
            }

            if (actualSpan.createdAt() != null) {
                assertThat(actualSpan.createdAt()).isAfter(expectedSpan.createdAt());
            }

            if (actualSpan.lastUpdatedAt() != null) {
                if (expectedSpan.lastUpdatedAt() != null) {
                    assertThat(actualSpan.lastUpdatedAt())
                            // Some JVMs can resolve higher than microseconds, such as nanoseconds in the Ubuntu AMD64 JVM
                            .isAfterOrEqualTo(expectedSpan.lastUpdatedAt().truncatedTo(ChronoUnit.MICROS));
                } else {
                    assertThat(actualSpan.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
                }
            }

            // The createdBy field can be excluded from the response
            if (actualSpan.createdBy() != null) {
                assertThat(actualSpan.createdBy()).isEqualTo(userName);
            }

            // The lastUpdatedBy field can be excluded from the response
            if (actualSpan.lastUpdatedBy() != null) {
                assertThat(actualSpan.lastUpdatedBy()).isEqualTo(userName);
            }

            assertThat(actualSpan.feedbackScores())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                    .build())
                    .ignoringCollectionOrder()
                    .isEqualTo(expectedFeedbackScores);

            if (actualSpan.duration() == null || expectedSpan.duration() == null) {
                assertThat(actualSpan.duration()).isEqualTo(expectedSpan.duration());
            } else {
                assertThat(actualSpan.duration()).isEqualTo(expectedSpan.duration(), within(0.001));
            }

            if (actualSpan.feedbackScores() != null) {
                Instant createdAt = expectedSpan.createdAt() == null
                        ? expectedSpan.lastUpdatedAt()
                        : expectedSpan.createdAt();
                Instant lastUpdatedAt = expectedSpan.lastUpdatedAt();

                actualSpan.feedbackScores().forEach(feedbackScore -> {
                    assertThat(feedbackScore.createdAt()).isAfter(createdAt);
                    assertThat(feedbackScore.lastUpdatedAt()).isAfter(lastUpdatedAt);
                    assertThat(feedbackScore.createdBy()).isEqualTo(userName);
                    assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(userName);
                });
            }

            if (actualSpan.comments() != null) {
                assertComments(expectedSpan.comments(), actualSpan.comments());

                actualSpan.comments().forEach(comment -> {
                    assertThat(comment.createdAt()).isAfter(actualSpan.lastUpdatedAt());
                    assertThat(comment.lastUpdatedAt()).isAfter(actualSpan.lastUpdatedAt());
                    assertThat(comment.createdBy()).isEqualTo(userName);
                    assertThat(comment.lastUpdatedBy()).isEqualTo(userName);
                });
            }
        }
    }

    public static void assertionStatusPage(List<ProjectStatItem<?>> actualStats,
            List<ProjectStatItem<?>> expectedStats) {

        assertThat(actualStats).hasSize(expectedStats.size());

        assertThat(actualStats)
                .usingRecursiveComparison(StatsUtils.getRecursiveComparisonConfiguration())
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                .isEqualTo(expectedStats);

    }

}
