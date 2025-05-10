package com.comet.opik.domain;

import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.Column.ColumnType;
import static com.comet.opik.domain.CommentResultMapper.getComments;
import static com.comet.opik.domain.FeedbackScoreMapper.getFeedbackScores;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toMap;

class DatasetItemResultMapper {

    private static final int COMMENT_INDEX = 11;

    private DatasetItemResultMapper() {
    }

    static List<ExperimentItem> getExperimentItems(List[] experimentItemsArrays) {
        if (ArrayUtils.isEmpty(experimentItemsArrays)) {
            return null;
        }

        var experimentItems = Arrays.stream(experimentItemsArrays)
                .filter(experimentItem -> CollectionUtils.isNotEmpty(experimentItem) &&
                        !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentItem.get(2).toString()))
                .map(experimentItem -> ExperimentItem.builder()
                        .id(UUID.fromString(experimentItem.get(0).toString()))
                        .experimentId(UUID.fromString(experimentItem.get(1).toString()))
                        .datasetItemId(UUID.fromString(experimentItem.get(2).toString()))
                        .traceId(UUID.fromString(experimentItem.get(3).toString()))
                        .input(getJsonNodeOrNull(experimentItem.get(4)))
                        .output(getJsonNodeOrNull(experimentItem.get(5)))
                        .feedbackScores(getFeedbackScores(experimentItem.get(6)))
                        .createdAt(Instant.parse(experimentItem.get(7).toString()))
                        .lastUpdatedAt(Instant.parse(experimentItem.get(8).toString()))
                        .createdBy(experimentItem.get(9).toString())
                        .lastUpdatedBy(experimentItem.get(10).toString())
                        .comments(experimentItem.size() > COMMENT_INDEX ? getComments(experimentItem.get(11)) : null)
                        .duration((Double) experimentItem.get(12))
                        .totalEstimatedCost(getTotalEstimatedCost(experimentItem))
                        .usage(getUsage(experimentItem))
                        .build())
                .toList();

        return experimentItems.isEmpty() ? null : experimentItems;
    }

    private static BigDecimal getTotalEstimatedCost(List experimentItem) {
        return Optional.ofNullable(experimentItem.get(13))
                .map(value -> (BigDecimal) value)
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .orElse(null);
    }

    private static Map<String, Long> getUsage(List experimentItem) {
        return Optional.ofNullable(experimentItem.get(14))
                .map(value -> (Map<String, Long>) value)
                .filter(not(Map::isEmpty))
                .orElse(null);

    }

    static JsonNode getJsonNodeOrNull(Object field) {
        if (null == field || StringUtils.isBlank(field.toString())) {
            return null;
        }
        return JsonUtils.getJsonNodeFromString(field.toString());
    }

    static Map.Entry<Long, Set<Column>> groupResults(Map.Entry<Long, Set<Column>> result1,
            Map.Entry<Long, Set<Column>> result2) {

        return Map.entry(result1.getKey() + result2.getKey(), Sets.union(result1.getValue(), result2.getValue()));
    }

    private static Set<Column> mapColumnsField(Map<String, String[]> row, String filterField) {
        return Optional.ofNullable(row).orElse(Map.of())
                .entrySet()
                .stream()
                .map(columnArray -> Column.builder().name(columnArray.getKey())
                        .types(Arrays.stream(mapColumnType(columnArray.getValue())).collect(Collectors.toSet()))
                        .filterFieldPrefix(filterField)
                        .build())
                .collect(Collectors.toSet());
    }

    private static ColumnType[] mapColumnType(String[] values) {
        return Arrays.stream(values)
                .map(value -> switch (value) {
                    case "String" -> ColumnType.STRING;
                    case "Int64", "Float64", "UInt64", "Double" -> ColumnType.NUMBER;
                    case "Object" -> ColumnType.OBJECT;
                    case "Array" -> ColumnType.ARRAY;
                    case "Bool" -> ColumnType.BOOLEAN;
                    case "Null" -> ColumnType.NULL;
                    default -> ColumnType.NULL;
                })
                .toArray(ColumnType[]::new);
    }

    static Publisher<DatasetItem> mapItem(Result results) {
        return results.map((row, rowMetadata) -> {

            Map<String, JsonNode> data = getData(row);

            return DatasetItem.builder()
                    .id(row.get("id", UUID.class))
                    .data(data)
                    .source(DatasetItemSource.fromString(row.get("source", String.class)))
                    .traceId(Optional.ofNullable(row.get("trace_id", String.class))
                            .filter(s -> !s.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .spanId(Optional.ofNullable(row.get("span_id", String.class))
                            .filter(s -> !s.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .datasetId(Optional.ofNullable(row.get("dataset_id", String.class))
                            .filter(s -> !s.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .experimentItems(getExperimentItems(row.get("experiment_items_array", List[].class)))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdAt(row.get("created_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .build();
        });
    }

    private static Map<String, JsonNode> getData(Row row) {
        return Optional.ofNullable(row.get("data", Map.class))
                .filter(s -> !s.isEmpty())
                .map(value -> (Map<String, String>) value)
                .stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .map(entry -> Map.entry(entry.getKey(), JsonUtils.getJsonNodeFromString(entry.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Map<String, String> getOrDefault(Map<String, JsonNode> data) {
        return Optional.ofNullable(data)
                .filter(not(Map::isEmpty))
                .stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().toString()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static String getOrDefault(UUID value) {
        return Optional.ofNullable(value).map(UUID::toString).orElse("");
    }

    static Publisher<Map.Entry<Long, Set<Column>>> mapCountAndColumns(Result result, String filterFieldPrefix) {
        return result.map((row, rowMetadata) -> {
            Long count = extractCountFromResult(row);
            Map<String, String[]> columnsMap = extractColumnsField(row);
            return Map.entry(count, mapColumnsField(columnsMap, filterFieldPrefix));
        });
    }

    private static Long extractCountFromResult(Row row) {
        return row.get("count", Long.class);
    }

    private static Map<String, String[]> extractColumnsField(Row row) {
        return row.get("columns", Map.class);
    }

    static Publisher<Long> mapCount(Result result) {
        return result.map((row, rowMetadata) -> extractCountFromResult(row));
    }

    static Mono<Set<Column>> mapColumns(Result result, String filterFieldPrefix) {
        return Mono.from(result.map((row, rowMetadata) -> {
            Map<String, String[]> columnsMap = extractColumnsField(row);
            return DatasetItemResultMapper.mapColumnsField(columnsMap, filterFieldPrefix);
        }));
    }
}
