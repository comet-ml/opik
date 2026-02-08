package com.comet.opik.api.resources.utils;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScoreNames;
import lombok.experimental.UtilityClass;

import java.util.List;

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
                                .build())
                        .toList())
                .build();
    }
}
