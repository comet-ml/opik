package com.comet.opik.api.sorting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SortingField(
        @NotBlank String field,
        Direction direction,
        String bindKeyParam) {

    // Canonical constructor with auto-generation of bindKeyParam for dynamic fields
    public SortingField {
        // Auto-generate bindKeyParam for dynamic fields if not provided
        if (bindKeyParam == null && field != null && field.contains(".")) {
            bindKeyParam = UUID.randomUUID().toString().replace("-", "");
        }
    }

    public SortingField(@NotBlank String field, Direction direction) {
        this(field, direction, null);
    }

    public String dbField() {
        if (isDynamic()) {
            return "%s[:%s]".formatted(fieldNamespace(), bindKey());
        }

        return field;
    }

    private String fieldNamespace() {
        return field.substring(0, field.indexOf('.'));
    }

    public String handleNullDirection() {
        if (isDynamic()) {
            return "mapContains(%s, :%s)".formatted(fieldNamespace(), bindKey());
        } else {
            return "";
        }
    }

    public String bindKey() {
        return "sorting_param_%s".formatted(bindKeyParam);
    }

    public String dynamicKey() {
        if (isDynamic()) {
            return field.substring(field.indexOf('.') + 1);
        }
        return "";
    }

    public boolean isDynamic() {
        return field.contains(".");
    }
}
