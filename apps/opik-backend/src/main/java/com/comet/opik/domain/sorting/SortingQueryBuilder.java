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

                    // Skip null handling for JSONExtractRaw fields (they're JSON strings, not maps)
                    boolean isJsonExtract = dbField.startsWith(JSON_EXTRACT_RAW_PREFIX);

                    // Handle null direction for dynamic fields (unless it's a JSON extract)
                    if (sortingField.handleNullDirection().isEmpty() || isJsonExtract) {
                        return "%s %s".formatted(dbField, getDirection(sortingField));
                    } else {
                        String nullDirection = transformNullDirection(sortingField);
                        return "(%s, %s) %s".formatted(dbField, nullDirection,
                                getDirection(sortingField));
                    }
                })
                .collect(Collectors.joining(", "));
    }

    private String getDbField(SortingField sortingField) {
        // Handle experiment_scores.* fields - use the output column alias 'experiment_scores_agg'
        // which is the column exposed by both branches of the UNION ALL in the FIND query
        if (sortingField.field().startsWith(EXPERIMENT_METRICS_PREFIX) && sortingField.isDynamic()) {
            String bindKey = sortingField.bindKey();
            return String.format(
                    "coalesce(experiment_scores_agg[:%s], 0)",
                    bindKey);
        }
        return sortingField.dbField();
    }

    private String transformNullDirection(SortingField sortingField) {
        if (sortingField.field().startsWith(EXPERIMENT_METRICS_PREFIX) && sortingField.isDynamic()) {
            String bindKey = sortingField.bindKey();
            return "mapContains(experiment_scores_agg, :%s)".formatted(bindKey);
        }
        return sortingField.handleNullDirection();
    }

    public boolean hasDynamicKeys(@NonNull List<SortingField> sorting) {
        // A dynamic field is bound whenever it carries a bindKeyParam. This covers JSON fields
        // (output/input/metadata), whose key is bound via JSONExtractRaw(col, :param), as well as
        // map-style fields such as data[:param] and experiment_scores_agg[:param].
        return sorting.stream()
                .filter(SortingField::isDynamic)
                .anyMatch(field -> field.bindKeyParam() != null);
    }

    public Statement bindDynamicKeys(Statement statement, @NonNull List<SortingField> sorting) {
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
