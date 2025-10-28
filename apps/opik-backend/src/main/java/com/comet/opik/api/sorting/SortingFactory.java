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

        // Hook for subclasses to process fields after deserialization
        sorting = processFields(sorting);

        validateFields(sorting);

        return sorting;
    }

    /**
     * Hook method for subclasses to process/transform sorting fields after deserialization.
     * Default implementation returns fields unchanged.
     *
     * @param sorting the sorting fields after JSON deserialization
     * @return processed sorting fields
     */
    protected List<SortingField> processFields(@NonNull List<SortingField> sorting) {
        return sorting;
    }

    protected List<SortingField> validateAndReturn(@NonNull List<SortingField> sorting) {
        validateFields(sorting);
        return sorting;
    }

    public abstract List<String> getSortableFields();

    private void validateFields(@NonNull List<SortingField> sorting) {
        if (sorting.isEmpty()) {
            return;
        }
        if (sorting.size() > 1) {
            throw new BadRequestException(ERR_MULTIPLE_SORTING);
        }

        List<String> illegalFields = sorting.stream()
                .map(SortingField::field)
                .filter(f -> !isFieldSupported(f) && !isDynamicFieldSupported(f))
                .toList();
        if (!illegalFields.isEmpty()) {
            throw new BadRequestException(
                    ERR_ILLEGAL_SORTING_FIELDS_TEMPLATE.formatted(StringUtils.join(illegalFields, ",")));
        }
    }

    private boolean isFieldSupported(String field) {
        return this.getSortableFields().contains(field);
    }

    private boolean isDynamicFieldSupported(String field) {
        if (field.contains(".")) {
            // Split at the first dot
            String[] parts = field.split("\\.", 2);
            String baseField = parts[0];
            String dynamicPart = parts.length > 1 ? parts[1] : "";

            // Dynamic part must not be empty
            if (dynamicPart.isEmpty()) {
                return false;
            }

            // Check if base field matches any supported dynamic field pattern
            return this.getSortableFields()
                    .stream()
                    .filter(supportedField -> supportedField.contains(".*"))
                    .anyMatch(supportedField -> {
                        String supportedBaseField = supportedField.substring(0, supportedField.indexOf(".*"));
                        return baseField.equals(supportedBaseField);
                    });
        }

        return false;
    }
}
