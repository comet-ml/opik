package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
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
public record AnnotationQueue(
        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Annotation queue ID", accessMode = Schema.AccessMode.READ_ONLY)
        UUID id,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Queue name", example = "Customer Support Q4 Review")
        @NotBlank
        String name,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Queue description", example = "Review customer support agent responses for Q4")
        String description,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Queue status", example = "active")
        AnnotationQueueStatus status,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "User ID who created the queue", accessMode = Schema.AccessMode.READ_ONLY)
        String createdBy,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Project ID", accessMode = Schema.AccessMode.READ_ONLY)
        UUID projectId,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Template ID for reusable configurations")
        UUID templateId,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Fields visible to SMEs", example = "[\"input\", \"output\", \"timestamp\"]")
        List<String> visibleFields,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Required annotation metrics", example = "[\"rating\"]")
        List<String> requiredMetrics,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Optional annotation metrics", example = "[\"comment\"]")
        List<String> optionalMetrics,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Instructions for SMEs")
        String instructions,

        @JsonView({AnnotationQueue.View.Public.class, AnnotationQueue.View.Write.class})
        @Schema(description = "Due date for queue completion")
        Instant dueDate,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Queue creation timestamp", accessMode = Schema.AccessMode.READ_ONLY)
        Instant createdAt,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Queue last update timestamp", accessMode = Schema.AccessMode.READ_ONLY)
        Instant updatedAt,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Total number of items in queue", accessMode = Schema.AccessMode.READ_ONLY)
        Integer totalItems,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Number of completed items", accessMode = Schema.AccessMode.READ_ONLY)
        Integer completedItems,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "List of assigned SME user IDs", accessMode = Schema.AccessMode.READ_ONLY)
        List<String> assignedSmes,

        @JsonView({AnnotationQueue.View.Public.class})
        @Schema(description = "Shareable URL for SME access", accessMode = Schema.AccessMode.READ_ONLY)
        String shareUrl
) {

    public static class View {
        public static class Public {}
        public static class Write {}
    }

    public enum AnnotationQueueStatus {
        ACTIVE("active"),
        COMPLETED("completed"),
        PAUSED("paused");

        private final String value;

        AnnotationQueueStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}