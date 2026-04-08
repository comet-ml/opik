package com.comet.opik.domain;

import com.comet.opik.api.DatasetType;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("DatasetItemService filterDataForDatasetType:")
class DatasetItemServiceFilterDataTest {

    private final DatasetItemServiceImpl service = new DatasetItemServiceImpl(
            mock(DatasetItemDAO.class),
            mock(DatasetItemVersionDAO.class),
            mock(DatasetService.class),
            mock(DatasetVersionService.class),
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

    private static final Map<String, JsonNode> ENRICHED_DATA = Map.of(
            "input", JsonUtils.getJsonNodeFromString("{\"topic\":\"Formula1\",\"lang\":\"en\"}"),
            "expected_output", JsonUtils.getJsonNodeFromString("{\"answer\":\"F1 is a sport\"}"),
            "metadata", JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"));

    static Stream<Arguments> filterDataForDatasetType() {
        return Stream.of(
                Arguments.of(
                        "EVALUATION_SUITE unwraps input fields as top-level data",
                        DatasetType.EVALUATION_SUITE,
                        ENRICHED_DATA,
                        Map.of(
                                "topic", JsonUtils.getJsonNodeFromString("\"Formula1\""),
                                "lang", JsonUtils.getJsonNodeFromString("\"en\""))),
                Arguments.of(
                        "DATASET preserves original data",
                        DatasetType.DATASET,
                        ENRICHED_DATA,
                        ENRICHED_DATA),
                Arguments.of(
                        "EVALUATION_SUITE with missing input returns empty map",
                        DatasetType.EVALUATION_SUITE,
                        Map.of("expected_output",
                                JsonUtils.getJsonNodeFromString("{\"answer\":\"F1 is a sport\"}")),
                        Map.<String, JsonNode>of()),
                Arguments.of(
                        "EVALUATION_SUITE with string input wraps under input key",
                        DatasetType.EVALUATION_SUITE,
                        Map.of("input", JsonUtils.getJsonNodeFromString("\"just a string\"")),
                        Map.of("input", JsonUtils.getJsonNodeFromString("\"just a string\""))),
                Arguments.of(
                        "EVALUATION_SUITE with array input wraps under input key",
                        DatasetType.EVALUATION_SUITE,
                        Map.of("input", JsonUtils.getJsonNodeFromString("[{\"role\":\"user\",\"content\":\"hello\"}]")),
                        Map.of("input",
                                JsonUtils.getJsonNodeFromString("[{\"role\":\"user\",\"content\":\"hello\"}]"))),
                Arguments.of(
                        "EVALUATION_SUITE with numeric input wraps under input key",
                        DatasetType.EVALUATION_SUITE,
                        Map.of("input", JsonUtils.getJsonNodeFromString("42")),
                        Map.of("input", JsonUtils.getJsonNodeFromString("42"))),
                Arguments.of(
                        "EVALUATION_SUITE with boolean input wraps under input key",
                        DatasetType.EVALUATION_SUITE,
                        Map.of("input", JsonUtils.getJsonNodeFromString("true")),
                        Map.of("input", JsonUtils.getJsonNodeFromString("true"))),
                Arguments.of(
                        "EVALUATION_SUITE with null input returns empty map",
                        DatasetType.EVALUATION_SUITE,
                        Map.of("input", JsonUtils.getJsonNodeFromString("null")),
                        Map.<String, JsonNode>of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void filterDataForDatasetType(String description, DatasetType datasetType,
            Map<String, JsonNode> input, Map<String, JsonNode> expected) {
        var result = service.filterDataForDatasetType(input, datasetType);
        assertThat(result).isEqualTo(expected);
    }
}
