package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A model has no status of its own: its state is its runs. Its serving run is its most recent COMPLETED one, null until
 * one completes, which is what makes the model usable in a rule; its latest run is its most recent one, whose status
 * the Models tab shows. The database row carries their ids; the services fill in the runs.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(accessMode = Schema.AccessMode.READ_ONLY)
public record GuardrailModel(
        UUID id,
        @Schema(description = "Unique in the workspace, ignoring case; never changes") String name,
        @Schema(description = "What the model flags: the question its adapter is trained and served on") String description,
        @JsonIgnore UUID servingRunId,
        @JsonIgnore UUID latestRunId,
        @Schema(description = "The run checks with the model are served by, its latest completed one; null until one completes, and a model without one cannot be used in a rule") @Nullable GuardrailTrainingRun servingRun,
        @Schema(description = "The model's latest run, whose status is the model's") @Nullable GuardrailTrainingRun latestRun,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) {

    public static final int NAME_MAX_LENGTH = 255;
    public static final int DESCRIPTION_MAX_LENGTH = 1000;

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GuardrailModelPage(
            List<GuardrailModel> content,
            int page,
            int size,
            long total,
            List<String> sortableBy) implements Page<GuardrailModel> {
    }
}
