package com.comet.opik.domain.sorting;

import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class SortingQueryBuilder {
    private static final String JSON_EXTRACT_RAW_PREFIX = "JSONExtractRaw(";
    private static final String EXPERIMENT_METRICS_PREFIX = "experiment_scores.";

    public String toOrderBySql(@NonNull List<SortingField> sorting) {
        return toOrderBySql(sorting, null);
    }

    public String toOrderBySql(@NonNull List<SortingField> sorting, Map<String, String> fieldMapping) {
        if (sorting.isEmpty()) {
            return null;
        }

        Function<SortingField, String> fieldMapper = fieldMapping != null
                ? sortingField -> fieldMapping.getOrDefault(sortingField.field(), getDbField(sortingField))
                : sortingField -> getDbField(sortingField);

        return sorting.stream()
                .map(sortingField -> {
                    String dbField = fieldMapper.apply(sortingField);

                    // Skip null handling for JSONExtractRaw fields and experiment_scores fields (they're JSON strings/arrays, not maps)
                    boolean isJsonExtract = dbField.startsWith(JSON_EXTRACT_RAW_PREFIX)
                            || sortingField.field().startsWith(EXPERIMENT_METRICS_PREFIX);

                    // Handle null direction for dynamic fields (unless it's a JSON extract)
                    if (sortingField.handleNullDirection().isEmpty() || isJsonExtract) {
                        return "%s %s".formatted(dbField, getDirection(sortingField));
                    } else {
                        return "(%s, %s) %s".formatted(dbField, sortingField.handleNullDirection(),
                                getDirection(sortingField));
                    }
                })
                .collect(Collectors.joining(", "));
    }

    private String getDbField(SortingField sortingField) {
        // Handle experiment_scores.* fields - extract from JSON array
        if (sortingField.field().startsWith(EXPERIMENT_METRICS_PREFIX) && sortingField.isDynamic()) {
            String bindKey = sortingField.bindKey();
            // Extract value from experiment_scores JSON array where name matches the key
            // experiment_scores is stored as a JSON string array: [{"name": "metric1", "value": 0.5}, ...]
            // We filter the array to find the object with matching name, then extract its value
            // Use ifNull with toFloat64OrNull to handle cases where experiment doesn't have the specific score
            // toFloat64OrNull returns NULL on parse failure instead of throwing an error
            return String.format(
                    "ifNull(toFloat64OrNull(JSON_VALUE(arrayFirst(x -> JSON_VALUE(x, '$.name') == :%s, JSONExtractArrayRaw(e.experiment_scores)), '$.value')), 0)",
                    bindKey);
        }
        return sortingField.dbField();
    }

    public boolean hasDynamicKeys(@NonNull List<SortingField> sorting) {
        // Only fields with bindKeyParam need dynamic binding
        // Fields without bindKeyParam use literal keys in field mappings (e.g., JSONExtractRaw)
        return sorting.stream()
                .filter(SortingField::isDynamic)
                .anyMatch(field -> field.bindKeyParam() != null);
    }

    public Statement bindDynamicKeys(Statement statement, List<SortingField> sorting) {
        sorting.stream()
                .filter(SortingField::isDynamic)
                .filter(sortingField -> sortingField.bindKeyParam() != null)
                .forEach(sortingField -> {
                    try {
                        statement.bind(sortingField.bindKey(), sortingField.dynamicKey());
                    } catch (Exception e) {
                        log.warn("Failed to bind dynamic key for sorting field: {}", sortingField, e);
                    }
                });

        return statement;
    }

    private Direction getDirection(SortingField sortingField) {
        return Optional.ofNullable(sortingField.direction()).orElse(Direction.ASC);
    }
}
