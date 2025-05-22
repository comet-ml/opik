package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.comet.opik.api.Span.SpanPage;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class SpanAssertions {

    public static final String[] IGNORED_FIELDS = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy", "totalEstimatedCost", "duration",
            "totalEstimatedCostVersion", "comments"};

    public static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

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

        assertThat(actualSpans).hasSize(expectedSpans.size());
        assertThat(actualSpans)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .ignoringCollectionOrderInFields("tags")
                .isEqualTo(expectedSpans);

        assertIgnoredFields(actualSpans, expectedSpans, userName);

        if (!unexpectedSpans.isEmpty()) {
            assertThat(actualSpans)
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS)
                    .ignoringCollectionOrderInFields("tags")
                    .isNotEqualTo(unexpectedSpans);
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
