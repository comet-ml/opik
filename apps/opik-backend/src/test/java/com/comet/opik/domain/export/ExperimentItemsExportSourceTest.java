package com.comet.opik.domain.export;

import com.comet.opik.api.AssertionResult;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsExportParams;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.RunStatus;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentItemsExportSourceTest {

    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID EXPERIMENT_ID = UUID.randomUUID();
    private static final UUID OTHER_EXPERIMENT_ID = UUID.randomUUID();

    @Mock
    private DatasetItemService datasetItemService;

    @Mock
    private ExperimentService experimentService;

    @InjectMocks
    private ExperimentItemsExportSource source;

    @Test
    void streamRows_shouldFlattenWithoutPrefix_whenSingleExperiment() {
        mockExperiments(experiment(EXPERIMENT_ID, "baseline"));
        mockPage(1, 100, List.of(item("what is 2+2?", experimentItem(EXPERIMENT_ID, "4"))), 1);

        List<SequencedMap<String, String>> rows = source
                .streamRows(params(EXPERIMENT_ID), 100)
                .collectList()
                .block();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("dataset.question", "what is 2+2?")
                .containsEntry("output", "4")
                .containsEntry("feedback_scores.relevance", "0.8")
                .containsEntry("feedback_scores.relevance_reason", "on topic")
                .containsEntry("assertion_1.name", "is numeric")
                .containsEntry("assertion_1.result", "passed")
                .containsEntry("usage.total_tokens", "42");
    }

    @Test
    void streamRows_shouldPrefixByExperimentName_whenComparing() {
        mockExperiments(experiment(EXPERIMENT_ID, "baseline"), experiment(OTHER_EXPERIMENT_ID, "candidate"));
        mockPage(1, 100, List.of(item("what is 2+2?",
                experimentItem(EXPERIMENT_ID, "4"),
                experimentItem(OTHER_EXPERIMENT_ID, "four"))), 1);

        SequencedMap<String, String> row = source
                .streamRows(params(EXPERIMENT_ID, OTHER_EXPERIMENT_ID), 100)
                .blockFirst();

        assertThat(row)
                .containsEntry("baseline.output", "4")
                .containsEntry("candidate.output", "four")
                .doesNotContainKey("output");
    }

    @Test
    void streamRows_shouldDisambiguateWithId_whenExperimentNamesCollide() {
        mockExperiments(experiment(EXPERIMENT_ID, "same"), experiment(OTHER_EXPERIMENT_ID, "same"));
        mockPage(1, 100, List.of(item("q", experimentItem(EXPERIMENT_ID, "a"))), 1);

        SequencedMap<String, String> row = source
                .streamRows(params(EXPERIMENT_ID, OTHER_EXPERIMENT_ID), 100)
                .blockFirst();

        assertThat(row).containsEntry("same(%s).output".formatted(EXPERIMENT_ID), "a");
    }

    @Test
    void streamRows_shouldPageUntilPartialPage() {
        mockExperiments(experiment(EXPERIMENT_ID, "baseline"));
        mockPage(1, 2, items(2, 0), 3);
        mockPage(2, 2, items(1, 2), 3);

        List<SequencedMap<String, String>> rows = source
                .streamRows(params(EXPERIMENT_ID), 2)
                .collectList()
                .block();

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(row -> row.get("dataset.question")))
                .containsExactly("question-0", "question-1", "question-2");
    }

    @Test
    void streamRows_shouldFail_whenFewerRowsStreamedThanReported() {
        mockExperiments(experiment(EXPERIMENT_ID, "baseline"));
        // The DAO turns some query failures into a short page; the reported total is what catches it.
        mockPage(1, 2, items(1, 0), 3);

        StepVerifier.create(source.streamRows(params(EXPERIMENT_ID), 2))
                .expectNextCount(1)
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("Export streamed 1 of 3 expected rows"))
                .verify();
    }

    @Test
    void discoverColumns_shouldUnionColumnsAcrossItems() {
        mockExperiments(experiment(EXPERIMENT_ID, "baseline"));
        var withRelevance = item("q1", experimentItem(EXPERIMENT_ID, "a1"));
        var withAccuracy = item("q2", experimentItem(EXPERIMENT_ID, "a2").toBuilder()
                .feedbackScores(List.of(score("accuracy", "1.0", null)))
                .assertionResults(List.of())
                .build());
        mockPage(1, 100, List.of(withRelevance, withAccuracy), 2);

        List<String> columns = source.discoverColumns(params(EXPERIMENT_ID)).block();

        assertThat(columns)
                .contains("dataset.question", "output", "feedback_scores.relevance", "feedback_scores.accuracy")
                .doesNotHaveDuplicates();
    }

    private void mockExperiments(Experiment... experiments) {
        for (Experiment experiment : experiments) {
            when(experimentService.getById(experiment.id())).thenReturn(Mono.just(experiment));
        }
    }

    private void mockPage(int page, int size, List<DatasetItem> content, long total) {
        when(datasetItemService.getItems(eq(page), eq(size), any(DatasetItemSearchCriteria.class)))
                .thenReturn(Mono.just(new DatasetItem.DatasetItemPage(content, page, content.size(), total,
                        java.util.Set.of(), List.of())));
    }

    private static ExperimentItemsExportParams params(UUID... experimentIds) {
        return ExperimentItemsExportParams.builder()
                .datasetId(DATASET_ID)
                .experimentIds(List.of(experimentIds))
                .build();
    }

    private static Experiment experiment(UUID id, String name) {
        return Experiment.builder().id(id).name(name).build();
    }

    private static List<DatasetItem> items(int count, int offset) {
        return IntStream.range(0, count)
                .mapToObj(index -> item("question-%d".formatted(offset + index),
                        experimentItem(EXPERIMENT_ID, "answer-%d".formatted(offset + index))))
                .toList();
    }

    private static DatasetItem item(String question, ExperimentItem... experimentItems) {
        return DatasetItem.builder()
                .id(UUID.randomUUID())
                .data(Map.of("question", JsonUtils.getJsonNodeFromString("\"%s\"".formatted(question))))
                .experimentItems(List.of(experimentItems))
                .build();
    }

    private static ExperimentItem experimentItem(UUID experimentId, String output) {
        return ExperimentItem.builder()
                .id(UUID.randomUUID())
                .experimentId(experimentId)
                .output(JsonUtils.getJsonNodeFromString("\"%s\"".formatted(output)))
                .duration(12.5)
                .totalEstimatedCost(new BigDecimal("0.01"))
                .usage(Map.of("total_tokens", 42L))
                .feedbackScores(List.of(score("relevance", "0.8", "on topic")))
                .assertionResults(List.of(AssertionResult.builder().value("is numeric").passed(true).build()))
                .status(RunStatus.PASSED)
                .build();
    }

    private static FeedbackScore score(String name, String value, String reason) {
        return FeedbackScore.builder().name(name).value(new BigDecimal(value)).reason(reason).build();
    }
}
