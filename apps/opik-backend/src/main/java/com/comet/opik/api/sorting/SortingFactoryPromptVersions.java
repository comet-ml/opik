package com.comet.opik.api.sorting;

import lombok.Getter;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
public class SortingFactoryPromptVersions extends SortingFactory {

    /**
     * Pattern with table alias as prefix for fields in prompt_versions in MySQL
     */
    public static final String PROMPT_VERSIONS_FIELDS_PATTERN = "pv.%s";

    private final List<String> sortableFields = List.of(
            SortableFields.ID,
            SortableFields.COMMIT,
            SortableFields.TEMPLATE,
            SortableFields.CHANGE_DESCRIPTION,
            SortableFields.TYPE,
            SortableFields.TAGS,
            SortableFields.CREATED_AT,
            SortableFields.CREATED_BY);

    public Map<String, String> newFieldMapping(@NonNull List<SortingField> sortingFields) {
        var fieldMapping = sortingFields.stream()
                .collect(Collectors.toUnmodifiableMap(
                        SortingField::field,
                        // Add the table alias as prefix to the db field name
                        field -> PROMPT_VERSIONS_FIELDS_PATTERN.formatted(field.dbField()),
                        // avoid exceptions on duplicate keys, the value is the same, doesn't matter which one
                        (existing, replacement) -> existing));
        // If no mappings, null is expected by SortingQueryBuilder.toOrderBySql
        return fieldMapping.isEmpty() ? null : fieldMapping;
    }
}
