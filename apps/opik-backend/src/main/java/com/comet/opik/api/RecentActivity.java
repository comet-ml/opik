package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class RecentActivity {

    public static class View {
        public static class Public {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecentActivityPage(
            @JsonView(View.Public.class) int page,
            @JsonView(View.Public.class) int size,
            @JsonView(View.Public.class) long total,
            @JsonView(View.Public.class) List<RecentActivityItem> content) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecentActivityItem(
            @JsonView(View.Public.class) ActivityType type,
            @JsonView(View.Public.class) UUID id,
            @JsonView(View.Public.class) String name,
            @JsonView(View.Public.class) UUID resourceId,
            @JsonView(View.Public.class) String createdBy,
            @JsonView(View.Public.class) Instant createdAt) {
    }

    @Builder(toBuilder = true)
    public record RecentDatasetVersion(@NonNull UUID datasetId, @NonNull String datasetName,
            @NonNull String datasetType, @NonNull Instant createdAt, @NonNull String createdBy) {
    }

    @RequiredArgsConstructor
    @Getter
    public enum ActivityType {
        EXPERIMENT("experiment"),
        DATASET_VERSION("dataset_version"),
        TEST_SUITE_VERSION("test_suite_version"),
        ALERT_EVENT("alert_event"),
        OPTIMIZATION("optimization"),
        AGENT_CONFIG_VERSION("agent_config_version");

        @JsonValue
        private final String value;
    }
}
