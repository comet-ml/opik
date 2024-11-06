package com.comet.opik.domain.sorting;

import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SortingQueryBuilder {
    public String toOrderBySql(@NonNull List<SortingField> sorting) {
        if (sorting.isEmpty()) {
            return null;
        }

        return sorting.stream()
                .map(sortingField -> {
                    return "%s %s".formatted(sortingField.field(), getDirection(sortingField));
                })
                .collect(Collectors.joining(", "));
    }

    private Direction getDirection(SortingField sortingField) {
        return Optional.ofNullable(sortingField.direction()).orElse(Direction.ASC);
    }
}
