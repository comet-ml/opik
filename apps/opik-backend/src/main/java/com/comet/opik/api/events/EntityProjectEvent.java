package com.comet.opik.api.events;

import com.comet.opik.domain.EntityType;
import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

/**
 * Base for events that carry a set of entities of a single {@link EntityType} scoped to (at most) one project.
 * {@code projectId} is optional: callers that can resolve a single project set it so downstream consumers can
 * prune by project; callers that can't (multi-project batches, paths without it) leave it {@code null}.
 */
@Getter
@Accessors(fluent = true)
public abstract class EntityProjectEvent extends BaseEvent {

    private final @NonNull Set<UUID> entityIds;
    private final @NonNull EntityType entityType;
    private final @Nullable UUID projectId;

    protected EntityProjectEvent(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName, @Nullable UUID projectId) {
        super(workspaceId, userName);
        this.entityIds = entityIds;
        this.entityType = entityType;
        this.projectId = projectId;
    }
}
