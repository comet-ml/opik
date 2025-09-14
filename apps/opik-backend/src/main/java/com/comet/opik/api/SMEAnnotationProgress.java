package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SMEAnnotationProgress(
        @JsonView( {
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String workspaceId,

        @JsonView({View.Public.class}) @NotNull UUID queueId,

        @JsonView({View.Public.class}) @NotBlank String smeIdentifier,

        @JsonView({View.Public.class}) @NotNull UUID itemId,

        @JsonView({View.Public.class}) @NotNull AnnotationQueueItemType itemType,

        @JsonView({View.Public.class}) @NotNull SMEAnnotationStatus status,

        @JsonView({View.Public.class}) @Nullable List<FeedbackScore> feedbackScores,

        @JsonView({View.Public.class}) @Nullable String comment,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant createdAt,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String createdBy,

        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastUpdatedAt,

        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String lastUpdatedBy){

    public static class View {
        public interface Public {
        }
    }

    @Builder(toBuilder = true)
    public record SMEProgressSummary(
            int totalItems,
            int completedItems,
            int skippedItems,
            int pendingItems,
            @Nullable UUID nextItemId,
            @Nullable AnnotationQueueItemType nextItemType,
            double completionPercentage) {
    }
}
