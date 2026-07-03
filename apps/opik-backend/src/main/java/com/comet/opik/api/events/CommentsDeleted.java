package com.comet.opik.api.events;

import com.comet.opik.domain.EntityType;
import jakarta.annotation.Nullable;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

public class CommentsDeleted extends EntityProjectEvent {

    public CommentsDeleted(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName) {
        this(entityIds, entityType, workspaceId, userName, null);
    }

    public CommentsDeleted(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName, @Nullable UUID projectId) {
        super(entityIds, entityType, workspaceId, userName, projectId);
    }
}
