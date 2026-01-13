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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Experiment(
        @JsonView( {
                Experiment.View.Public.class, Experiment.View.Write.class}) UUID id,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) @NotBlank String datasetName,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID projectId,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) String name,
        @Schema(implementation = JsonListString.class) @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) JsonNode metadata,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags") @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tags,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) ExperimentType type,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) UUID optimizationId,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScoreAverage> feedbackScores,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<Comment> comments,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Long traceCount,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) PercentageValues duration,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCost,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCostAvg,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, Double> usage,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) ExperimentStatus status,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) List<@NotNull @Valid ExperimentScore> experimentScores,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @Schema(deprecated = true) PromptVersionLink promptVersion,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) List<PromptVersionLink> promptVersions,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @Schema(description = "ID of the dataset version this experiment is linked to. If not provided at creation, experiment will be automatically linked to the latest version.") UUID datasetVersionId,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Summary of the dataset version this experiment is linked to.") DatasetVersionSummary datasetVersionSummary){

    @Builder(toBuilder = true)
    public record ExperimentPage(
            @JsonView(Experiment.View.Public.class) int page,
            @JsonView(Experiment.View.Public.class) int size,
            @JsonView(Experiment.View.Public.class) long total,
            @JsonView(Experiment.View.Public.class) List<Experiment> content,
            @JsonView(Experiment.View.Public.class) List<String> sortableBy)
            implements
                Page<Experiment> {
        public static Experiment.ExperimentPage empty(int page, List<String> sortableBy) {
            return new Experiment.ExperimentPage(page, 0, 0, List.of(), sortableBy);
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PromptVersionLink(@JsonView( {
            Experiment.View.Public.class, Experiment.View.Write.class}) @NotNull UUID id,
            @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String commit,
            @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID promptId,
            @JsonView({
                    Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String promptName){
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
