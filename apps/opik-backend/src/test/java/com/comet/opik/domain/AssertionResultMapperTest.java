package com.comet.opik.domain;

import com.comet.opik.api.AssertionResult;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentRunSummary;
import com.comet.opik.api.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionResultMapperTest {

    @Test
    void enrichWithAssertions_nullJson_returnsUnchanged() {
        var item = baseItem().build();

        var result = AssertionResultMapper.enrichWithAssertions(item, null);

        assertThat(result.assertionResults()).isNull();
        assertThat(result.status()).isNull();
    }

    @Test
    void enrichWithAssertions_blankJson_returnsUnchanged() {
        var item = baseItem().build();

        var result = AssertionResultMapper.enrichWithAssertions(item, "  ");

        assertThat(result.assertionResults()).isNull();
        assertThat(result.status()).isNull();
    }

    @Test
    void enrichWithAssertions_allAssertionsPass_statusPassed() {
        var item = baseItem().build();
        var json = """
                [{"value":"Should link to docs","passed":"passed","reason":"Links found"},
                 {"value":"Should be concise","passed":"passed","reason":"Under 200 words"}]
                """;

        var result = AssertionResultMapper.enrichWithAssertions(item, json);

        assertThat(result.assertionResults()).hasSize(2);
        assertThat(result.assertionResults()).allMatch(AssertionResult::passed);
        assertThat(result.status()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void enrichWithAssertions_someAssertionsFail_statusFailed() {
        var item = baseItem().build();
        var json = """
                [{"value":"Should link to docs","passed":"passed","reason":"Links found"},
                 {"value":"Should be concise","passed":"failed","reason":"Too long"}]
                """;

        var result = AssertionResultMapper.enrichWithAssertions(item, json);

        assertThat(result.assertionResults()).hasSize(2);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.assertionResults().get(0).passed()).isTrue();
        assertThat(result.assertionResults().get(1).passed()).isFalse();
    }

    @Test
    void enrichWithAssertions_allAssertionsFail_statusFailed() {
        var item = baseItem().build();
        var json = """
                [{"value":"Should link to docs","passed":"failed","reason":"No links found"}]
                """;

        var result = AssertionResultMapper.enrichWithAssertions(item, json);

        assertThat(result.assertionResults()).hasSize(1);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void computeRunSummaries_singleRunWithAssertions_returnsSummary() {
        var item = baseItem()
                .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                .status(RunStatus.PASSED)
                .build();

        var result = AssertionResultMapper.computeRunSummaries(List.of(item));

        assertThat(result).hasSize(1);
        var summary = result.get(item.experimentId().toString());
        assertThat(summary.passedRuns()).isEqualTo(1);
        assertThat(summary.totalRuns()).isEqualTo(1);
        assertThat(summary.status()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void computeRunSummaries_multipleRuns_returnsSummary() {
        var experimentId = UUID.randomUUID();
        var items = List.of(
                baseItem().experimentId(experimentId)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build(),
                baseItem().experimentId(experimentId)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(false).build()))
                        .status(RunStatus.FAILED).build(),
                baseItem().experimentId(experimentId)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(true).build()))
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
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(false).build()))
                        .status(RunStatus.FAILED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(true).build()))
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
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(true).build()))
                        .status(RunStatus.PASSED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(false).build()))
                        .status(RunStatus.FAILED).build(),
                baseItem().experimentId(experimentId).executionPolicy(policy)
                        .assertionResults(
                                List.of(AssertionResult.builder().value("a").passed(true).build()))
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

}
