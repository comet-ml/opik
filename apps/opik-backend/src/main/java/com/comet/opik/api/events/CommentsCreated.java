package com.comet.opik.api.events;

import com.comet.opik.domain.EntityType;
import jakarta.annotation.Nullable;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

public class CommentsCreated extends EntityProjectEvent {

    public CommentsCreated(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName) {
        this(entityIds, entityType, workspaceId, userName, null);
    }

    public CommentsCreated(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName, @Nullable UUID projectId) {
        super(entityIds, entityType, workspaceId, userName, projectId);
    }
}
