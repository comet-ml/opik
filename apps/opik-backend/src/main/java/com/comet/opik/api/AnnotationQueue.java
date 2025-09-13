package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueue(
        @JsonView( {
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String workspaceId,

        @JsonView({View.Public.class, View.Create.class, View.Update.class}) @NotNull UUID projectId,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String projectName,

        @JsonView({View.Public.class, View.Create.class, View.Update.class}) @NotBlank String name,

        @JsonView({View.Public.class, View.Create.class,
                View.Update.class}) @Nullable @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String description,

        @JsonView({View.Public.class, View.Create.class,
                View.Update.class}) @Nullable @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String instructions,

        @JsonView({View.Public.class, View.Create.class, View.Update.class}) @NotNull Boolean commentsEnabled,

        @JsonView({View.Public.class, View.Create.class, View.Update.class}) @NotNull List<UUID> feedbackDefinitions,

        @JsonView({View.Public.class, View.Create.class}) @NotNull AnnotationQueueScope scope,

        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable List<AnnotationQueueReviewer> reviewers,

        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable List<AggregatedFeedbackScore> feedbackScores,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long itemsCount,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant createdAt,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String createdBy,

        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastUpdatedAt,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String lastUpdatedBy,

        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastScoredAt,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable UUID shareToken,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Boolean isPublic){

    public static class View {
        public static class Public {
        }

        public static class Create {
        }

        public static class Update {
        }
    }

    public record AnnotationQueuePage(
            @JsonView( {
                    View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total,
            @JsonView({View.Public.class}) List<AnnotationQueue> content,
            @JsonView({View.Public.class}) List<String> sortableBy) implements Page<AnnotationQueue>{
    }
}
