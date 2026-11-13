package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code topic_night_projects}: a project one night looked at and what it decided: {@code nightly_update},
 * {@code skip} with a reason, {@code not_reached}, or {@code count_failed} when its window count read failed.
 */
@Builder(toBuilder = true)
public record TopicNightProject(
        @NonNull UUID id,
        @NonNull LocalDate night,
        @NonNull String workspaceId,
        @Nullable UUID projectId,
        @NonNull String decision,
        @Nullable String reason,
        @Nullable UUID runId) {

    /** The decision of a project's row when its window count read failed. */
    public static final String COUNT_FAILED = "count_failed";
}
