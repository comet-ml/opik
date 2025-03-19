package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.math.BigDecimal;
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
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                .containsExactlyElementsOf(expectedSpans);

        assertIgnoredFields(actualSpans, expectedSpans, userName);

        if (!unexpectedSpans.isEmpty()) {
            assertThat(actualSpans)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedSpans);
        }
    }

    public static void assertIgnoredFields(List<Span> actualSpans, List<Span> expectedSpans, String userName) {
        for (int i = 0; i < actualSpans.size(); i++) {
            var actualSpan = actualSpans.get(i);
            var expectedSpan = expectedSpans.get(i);
            var expectedFeedbackScores = expectedSpan.feedbackScores() == null
                    ? null
                    : expectedSpan.feedbackScores().reversed();
            assertThat(actualSpan.projectId()).isNotNull();
            assertThat(actualSpan.projectName()).isNull();
            assertThat(actualSpan.createdAt()).isAfter(expectedSpan.createdAt());
            assertThat(actualSpan.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
            assertThat(actualSpan.feedbackScores())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                    .build())
                    .isEqualTo(expectedFeedbackScores);
            var expected = DurationUtils.getDurationInMillisWithSubMilliPrecision(
                    expectedSpan.startTime(), expectedSpan.endTime());
            if (actualSpan.duration() == null || expected == null) {
                assertThat(actualSpan.duration()).isEqualTo(expected);
            } else {
                assertThat(actualSpan.duration()).isEqualTo(expected, within(0.001));
            }

            if (actualSpan.feedbackScores() != null) {
                actualSpan.feedbackScores().forEach(feedbackScore -> {
                    assertThat(feedbackScore.createdAt()).isAfter(expectedSpan.createdAt());
                    assertThat(feedbackScore.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
                    assertThat(feedbackScore.createdBy()).isEqualTo(userName);
                    assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(userName);
                });
            }

            if (actualSpan.comments() != null) {
                assertComments(expectedSpan.comments(), actualSpan.comments());

                actualSpan.comments().forEach(comment -> {
                    assertThat(comment.createdAt()).isAfter(actualSpan.createdAt());
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
