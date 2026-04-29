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

        assertThat(result).isEqualTo(item);
    }

    @Test
    void enrichWithAssertions_blankJson_returnsUnchanged() {
        var item = baseItem().build();

        var result = AssertionResultMapper.enrichWithAssertions(item, "  ");

        assertThat(result).isEqualTo(item);
    }

    @Test
    void enrichWithAssertions_emptyArray_returnsUnchanged() {
        var item = baseItem().build();

        var result = AssertionResultMapper.enrichWithAssertions(item, "[]");

        assertThat(result).isEqualTo(item);
    }

    @Test
    void enrichWithAssertions_allAssertionsPass_statusPassed() {
        var item = baseItem().build();
        var json = """
                [{"value":"Should link to docs","passed":"passed","reason":"Links found"},
                 {"value":"Should be concise","passed":"passed","reason":"Under 200 words"}]
                """;

        var result = AssertionResultMapper.enrichWithAssertions(item, json);

        var expected = item.toBuilder()
                .assertionResults(List.of(
                        AssertionResult.builder().value("Should link to docs").passed(true).reason("Links found")
                                .build(),
                        AssertionResult.builder().value("Should be concise").passed(true).reason("Under 200 words")
                                .build()))
                .status(RunStatus.PASSED)
                .build();
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void enrichWithAssertions_someAssertionsFail_statusFailed() {
        var item = baseItem().build();
        var json = """
                [{"value":"Should link to docs","passed":"passed","reason":"Links found"},
                 {"value":"Should be concise","passed":"failed","reason":"Too long"}]
                """;

        var result = AssertionResultMapper.enrichWithAssertions(item, json);

        var expected = item.toBuilder()
                .assertionResults(List.of(
                        AssertionResult.builder().value("Should link to docs").passed(true).reason("Links found")
                                .build(),
                        AssertionResult.builder().value("Should be concise").passed(false).reason("Too long").build()))
                .status(RunStatus.FAILED)
                .build();
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void enrichWithAssertions_allAssertionsFail_statusFailed() {
        var item = baseItem().build();
        var json = """
                [{"value":"Should link to docs","passed":"failed","reason":"No links found"}]
                """;

        var result = AssertionResultMapper.enrichWithAssertions(item, json);

        var expected = item.toBuilder()
                .assertionResults(List.of(
                        AssertionResult.builder().value("Should link to docs").passed(false).reason("No links found")
                                .build()))
                .status(RunStatus.FAILED)
                .build();
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void computeRunSummaries_singleRunWithAssertions_returnsSummary() {
        var item = baseItem()
                .assertionResults(List.of(AssertionResult.builder().value("a").passed(true).build()))
                .status(RunStatus.PASSED)
                .build();

        var result = AssertionResultMapper.computeRunSummaries(List.of(item));

        assertThat(result).isEqualTo(Map.of(
                item.experimentId().toString(),
                new ExperimentRunSummary(1, 1, RunStatus.PASSED)));
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

        assertThat(result).isEqualTo(Map.of(
                experimentId.toString(),
                new ExperimentRunSummary(2, 3, RunStatus.PASSED)));
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

        assertThat(result).isEqualTo(Map.of(
                experimentId.toString(),
                new ExperimentRunSummary(2, 3, RunStatus.FAILED)));
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

        assertThat(result).isEqualTo(Map.of(
                experimentId.toString(),
                new ExperimentRunSummary(2, 3, RunStatus.PASSED)));
    }

    @Test
    void computeRunSummaries_noAssertionResults_returnsNull() {
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
