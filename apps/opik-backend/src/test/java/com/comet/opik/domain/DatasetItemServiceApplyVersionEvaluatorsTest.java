package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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

    static Stream<Arguments> emptyEvaluatorVariants() {
        return Stream.of(
                Arguments.of("no evaluators set", DatasetItem.builder().build()),
                Arguments.of("null evaluators", DatasetItem.builder().evaluators(null).build()),
                Arguments.of("empty evaluators list", DatasetItem.builder().evaluators(List.of()).build()));
    }

    @ParameterizedTest(name = "item with {0} gets version evaluators")
    @MethodSource("emptyEvaluatorVariants")
    void applyVersionEvaluatorsWhenItemHasNoEvaluators(String description, DatasetItem item) {
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

    // --- per-version routing tests (applyVersionEvaluatorsToPage) ---

    private static final EvaluatorItem V2_EVALUATOR = EvaluatorItem.builder()
            .name("Is concise")
            .type(EvaluatorType.LLM_JUDGE)
            .config(JsonUtils.getJsonNodeFromString("{\"schema\":[{\"name\":\"concise\",\"type\":\"boolean\"}]}"))
            .build();

    @Test
    @DisplayName("items from different versions get their own version's evaluators")
    void applyVersionEvaluatorsToPageRoutesPerVersion() {
        var v1Id = UUID.randomUUID();
        var v2Id = UUID.randomUUID();

        var itemFromV1 = DatasetItem.builder().datasetVersionId(v1Id).build();
        var itemFromV2 = DatasetItem.builder().datasetVersionId(v2Id).build();

        var page = DatasetItemPage.builder()
                .content(List.of(itemFromV1, itemFromV2))
                .page(1).size(10).total(2).sortableBy(List.of())
                .build();

        var evaluatorsByVersion = Map.of(
                v1Id, List.of(VERSION_EVALUATOR),
                v2Id, List.of(V2_EVALUATOR));

        var result = service.applyVersionEvaluatorsToPage(page, evaluatorsByVersion);

        assertThat(result.content().get(0).evaluators()).containsExactly(VERSION_EVALUATOR);
        assertThat(result.content().get(1).evaluators()).containsExactly(V2_EVALUATOR);
    }

    @Test
    @DisplayName("item with unknown version gets no version evaluators")
    void applyVersionEvaluatorsToPageSkipsUnknownVersion() {
        var knownVersionId = UUID.randomUUID();
        var unknownVersionId = UUID.randomUUID();

        var itemKnown = DatasetItem.builder().datasetVersionId(knownVersionId).build();
        var itemUnknown = DatasetItem.builder().datasetVersionId(unknownVersionId)
                .evaluators(List.of(ITEM_EVALUATOR)).build();

        var page = DatasetItemPage.builder()
                .content(List.of(itemKnown, itemUnknown))
                .page(1).size(10).total(2).sortableBy(List.of())
                .build();

        var evaluatorsByVersion = Map.of(knownVersionId, List.of(VERSION_EVALUATOR));

        var result = service.applyVersionEvaluatorsToPage(page, evaluatorsByVersion);

        assertThat(result.content().get(0).evaluators()).containsExactly(VERSION_EVALUATOR);
        assertThat(result.content().get(1).evaluators()).containsExactly(ITEM_EVALUATOR);
    }

    @Test
    @DisplayName("item with null versionId gets no version evaluators")
    void applyVersionEvaluatorsToPageSkipsNullVersion() {
        var versionId = UUID.randomUUID();

        var itemWithVersion = DatasetItem.builder().datasetVersionId(versionId).build();
        var itemWithoutVersion = DatasetItem.builder().build();

        var page = DatasetItemPage.builder()
                .content(List.of(itemWithVersion, itemWithoutVersion))
                .page(1).size(10).total(2).sortableBy(List.of())
                .build();

        var evaluatorsByVersion = Map.of(versionId, List.of(VERSION_EVALUATOR));

        var result = service.applyVersionEvaluatorsToPage(page, evaluatorsByVersion);

        assertThat(result.content().get(0).evaluators()).containsExactly(VERSION_EVALUATOR);
        assertThat(result.content().get(1).evaluators()).isNull();
    }

    @Test
    @DisplayName("empty evaluator map returns page unchanged")
    void applyVersionEvaluatorsToPageEmptyMapReturnsUnchanged() {
        var item = DatasetItem.builder()
                .datasetVersionId(UUID.randomUUID())
                .evaluators(List.of(ITEM_EVALUATOR))
                .build();

        var page = DatasetItemPage.builder()
                .content(List.of(item))
                .page(1).size(10).total(1).sortableBy(List.of())
                .build();

        var result = service.applyVersionEvaluatorsToPage(page, Map.of());

        assertThat(result).isSameAs(page);
    }
}
