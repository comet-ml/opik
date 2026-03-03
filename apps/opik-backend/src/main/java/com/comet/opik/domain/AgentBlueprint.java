package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.comet.opik.api.validation.UniqueKeysValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@UniqueKeysValidation
public record AgentBlueprint(
        @JsonView( {
                AgentConfig.View.Public.class, AgentConfig.View.History.class,
                AgentConfig.View.Write.class}) UUID id,
        @Schema(hidden = true) UUID projectId,
        @JsonView({AgentConfig.View.Public.class, AgentConfig.View.History.class,
                AgentConfig.View.Write.class}) @NotNull BlueprintType type,
        @JsonView({AgentConfig.View.Public.class, AgentConfig.View.History.class,
                AgentConfig.View.Write.class}) @Size(max = 255, message = "cannot exceed 255 characters") String description,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<String> envs,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({AgentConfig.View.Public.class,
                AgentConfig.View.Write.class}) @Valid @NotNull @Size(min = 1, max = 250, message = "blueprint must have between 1 and 250 values") List<@NotNull AgentConfigValue> values){

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BlueprintPage(
            @JsonView( {
                    AgentConfig.View.History.class}) int page,
            @JsonView({AgentConfig.View.History.class}) int size,
            @JsonView({AgentConfig.View.History.class}) long total,
            @JsonView({AgentConfig.View.History.class}) List<AgentBlueprint> content)
            implements
                Page<AgentBlueprint>{

        public static BlueprintPage empty(int page) {
            return new BlueprintPage(page, 0, 0, List.of());
        }
    }

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum BlueprintType {
        BLUEPRINT("blueprint"),
        MASK("mask");

        @JsonValue
        private final String type;

        public static BlueprintType fromString(String type) {
            return Arrays.stream(values())
                    .filter(v -> v.type.equals(type))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown blueprint type: " + type));
        }
    }
}
