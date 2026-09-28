package com.comet.opik.api.events;

import com.comet.opik.domain.EntityType;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

@Getter
public class FeedbackScoresCreated extends EntityProjectEvent {

    /**
     * Names of the scores this event wrote, where the emitter knows them.
     *
     * <p>Carried for annotation queue routing, which uses them to tell "this entity has no scores yet"
     * apart from "the score that triggered me is not visible in ClickHouse yet". Without them the second
     * case is indistinguishable from a legitimate non-match, and the entity goes permanently unrouted.
     *
     * <p>Empty means the emitter did not say, which consumers must read as no information rather than as
     * an empty set of scores.
     */
    private final Set<String> scoreNames;

    public FeedbackScoresCreated(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName) {
        this(entityIds, entityType, workspaceId, userName, null);
    }

    public FeedbackScoresCreated(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName, @Nullable UUID projectId) {
        this(entityIds, entityType, workspaceId, userName, projectId, Set.of());
    }

    public FeedbackScoresCreated(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName, @Nullable UUID projectId,
            @NonNull Set<String> scoreNames) {
        super(entityIds, entityType, workspaceId, userName, projectId);
        this.scoreNames = Set.copyOf(scoreNames);
    }
}
