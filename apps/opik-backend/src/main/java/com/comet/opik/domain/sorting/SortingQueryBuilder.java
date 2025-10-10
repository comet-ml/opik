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

                    // Handle null direction for dynamic fields
                    if (sortingField.handleNullDirection().isEmpty()) {
                        return "%s %s".formatted(dbField, getDirection(sortingField));
                    } else {
                        return "(%s, %s) %s".formatted(dbField, sortingField.handleNullDirection(),
                                getDirection(sortingField));
                    }
                })
                .collect(Collectors.joining(", "));
    }

    public boolean hasDynamicKeys(@NonNull List<SortingField> sorting) {
        return sorting.stream().anyMatch(SortingField::isDynamic);
    }

    public Statement bindDynamicKeys(Statement statement, List<SortingField> sorting) {
        sorting.stream()
                .filter(SortingField::isDynamic)
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
