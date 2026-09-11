package com.comet.opik.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
                BigDecimal.ONE, null, ScoreSource.ONLINE_SCORING, null, null, null, UUID.randomUUID());

        assertThat(item.scoreDestination()).isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    static Stream<Arguments> metadataVariants() {
        return Stream.of(
                Arguments.of(Named.of("null", null)),
                Arguments.of(Named.of("empty", Map.<String, Object>of())),
                Arguments.of(Named.of("flat", Map.<String, Object>of("evaluator", "exact_match", "revision", "abc1"))),
                Arguments.of(Named.of("nested", Map.<String, Object>of("outer", Map.of("inner", "value")))));
    }

    @ParameterizedTest
    @MethodSource("metadataVariants")
    @DisplayName("FeedbackScoreBatchItem carries metadata unchanged without affecting routing")
    void feedbackScoreBatchItemCarriesMetadata(Map<String, Object> metadata) {
        var item = new FeedbackScoreItem.FeedbackScoreBatchItem(
                "project", UUID.randomUUID(), "score-name", null,
                BigDecimal.ONE, null, ScoreSource.ONLINE_SCORING, null, null, metadata, UUID.randomUUID());

        assertThat(item.metadata()).isEqualTo(metadata);
        assertThat(item.scoreDestination()).isEqualTo(ScoreDestination.FEEDBACK_SCORES);
    }

    @ParameterizedTest
    @MethodSource("metadataVariants")
    @DisplayName("FeedbackScoreBatchItemThread carries metadata unchanged")
    void feedbackScoreBatchItemThreadCarriesMetadata(Map<String, Object> metadata) {
        var item = new FeedbackScoreItem.FeedbackScoreBatchItemThread(
                "project", UUID.randomUUID(), "score-name", null,
                BigDecimal.ONE, null, ScoreSource.ONLINE_SCORING, null, null, metadata, "thread-id");

        assertThat(item.metadata()).isEqualTo(metadata);
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
