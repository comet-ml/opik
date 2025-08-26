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
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QueueItem(
        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Queue item ID", accessMode = Schema.AccessMode.READ_ONLY)
        UUID id,

        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Queue ID", accessMode = Schema.AccessMode.READ_ONLY)
        UUID queueId,

        @JsonView({QueueItem.View.Public.class, QueueItem.View.Write.class})
        @Schema(description = "Item type", example = "trace")
        @NotNull
        QueueItemType itemType,

        @JsonView({QueueItem.View.Public.class, QueueItem.View.Write.class})
        @Schema(description = "Item ID (trace_id or thread_id)", example = "trace_123")
        @NotBlank
        String itemId,

        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Item status", example = "pending")
        QueueItemStatus status,

        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Assigned SME user ID")
        String assignedSme,

        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Item creation timestamp", accessMode = Schema.AccessMode.READ_ONLY)
        Instant createdAt,

        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Item completion timestamp", accessMode = Schema.AccessMode.READ_ONLY)
        Instant completedAt,

        // Embedded trace/thread data for SME view
        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Trace data (when item_type is trace)", accessMode = Schema.AccessMode.READ_ONLY)
        Trace traceData,

        @JsonView({QueueItem.View.Public.class})
        @Schema(description = "Thread data (when item_type is thread)", accessMode = Schema.AccessMode.READ_ONLY)
        TraceThread threadData
) {

    public static class View {
        public static class Public {}
        public static class Write {}
    }

    public enum QueueItemType {
        TRACE("trace"),
        THREAD("thread");

        private final String value;

        QueueItemType(String value) {
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

    public enum QueueItemStatus {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed"),
        SKIPPED("skipped");

        private final String value;

        QueueItemStatus(String value) {
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