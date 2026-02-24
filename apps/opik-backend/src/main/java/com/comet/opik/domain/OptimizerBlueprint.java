package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
public record OptimizerBlueprint(
        @JsonView( {
                OptimizerConfig.View.Public.class,
                OptimizerConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        UUID projectId,
        @JsonView({OptimizerConfig.View.Public.class, OptimizerConfig.View.History.class,
                OptimizerConfig.View.Write.class}) @NotNull BlueprintType type,
        @JsonView({OptimizerConfig.View.Public.class, OptimizerConfig.View.History.class,
                OptimizerConfig.View.Write.class}) @Size(max = 255, message = "cannot exceed 255 characters") String description,
        @JsonView({OptimizerConfig.View.Public.class, OptimizerConfig.View.History.class}) List<String> envs,
        @JsonView({OptimizerConfig.View.Public.class,
                OptimizerConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({OptimizerConfig.View.Public.class,
                OptimizerConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({OptimizerConfig.View.Public.class,
                OptimizerConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({OptimizerConfig.View.Public.class,
                OptimizerConfig.View.History.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({OptimizerConfig.View.Public.class,
                OptimizerConfig.View.Write.class}) @Valid @NotEmpty List<OptimizerConfigValue> values){

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BlueprintPage(
            @JsonView({OptimizerConfig.View.History.class}) int page,
            @JsonView({OptimizerConfig.View.History.class}) int size,
            @JsonView({OptimizerConfig.View.History.class}) long total,
            @JsonView({OptimizerConfig.View.History.class}) List<OptimizerBlueprint> content)
            implements Page<OptimizerBlueprint> {

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
