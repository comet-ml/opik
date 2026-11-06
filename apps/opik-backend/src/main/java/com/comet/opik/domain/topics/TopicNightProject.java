package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code topic_night_projects}: a project one night looked at and what it decided. A workspace whose count
 * read failed has one row with decision {@code count_failed} and no project.
 */
@Builder(toBuilder = true)
public record TopicNightProject(
        @NonNull UUID id,
        @NonNull LocalDate night,
        @NonNull String workspaceId,
        @Nullable UUID projectId,
        @Nullable Long vectors,
        @NonNull String decision,
        @Nullable String reason,
        @Nullable UUID runId) {

    /** The decision of a workspace's row when its count read failed. */
    public static final String COUNT_FAILED = "count_failed";
}
