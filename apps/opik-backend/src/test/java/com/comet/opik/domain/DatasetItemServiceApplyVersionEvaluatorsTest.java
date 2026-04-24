package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("DatasetItemService applyVersionEvaluators:")
class DatasetItemServiceApplyVersionEvaluatorsTest {

    private final DatasetItemServiceImpl service = new DatasetItemServiceImpl(
            mock(DatasetItemDAO.class),
            mock(DatasetItemVersionDAO.class),
            mock(DatasetService.class),
            mock(DatasetVersionService.class),
            mock(ExperimentDAO.class),
            mock(TraceService.class),
            mock(SpanService.class),
            mock(TraceEnrichmentService.class),
            mock(SpanEnrichmentService.class),
            mock(IdGenerator.class),
            mock(SortingFactoryDatasets.class),
            mock(TransactionTemplate.class),
            mock(FeatureFlags.class),
            mock(DatasetVersioningMigrationService.class),
            mock(ProjectService.class),
            mock(OpikConfiguration.class));

    private static final EvaluatorItem VERSION_EVALUATOR = EvaluatorItem.builder()
            .name("Should be in English")
            .type(EvaluatorType.LLM_JUDGE)
            .config(JsonUtils.getJsonNodeFromString("{\"schema\":[{\"name\":\"english\",\"type\":\"boolean\"}]}"))
            .build();

    private static final EvaluatorItem ITEM_EVALUATOR = EvaluatorItem.builder()
            .name("Is factual")
            .type(EvaluatorType.LLM_JUDGE)
            .config(JsonUtils.getJsonNodeFromString("{\"schema\":[{\"name\":\"factual\",\"type\":\"boolean\"}]}"))
            .build();

    @Test
    @DisplayName("item with no evaluators gets version evaluators")
    void applyVersionEvaluatorsWhenItemHasNoEvaluators() {
        var item = DatasetItem.builder().build();

        var result = service.applyVersionEvaluators(item, List.of(VERSION_EVALUATOR));

        assertThat(result.evaluators()).containsExactly(VERSION_EVALUATOR);
    }

    @Test
    @DisplayName("item with null evaluators gets version evaluators")
    void applyVersionEvaluatorsWhenItemHasNullEvaluators() {
        var item = DatasetItem.builder().evaluators(null).build();

        var result = service.applyVersionEvaluators(item, List.of(VERSION_EVALUATOR));

        assertThat(result.evaluators()).containsExactly(VERSION_EVALUATOR);
    }

    @Test
    @DisplayName("item with its own evaluators gets both merged (version first, then item)")
    void applyVersionEvaluatorsWhenItemHasOwnEvaluators() {
        var item = DatasetItem.builder().evaluators(List.of(ITEM_EVALUATOR)).build();

        var result = service.applyVersionEvaluators(item, List.of(VERSION_EVALUATOR));

        assertThat(result.evaluators()).containsExactly(VERSION_EVALUATOR, ITEM_EVALUATOR);
    }

    @Test
    @DisplayName("item with empty evaluators list gets version evaluators")
    void applyVersionEvaluatorsWhenItemHasEmptyEvaluatorsList() {
        var item = DatasetItem.builder().evaluators(List.of()).build();

        var result = service.applyVersionEvaluators(item, List.of(VERSION_EVALUATOR));

        assertThat(result.evaluators()).containsExactly(VERSION_EVALUATOR);
    }

    @Test
    @DisplayName("multiple version evaluators are all included")
    void applyVersionEvaluatorsWithMultipleVersionEvaluators() {
        var secondVersionEval = EvaluatorItem.builder()
                .name("Is concise")
                .type(EvaluatorType.LLM_JUDGE)
                .config(JsonUtils.getJsonNodeFromString("{\"schema\":[]}"))
                .build();
        var item = DatasetItem.builder().evaluators(List.of(ITEM_EVALUATOR)).build();

        var result = service.applyVersionEvaluators(item, List.of(VERSION_EVALUATOR, secondVersionEval));

        assertThat(result.evaluators()).containsExactly(VERSION_EVALUATOR, secondVersionEval, ITEM_EVALUATOR);
    }

    @Test
    @DisplayName("non-evaluator fields are preserved")
    void applyVersionEvaluatorsPreservesOtherFields() {
        var data = Map.of("input", JsonUtils.getJsonNodeFromString("{\"q\":\"hello\"}"));
        var item = DatasetItem.builder()
                .data(data)
                .source(DatasetItemSource.MANUAL)
                .build();

        var result = service.applyVersionEvaluators(item, List.of(VERSION_EVALUATOR));

        assertThat(result.evaluators()).containsExactly(VERSION_EVALUATOR);
        assertThat(result.data()).isEqualTo(data);
        assertThat(result.source()).isEqualTo(DatasetItemSource.MANUAL);
    }
}
