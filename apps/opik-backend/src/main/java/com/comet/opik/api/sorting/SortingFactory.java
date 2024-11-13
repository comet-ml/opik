package com.comet.opik.api.sorting;

import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public abstract class SortingFactory {
    public static final String ERR_INVALID_SORTING_PARAM_TEMPLATE = "Invalid sorting query parameter '%s'";
    public static final String ERR_ILLEGAL_SORTING_FIELDS_TEMPLATE = "Invalid sorting fields '%s'";
    public static final String ERR_MULTIPLE_SORTING = "Sorting by multiple fields is currently not supported";

    public List<SortingField> newSorting(String queryParam) {
        List<SortingField> sorting = new ArrayList<>();

        if (StringUtils.isBlank(queryParam)) {
            return sorting;
        }

        try {
            sorting = JsonUtils.readCollectionValue(queryParam, List.class, SortingField.class);
        } catch (UncheckedIOException exception) {
            throw new BadRequestException(ERR_INVALID_SORTING_PARAM_TEMPLATE.formatted(queryParam), exception);
        }

        validateFields(sorting);

        return sorting;
    }

    public abstract List<String> getSortableFields();

    private void validateFields(@NonNull List<SortingField> sorting) {
        if (sorting.isEmpty()) {
            return;
        }

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
