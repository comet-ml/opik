package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.util.Set;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Column(String name, Set<ColumnType> types, String filterFieldPrefix) {

    @JsonProperty("filterField")
    @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "The field to use for filtering", name = "filterField")
    public String filterField() {
        return "%s.%s".formatted(filterFieldPrefix, name);
    }

    @RequiredArgsConstructor
    public enum ColumnType {
        STRING("string"),
        NUMBER("number"),
        OBJECT("object"),
        BOOLEAN("boolean"),
        ARRAY("array"),
        NULL("null");

        @JsonValue
        private final String value;
    }
}
