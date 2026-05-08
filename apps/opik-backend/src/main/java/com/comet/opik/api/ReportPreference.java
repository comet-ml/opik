package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReportPreference(
        @JsonIgnore String workspaceId,
        @JsonIgnore String workspaceName,
        UUID projectId,
        boolean enabled,
        String scheduleTimeUtc,
        Instant createdAt,
        Instant lastUpdatedAt) {

    public static final String DEFAULT_SCHEDULE_TIME_UTC = "05:00:00";

    public static ReportPreference defaultForProject(UUID projectId) {
        return ReportPreference.builder()
                .projectId(projectId)
                .enabled(false)
                .scheduleTimeUtc(DEFAULT_SCHEDULE_TIME_UTC)
                .build();
    }
}
