package com.comet.opik.api.sorting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;
import java.util.regex.Pattern;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SortingField(
        @NotBlank String field,
        Direction direction,
        // bindKeyParam feeds a SQL parameter placeholder name (see bindKey()), so it is an internal
        // value derived server-side only: @JsonIgnore keeps it off the JSON API surface entirely (neither
        // serialized nor deserialized). It is null for static (non-dynamic) fields and a server-generated
        // safe identifier for dynamic fields (see the canonical constructor).
        @JsonIgnore @Nullable String bindKeyParam) {

    private static final Pattern SAFE_BIND_KEY_PARAM = Pattern.compile("[A-Za-z0-9_]+");

    // Canonical constructor. bindKeyParam is rendered into a SQL placeholder name, so for dynamic
    // fields it must always be a server-generated safe identifier. Regenerate it whenever it is
    // missing or is not a safe identifier, so no client-influenced value can reach the SQL.
    public SortingField {
        if (field != null && field.contains(".")
                && (bindKeyParam == null || !SAFE_BIND_KEY_PARAM.matcher(bindKeyParam).matches())) {
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
