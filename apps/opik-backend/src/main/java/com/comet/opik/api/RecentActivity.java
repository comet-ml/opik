package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RecentActivity(List<RecentActivityItem> items) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecentActivityItem(
            ActivityType type,
            UUID id,
            String name,
            Instant createdAt,
            UUID resourceId) {

        public RecentActivityItem(ActivityType type, UUID id, String name, Instant createdAt) {
            this(type, id, name, createdAt, null);
        }
    }

    @Builder(toBuilder = true)
    public record RecentDatasetVersion(UUID datasetId, String datasetName, String datasetType, Instant createdAt) {
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
