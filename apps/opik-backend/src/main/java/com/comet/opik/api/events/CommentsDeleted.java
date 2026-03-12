package com.comet.opik.api.events;

import com.comet.opik.domain.EntityType;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class CommentsDeleted extends BaseEvent {
    private final @NonNull Set<UUID> entityIds;
    private final @NonNull EntityType entityType;

    public CommentsDeleted(@NonNull Set<UUID> entityIds, @NonNull EntityType entityType,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.entityIds = entityIds;
        this.entityType = entityType;
    }
}
