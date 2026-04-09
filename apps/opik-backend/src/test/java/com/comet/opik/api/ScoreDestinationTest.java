package com.comet.opik.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScoreDestination routing")
class ScoreDestinationTest {

    @Test
    @DisplayName("suite_assertion categoryName resolves to ASSERTION_RESULTS")
    void suiteAssertionCategoryResolvesToAssertionResults() {
        assertThat(ScoreDestination.fromCategoryName("suite_assertion"))
                .isEqualTo(ScoreDestination.ASSERTION_RESULTS);
    }

    @Test
    @DisplayName("null categoryName resolves to FEEDBACK_SCORES")
    void nullCategoryResolvesToFeedbackScores() {
        assertThat(ScoreDestination.fromCategoryName(null))
                .isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @Test
    @DisplayName("arbitrary categoryName resolves to FEEDBACK_SCORES")
    void arbitraryCategoryResolvesToFeedbackScores() {
        assertThat(ScoreDestination.fromCategoryName("some_other_category"))
                .isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @Test
    @DisplayName("FeedbackScoreBatchItem without categoryName defaults to FEEDBACK_SCORES")
    void feedbackScoreBatchItemDefaultsToFeedbackScores() {
        var item = new FeedbackScoreItem.FeedbackScoreBatchItem(
                "project", UUID.randomUUID(), "score-name", null,
                BigDecimal.ONE, null, ScoreSource.ONLINE_SCORING, null, UUID.randomUUID());

        assertThat(item.scoreDestination()).isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @Test
    @DisplayName("FeedbackScoreBatchItem with suite_assertion categoryName resolves to ASSERTION_RESULTS")
    void feedbackScoreBatchItemWithSuiteAssertionResolvesToAssertionResults() {
        var item = FeedbackScoreItem.FeedbackScoreBatchItem.builder()
                .name("assertion_1")
                .value(BigDecimal.ONE)
                .source(ScoreSource.ONLINE_SCORING)
                .id(UUID.randomUUID())
                .categoryName("suite_assertion")
                .build();

        assertThat(item.scoreDestination()).isEqualTo(ScoreDestination.ASSERTION_RESULTS);
        assertThat(item.categoryName()).isEqualTo("suite_assertion");
    }
}
