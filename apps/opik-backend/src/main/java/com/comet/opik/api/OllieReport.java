package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OllieReport(
        @JsonView(View.Public.class) @NonNull UUID id,
        @JsonView(View.Public.class) @NonNull UUID projectId,
        @JsonView(View.Public.class) String sessionId,
        @JsonView(View.Public.class) String content,
        @JsonView(View.Public.class) JsonNode recommendedActions,
        @JsonView(View.Public.class) @NonNull ReportStatus status,
        @JsonView(View.Public.class) String failureReason,
        @JsonView(View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView(View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt) {

    @RequiredArgsConstructor
    @Getter
    public enum ReportStatus {
        PENDING("pending"),
        COMPLETED("completed"),
        FAILED("failed");

        @JsonValue
        private final String value;

        @JsonCreator
        public static ReportStatus fromString(String value) {
            for (ReportStatus status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown report status: " + value);
        }
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OllieReportPage(
            @JsonView(View.Public.class) int page,
            @JsonView(View.Public.class) int size,
            @JsonView(View.Public.class) long total,
            @JsonView(View.Public.class) @NonNull List<OllieReport> content)
            implements
                Page<OllieReport> {

        public static OllieReportPage empty(int page) {
            return new OllieReportPage(page, 0, 0, List.of());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReportCompleteRequest(
            @JsonView(View.Write.class) String content,
            @JsonView(View.Write.class) @NotNull ReportStatus status,
            @JsonView(View.Write.class) String sessionId,
            @JsonView(View.Write.class) JsonNode recommendedActions,
            @JsonView(View.Write.class) @Size(max = 64) @Schema(description = "Why the report failed. Only "
                    + "'out_of_credits' is acted on; any other value is recorded but renders as a generic "
                    + "failure.") String failureReason) {
    }

    public static class FailureReason {
        // Pre-check (or a runtime 402) found the org can't afford the report. No pod was spun up.
        public static final String OUT_OF_CREDITS = "out_of_credits";
        // The two below are recorded by opik itself, never reported by a caller — hence absent from the request
        // schema above. They name a cause the metric leaves to its stage label, which the row has no room for.
        public static final String TRIGGER_FAILED = "trigger_failed";
        public static final String STALE_TIMEOUT = "stale_timeout";
    }

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
