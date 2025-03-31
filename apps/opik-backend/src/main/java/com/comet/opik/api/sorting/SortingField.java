package com.comet.opik.api.sorting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Getter
@RequiredArgsConstructor
public class SortingField {

    private final @NotBlank String field;
    private final Direction direction;
    private final String bindKeyParam = UUID.randomUUID().toString().replace("-", "");

    public String field() {
        if (isDynamic()) {
            return "%s[:%s]".formatted(field.substring(0, field.indexOf('.')), bindKey());
        }

        return field;
    }

    public String rawField() {
        return field;
    }

    public Direction direction() {
        return direction;
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
