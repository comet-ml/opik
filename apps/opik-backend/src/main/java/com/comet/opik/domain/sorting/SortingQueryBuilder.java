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
    public String toOrderBySql(@NonNull List<SortingField> sorting) {
        return toOrderBySql(sorting, null);
    }

    public String toOrderBySql(@NonNull List<SortingField> sorting, Map<String, String> fieldMapping) {
        if (sorting.isEmpty()) {
            return null;
        }

        Function<SortingField, String> fieldMapper = fieldMapping != null
                ? sortingField -> fieldMapping.getOrDefault(sortingField.field(), sortingField.dbField())
                : SortingField::dbField;

        return sorting.stream()
                .map(sortingField -> {
                    String dbField = fieldMapper.apply(sortingField);

                    // Skip null handling for JSONExtractRaw fields (they're JSON strings, not maps)
                    boolean isJsonExtract = dbField.startsWith("JSONExtractRaw(");
                    
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

    public boolean hasDynamicKeys(@NonNull List<SortingField> sorting) {
        // JSON fields (output.*, input.*, metadata.*) are not considered dynamic
        // because they use JSONExtractRaw with literal keys, not bind parameters
        return sorting.stream()
                .filter(sortingField -> {
                    String field = sortingField.field();
                    return !field.startsWith("output.") 
                        && !field.startsWith("input.") 
                        && !field.startsWith("metadata.");
                })
                .anyMatch(SortingField::isDynamic);
    }

    public Statement bindDynamicKeys(Statement statement, List<SortingField> sorting) {
        sorting.stream()
                .filter(SortingField::isDynamic)
                .filter(sortingField -> {
                    // Skip JSON fields (output.*, input.*, metadata.*)
                    String field = sortingField.field();
                    return !field.startsWith("output.") 
                        && !field.startsWith("input.") 
                        && !field.startsWith("metadata.");
                })
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
