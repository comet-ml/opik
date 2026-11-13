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

    public static final String NIGHTLY_UPDATE = "nightly_update";
    public static final String SKIP = "skip";
    /** The night's budget ran out before the project: it runs the next night. */
    public static final String NOT_REACHED = "not_reached";
    /** The decision of a project's row when its window count read failed. */
    public static final String COUNT_FAILED = "count_failed";

    /**
     * Why the night skipped a project: under {@code minVectors}, deleted, its lock held, its last saved run already
     * covers the night, paused after its last runs failed, or no live topic left when the night took its lock.
     */
    public static final String REASON_SMALL = "small";
    public static final String REASON_DELETED = "deleted";
    public static final String REASON_LOCKED = "locked";
    public static final String REASON_ALREADY_SAVED = "already_saved";
    public static final String REASON_PAUSED = "paused";
    public static final String REASON_NO_LIST = "no_list";
}
