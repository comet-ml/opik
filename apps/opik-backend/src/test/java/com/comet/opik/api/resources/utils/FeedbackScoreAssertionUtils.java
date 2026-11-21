package com.comet.opik.api.resources.utils;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.ValueEntry;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class FeedbackScoreAssertionUtils {

    public static void assertFeedbackScoreNames(FeedbackScoreNames actual, List<String> expectedNames) {
        // Filter to only feedback_scores (exclude experiment_scores) since this test is about feedback score names
        var actualNames = actual.scores().stream()
                .filter(score -> score.type() == null || "feedback_scores".equals(score.type()))
                .map(FeedbackScoreNames.ScoreName::name)
                .toList();
        assertThat(actualNames).containsExactlyInAnyOrderElementsOf(expectedNames);
    }

    public static ExperimentItem assertFeedbackScoresIgnoredFieldsAndSetThemToNull(ExperimentItem actualExperimentItem,
            String user) {
        if (actualExperimentItem.feedbackScores() == null) {
            return actualExperimentItem;
        }
        actualExperimentItem.feedbackScores().forEach(feedbackScore -> {
            assertThat(feedbackScore.createdBy()).isEqualTo(user);
            assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(user);
            assertThat(feedbackScore.createdAt()).isNotNull();
            assertThat(feedbackScore.lastUpdatedAt()).isNotNull();
        });

        return actualExperimentItem.toBuilder()
                .feedbackScores(actualExperimentItem.feedbackScores().stream().map(
                        feedbackScore -> feedbackScore.toBuilder()
                                .createdBy(null)
                                .lastUpdatedBy(null)
                                .createdAt(null)
                                .lastUpdatedAt(null)
                                .valueByAuthor(null)
                                .sourceQueueId(null)
                                .build())
                        .toList())
                .build();
    }

    public static FeedbackScore expectedFeedbackScore(FeedbackScoreItem item, String author) {
        return FeedbackScore.builder()
                .name(item.name())
                .categoryName(item.categoryName())
                .value(item.value())
                .reason(item.reason())
                .source(item.source())
                .createdBy(author)
                .lastUpdatedBy(author)
                .evaluatorRevision(item.evaluatorRevision())
                .valueByAuthor(Map.of(author, ValueEntry.builder()
                        .value(item.value())
                        .reason(item.reason())
                        .categoryName(item.categoryName())
                        .source(item.source())
                        .spanType("")
                        .spanId("")
                        .author(author)
                        .evaluatorRevision(item.evaluatorRevision())
                        .build()))
                .build();
    }

    public static void assertFeedbackScores(List<FeedbackScore> actual, List<FeedbackScore> expected) {
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .withIgnoredFieldsOfTypes(Instant.class)
                        .build())
                .containsExactlyInAnyOrderElementsOf(expected);
    }
}
