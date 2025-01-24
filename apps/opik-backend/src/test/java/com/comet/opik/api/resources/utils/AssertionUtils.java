package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Comment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScoreNames;
import lombok.experimental.UtilityClass;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class AssertionUtils {

    public static final String[] IGNORED_FIELDS_COMMENTS = {"id", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};

    public static void assertFeedbackScoreNames(FeedbackScoreNames actual, List<String> expectedNames) {
        assertThat(actual.scores()).hasSize(expectedNames.size());
        assertThat(actual
                .scores()
                .stream()
                .map(FeedbackScoreNames.ScoreName::name)
                .toList()).containsExactlyInAnyOrderElementsOf(expectedNames);
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
                                .build())
                        .toList())
                .build();
    }

    public static void assertTraceComment(Comment expected, Comment actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_COMMENTS)
                .isEqualTo(expected);

        assertThat(actual.createdAt()).isNotNull();
        assertThat(actual.lastUpdatedAt()).isNotNull();
        assertThat(actual.createdBy()).isNotNull();
        assertThat(actual.lastUpdatedBy()).isNotNull();
    }

    public static void assertUpdatedComment(Comment initial, Comment updated, String expectedText) {
        assertThat(initial.text()).isNotEqualTo(expectedText);

        assertTraceComment(initial.toBuilder().text(expectedText).build(), updated);

        assertThat(updated.createdAt()).isEqualTo(initial.createdAt());
        assertThat(updated.lastUpdatedAt()).isNotEqualTo(initial.lastUpdatedAt());
        assertThat(updated.createdBy()).isEqualTo(initial.createdBy());
        assertThat(updated.lastUpdatedBy()).isEqualTo(initial.lastUpdatedBy());
    }

    public static void assertComments(List<Comment> expected, List<Comment> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_COMMENTS)
                .isEqualTo(expected);
    }
}
