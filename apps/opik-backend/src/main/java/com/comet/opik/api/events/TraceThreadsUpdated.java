package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

/**
 * Threads whose own fields changed after they were created: their tags were edited, or they were closed.
 */
@Getter
@Accessors(fluent = true)
public class TraceThreadsUpdated extends BaseEvent {

    // Null when the change spans threads the caller did not resolve to a project, as a batch update does.
    private final @Nullable UUID projectId;
    private final @NonNull Set<UUID> threadModelIds;

    public TraceThreadsUpdated(@Nullable UUID projectId, @NonNull Set<UUID> threadModelIds,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.projectId = projectId;
        this.threadModelIds = threadModelIds;
    }
}
