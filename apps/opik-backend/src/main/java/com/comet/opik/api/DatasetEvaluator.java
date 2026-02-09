package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetEvaluator(
        @JsonView( {
                View.Public.class, View.Write.class}) UUID id,
        @JsonView({View.Public.class, View.Write.class}) @NotNull UUID datasetId,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank @Size(max = 255) String name,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank @Size(max = 100) String metricType,
        @JsonView({View.Public.class, View.Write.class}) @NotNull JsonNode metricConfig,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DatasetEvaluatorPage(
            @JsonView( {
                    View.Public.class}) List<DatasetEvaluator> content,
            @JsonView({View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total) implements Page<DatasetEvaluator>{

        public static DatasetEvaluatorPage empty(int page) {
            return new DatasetEvaluatorPage(List.of(), page, 0, 0);
        }
    }

    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DatasetEvaluatorBatchRequest(
            @NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid DatasetEvaluatorCreate> evaluators) {
    }

    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DatasetEvaluatorCreate(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 100) String metricType,
            @NotNull JsonNode metricConfig) {
    }
}
