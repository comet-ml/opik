package com.comet.opik.domain.sorting;

import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class SortingQueryBuilder {
    public String toOrderBySql(@NonNull List<SortingField> sorting) {
        if (sorting.isEmpty()) {
            return null;
        }

        String sortFields = sorting.stream()
                .map(sortingField -> {

                    // Handle null direction for dynamic fields
                    if (sortingField.handleNullDirection().isEmpty()) {
                        return "%s %s".formatted(sortingField.dbField(), getDirection(sortingField));
                    } else {
                        return "(%s, %s) %s".formatted(sortingField.dbField(), sortingField.handleNullDirection(),
                                getDirection(sortingField));
                    }
                })
                .collect(Collectors.joining(", "));

        // Add secondary sort by id DESC for deterministic ordering
        // This ensures consistent results when the primary sort field has duplicate values
        return sortFields + ", id DESC";
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
