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

        return sorting.stream()
                .map(sortingField -> "%s %s".formatted(sortingField.dbField(), getDirection(sortingField)))
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
