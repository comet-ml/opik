package com.comet.opik.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.comet.opik.api.resources.v1.events.EvalSuiteAssertionSampler.SUITE_ASSERTION_CATEGORY;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScoreDestination routing")
class ScoreDestinationTest {

    @Test
    @DisplayName("suite_assertion categoryName resolves to ASSERTION_RESULTS")
    void suiteAssertionCategoryResolvesToAssertionResults() {
        ScoreDestination destination = SUITE_ASSERTION_CATEGORY.equals(SUITE_ASSERTION_CATEGORY)
                ? ScoreDestination.ASSERTION_RESULTS
                : ScoreDestination.FEEDBACK_SCORES;

        assertThat(destination).isEqualTo(ScoreDestination.ASSERTION_RESULTS);
    }

    @Test
    @DisplayName("null categoryName resolves to FEEDBACK_SCORES")
    void nullCategoryResolvesToFeedbackScores() {
        ScoreDestination destination = SUITE_ASSERTION_CATEGORY.equals(null)
                ? ScoreDestination.ASSERTION_RESULTS
                : ScoreDestination.FEEDBACK_SCORES;

        assertThat(destination).isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @Test
    @DisplayName("arbitrary categoryName resolves to FEEDBACK_SCORES")
    void arbitraryCategoryResolvesToFeedbackScores() {
        ScoreDestination destination = SUITE_ASSERTION_CATEGORY.equals("some_other_category")
                ? ScoreDestination.ASSERTION_RESULTS
                : ScoreDestination.FEEDBACK_SCORES;

        assertThat(destination).isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @Test
    @DisplayName("FeedbackScoreBatchItem defaults to FEEDBACK_SCORES when constructed via JSON constructor")
    void feedbackScoreBatchItemDefaultsToFeedbackScores() {
        var item = new FeedbackScoreItem.FeedbackScoreBatchItem(
                "project", UUID.randomUUID(), "score-name", null,
                BigDecimal.ONE, null, ScoreSource.ONLINE_SCORING, null, UUID.randomUUID());

        assertThat(item.scoreDestination()).isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @Test
    @DisplayName("FeedbackScoreBatchItem can be built with ASSERTION_RESULTS via builder")
    void feedbackScoreBatchItemCanBeSetToAssertionResults() {
        var item = FeedbackScoreItem.FeedbackScoreBatchItem.builder()
                .name("assertion_1")
                .value(BigDecimal.ONE)
                .source(ScoreSource.ONLINE_SCORING)
                .id(UUID.randomUUID())
                .categoryName(SUITE_ASSERTION_CATEGORY)
                .scoreDestination(ScoreDestination.ASSERTION_RESULTS)
                .build();

        assertThat(item.scoreDestination()).isEqualTo(ScoreDestination.ASSERTION_RESULTS);
        assertThat(item.categoryName()).isEqualTo(SUITE_ASSERTION_CATEGORY);
    }
}
