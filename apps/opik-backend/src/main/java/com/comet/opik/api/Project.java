package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
// This annotation is used to specify the strategy to be used for naming of properties for the annotated type. Required so that OpenAPI schema generation uses snake_case
// for property names
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Project(
        @JsonView( {
                Project.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({Project.View.Public.class, View.Write.class}) @NotBlank String name,
        @JsonView({Project.View.Public.class, View.Write.class}) Visibility visibility,
        @JsonView({Project.View.Public.class,
                View.Write.class}) String description,
        @JsonView({Project.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Project.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Project.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Project.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({
                Project.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastUpdatedTraceAt,
        @JsonView({
                Project.View.Detailed.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable List<FeedbackScoreAverage> feedbackScores,
        @JsonView({
                Project.View.Detailed.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable PercentageValues duration,
        @JsonView({
                Project.View.Detailed.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Double totalEstimatedCost,
        @JsonView({
                Project.View.Detailed.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Map<String, Double> usage,
        @JsonView({
                Project.View.Detailed.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long traceCount,
        @JsonView({
                Project.View.Detailed.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long guardrailsFailedCount){

    public static class View {
        public static class Write {
        }

        public static class Public {
        }

        public static class Detailed extends Public {
        }
    }

    public record ProjectPage(
            @JsonView( {
                    Project.View.Public.class}) int page,
            @JsonView({Project.View.Public.class}) int size,
            @JsonView({Project.View.Public.class}) long total,
            @JsonView({Project.View.Public.class}) List<Project> content,
            @JsonView({Project.View.Public.class}) List<String> sortableBy)
            implements
                com.comet.opik.api.Page<Project>{

        public static ProjectPage empty(int page) {
            return new ProjectPage(page, 0, 0, List.of(), List.of());
        }
    }
}
