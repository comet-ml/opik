package com.comet.opik.domain.sorting;

import com.comet.opik.api.sorting.SortingField;
import lombok.NonNull;

import java.util.List;
import java.util.stream.Collectors;

public class SortingQueryBuilder {
    public String toOrderBySql(@NonNull List<SortingField> sorting) {
        if (sorting.isEmpty()) {
            return null;
        }

        return sorting.stream()
                .map(sortingField -> {
                    String sortOrder = sortingField.desc() ? "DESC" : "ASC";
                    return "%s %s".formatted(sortingField.field(), sortOrder);
                })
                .collect(Collectors.joining(", "));
    }
}
