package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class TraceAssertions {

    public static final String[] IGNORED_FIELDS_TRACES = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy", "totalEstimatedCost", "spanCount",
            "duration", "comments", "threadId", "guardrailsValidations"};

    private static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

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

        assertThat(actualTraces)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                .containsExactlyElementsOf(expectedTraces);

        assertIgnoredFields(actualTraces, expectedTraces, user);

        if (!unexpectedTraces.isEmpty()) {
            assertThat(actualTraces)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                    .doesNotContainAnyElementsOf(unexpectedTraces);
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
            assertThat(actualTrace.createdAt()).isAfter(expectedTrace.createdAt());
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

        assertThat(actualTrace.feedbackScores())
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .ignoringFields(IGNORED_FIELDS_SCORES)
                .ignoringCollectionOrder()
                .isEqualTo(expectedTrace.feedbackScores());

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
                .isEqualTo(expectedStats);
    }

    public static void assertPage(Trace.TracePage actualPage, int page, int expectedPageSize, int expectedTotal) {
        assertThat(actualPage.page()).isEqualTo(page);
        assertThat(actualPage.size()).isEqualTo(expectedPageSize);
        assertThat(actualPage.total()).isEqualTo(expectedTotal);
    }
}
