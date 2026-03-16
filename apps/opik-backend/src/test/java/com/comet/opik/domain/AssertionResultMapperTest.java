package com.comet.opik.domain;

import com.comet.opik.api.AssertionResult;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentRunSummary;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.RunStatus;
import com.comet.opik.api.ScoreSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionResultMapperTest {

    @Test
    void enrichWithAssertions_noFeedbackScores_returnsUnchanged() {
        var item = baseItem().build();

        var result = AssertionResultMapper.enrichWithAssertions(item);

        assertThat(result.assertionResults()).isNull();
        assertThat(result.status()).isNull();
        assertThat(result.feedbackScores()).isNull();
    }

    @Test
    void enrichWithAssertions_onlyRegularScores_returnsUnchanged() {
        var item = baseItem()
                .feedbackScores(List.of(regularScore("accuracy", BigDecimal.valueOf(0.85))))
                .build();

        var result = AssertionResultMapper.enrichWithAssertions(item);

        assertThat(result.assertionResults()).isNull();
        assertThat(result.status()).isNull();
        assertThat(result.feedbackScores()).hasSize(1);
        assertThat(result.feedbackScores().getFirst().name()).isEqualTo("accuracy");
    }

    @Test
    void enrichWithAssertions_allAssertionsPass_statusPassed() {
        var item = baseItem()
                .feedbackScores(List.of(
                        assertionScore("Should link to docs", BigDecimal.ONE, "Links found"),
                        assertionScore("Should be concise", BigDecimal.valueOf(1.0), "Under 200 words")))
                .build();

        var result = AssertionResultMapper.enrichWithAssertions(item);

        assertThat(result.assertionResults()).hasSize(2);
        assertThat(result.assertionResults()).allMatch(AssertionResult::passed);
        assertThat(result.status()).isEqualTo(RunStatus.PASSED);
        assertThat(result.feedbackScores()).isNull();
    }

    @Test
    void enrichWithAssertions_someAssertionsFail_statusFailed() {
        var item = baseItem()
                .feedbackScores(List.of(
                        assertionScore("Should link to docs", BigDecimal.ONE, "Links found"),
                        assertionScore("Should be concise", BigDecimal.ZERO, "Too long")))
                .build();

        var result = AssertionResultMapper.enrichWithAssertions(item);

        assertThat(result.assertionResults()).hasSize(2);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.assertionResults().get(0).passed()).isTrue();
        assertThat(result.assertionResults().get(1).passed()).isFalse();
    }

    @Test
    void enrichWithAssertions_mixedScoreTypes_splitsCorrectly() {
        var item = baseItem()
                .feedbackScores(List.of(
                        regularScore("accuracy", BigDecimal.valueOf(0.85)),
                        assertionScore("Should link to docs", BigDecimal.ONE, "Links found"),
                        regularScore("relevance", BigDecimal.valueOf(0.9))))
                .build();

        var result = AssertionResultMapper.enrichWithAssertions(item);

        assertThat(result.feedbackScores()).hasSize(2);
        assertThat(result.feedbackScores()).extracting(FeedbackScore::name)
                .containsExactly("accuracy", "relevance");
        assertThat(result.assertionResults()).hasSize(1);
        assertThat(result.assertionResults().getFirst().value()).isEqualTo("Should link to docs");
        assertThat(result.status()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void computeRunSummaries_singleRun_returnsNull() {
        var item = baseItem()
                .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                .status(RunStatus.PASSED)
                .build();

        var result = AssertionResultMapper.computeRunSummaries(List.of(item));

        assertThat(result).isNull();
    }

    @Test
    void computeRunSummaries_multipleRuns_returnsSummary() {
        var experimentId = UUID.randomUUID();
        var items = List.of(
                baseItem().experimentId(experimentId)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build(),
                baseItem().experimentId(experimentId)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(false).build()))
                        .status(RunStatus.FAILED).build(),
                baseItem().experimentId(experimentId)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build());

        Map<String, ExperimentRunSummary> result = AssertionResultMapper.computeRunSummaries(items);

        assertThat(result).hasSize(1);
        var summary = result.get(experimentId.toString());
        assertThat(summary.passedRuns()).isEqualTo(2);
        assertThat(summary.totalRuns()).isEqualTo(3);
        assertThat(summary.status()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void computeRunSummaries_passThresholdNotMet_statusFailed() {
        var experimentId = UUID.randomUUID();
        var policy = ExecutionPolicy.builder().runsPerItem(3).passThreshold(3).build();
        var items = List.of(
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(false).build()))
                        .status(RunStatus.FAILED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build());

        Map<String, ExperimentRunSummary> result = AssertionResultMapper.computeRunSummaries(items);

        assertThat(result).hasSize(1);
        var summary = result.get(experimentId.toString());
        assertThat(summary.passedRuns()).isEqualTo(2);
        assertThat(summary.totalRuns()).isEqualTo(3);
        assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void computeRunSummaries_passThresholdMet_statusPassed() {
        var experimentId = UUID.randomUUID();
        var policy = ExecutionPolicy.builder().runsPerItem(3).passThreshold(2).build();
        var items = List.of(
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(false).build()))
                        .status(RunStatus.FAILED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build());

        Map<String, ExperimentRunSummary> result = AssertionResultMapper.computeRunSummaries(items);

        assertThat(result).hasSize(1);
        var summary = result.get(experimentId.toString());
        assertThat(summary.passedRuns()).isEqualTo(2);
        assertThat(summary.totalRuns()).isEqualTo(3);
        assertThat(summary.status()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void computeRunSummaries_regularExperimentItems_returnsNull() {
        var items = List.of(
                baseItem().build(),
                baseItem().build());

        var result = AssertionResultMapper.computeRunSummaries(items);

        assertThat(result).isNull();
    }

    private static ExperimentItem.ExperimentItemBuilder baseItem() {
        return ExperimentItem.builder()
                .id(UUID.randomUUID())
                .experimentId(UUID.randomUUID())
                .datasetItemId(UUID.randomUUID())
                .traceId(UUID.randomUUID());
    }

    private static FeedbackScore regularScore(String name, BigDecimal value) {
        return FeedbackScore.builder()
                .name(name)
                .value(value)
                .source(ScoreSource.SDK)
                .build();
    }

    private static FeedbackScore assertionScore(String name, BigDecimal value, String reason) {
        return FeedbackScore.builder()
                .name(name)
                .categoryName(AssertionResultMapper.SUITE_ASSERTION_CATEGORY)
                .value(value)
                .reason(reason)
                .source(ScoreSource.SDK)
                .build();
    }
}
