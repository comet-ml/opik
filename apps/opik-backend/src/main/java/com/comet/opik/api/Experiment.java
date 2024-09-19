package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Experiment(
        @JsonView( {
                Experiment.View.Public.class, Experiment.View.Write.class}) UUID id,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) @NotBlank String datasetName,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) String name,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) JsonNode metadata,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScoreAverage> feedbackScores,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Long traceCount,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @Builder(toBuilder = true)
    public record ExperimentPage(
            @JsonView(Experiment.View.Public.class) int page,
            @JsonView(Experiment.View.Public.class) int size,
            @JsonView(Experiment.View.Public.class) long total,
            @JsonView(Experiment.View.Public.class) List<Experiment> content)
            implements
                Page<Experiment> {
        public static Experiment.ExperimentPage empty(int page) {
            return new Experiment.ExperimentPage(page, 0, 0, List.of());
        }
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
