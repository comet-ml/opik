package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentConfigValue(
        @JsonView( {
                AgentConfig.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({AgentConfig.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID projectId,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.Write.class}) @NotBlank @Size(max = 255, message = "key cannot exceed 255 characters") String key,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.Write.class}) @NotBlank @Size(max = 512, message = "value cannot exceed 512 characters") String value,
        @JsonView({AgentConfig.View.Public.class, AgentConfig.View.Write.class}) @NotNull ValueType type,
        @JsonView({AgentConfig.View.Public.class, AgentConfig.View.Write.class}) String description,
        @JsonView({
                AgentConfig.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID validFromBlueprintId,
        @JsonView({
                AgentConfig.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID validToBlueprintId){

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum ValueType {
        STRING("string"),
        INTEGER("integer"),
        FLOAT("float"),
        BOOLEAN("boolean"),
        PROMPT("prompt"),
        PROMPT_COMMIT("prompt_commit");

        @JsonValue
        private final String type;

        @JsonCreator
        public static ValueType fromString(String type) {
            return Arrays.stream(values())
                    .filter(v -> v.type.equals(type))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown value type: " + type));
        }
    }
}
