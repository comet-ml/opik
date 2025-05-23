package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Optimization(
        @JsonView( {
                Optimization.View.Public.class, Optimization.View.Write.class}) UUID id,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) String name,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) @NotBlank String datasetName,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) @NotBlank String objectiveName,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) @NotNull OptimizationStatus status,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) JsonNode metadata,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Long numTrials,
        @JsonView({
                Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScoreAverage> feedbackScores,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) Instant lastUpdatedAt,
        @JsonView({
                Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @Builder(toBuilder = true)
    public record OptimizationPage(
            @JsonView(Optimization.View.Public.class) int page,
            @JsonView(Optimization.View.Public.class) int size,
            @JsonView(Optimization.View.Public.class) long total,
            @JsonView(Optimization.View.Public.class) List<Optimization> content,
            @JsonView(Optimization.View.Public.class) List<String> sortableBy)
            implements
                Page<Optimization> {

        public static OptimizationPage empty(int page, List<String> sortableBy) {
            return new OptimizationPage(page, 0, 0, List.of(), sortableBy);
        }
    }

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
