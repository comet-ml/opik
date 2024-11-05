package com.comet.opik.api.sorting;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

public abstract class SortingFactory {
    public static final String ERR_INVALID_SORTING_PARAM_TEMPLATE = "Invalid sorting query parameter '%s'";
    public static final String ERR_ILLEGAL_SORTING_FIELDS_TEMPLATE = "Invalid sorting fields '%s'";
    public List<SortingField> newSorting(String queryParam) {
        if (StringUtils.isBlank(queryParam)) {
            return null;
        }

        List<SortingField> sorting;
        try {
            sorting = JsonUtils.readValue(queryParam, new TypeReference<>() {
            });
        } catch (UncheckedIOException exception) {
            throw new BadRequestException(ERR_INVALID_SORTING_PARAM_TEMPLATE.formatted(queryParam), exception);
        }

        if (sorting.isEmpty()) {
            return null;
        }

        validateFields(sorting);

        return sorting;
    }

    public String toOrderBySql(List<SortingField> sorting) {
        if (sorting == null || sorting.isEmpty()) {
            return null;
        }

        return sorting.stream()
                .map(sortingField -> {
                    String sortOrder = sortingField.desc() ? "DESC" : "ASC";
                    return "%s %s".formatted(sortingField.field(), sortOrder);
                })
                .collect(Collectors.joining(", "));
    }

    public abstract List<String> getSortableFields();

    private void validateFields(List<SortingField> sorting) {
        List<String> illegalFields = sorting.stream()
                .map(SortingField::field)
                .filter(f -> !this.getSortableFields().contains(f))
                .toList();
        if (!illegalFields.isEmpty()) {
            throw new BadRequestException(
                    ERR_ILLEGAL_SORTING_FIELDS_TEMPLATE.formatted(StringUtils.join(illegalFields, ",")));
        }
    }
}
