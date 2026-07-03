package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class SpansDeleted extends BaseEvent {
    private final @NonNull Set<UUID> traceIds;
    private final @NonNull Set<UUID> spanIds;
    private final UUID projectId;

    public SpansDeleted(@NonNull Set<UUID> spanIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName) {
        this(spanIds, traceIds, workspaceId, userName, null);
    }

    public SpansDeleted(@NonNull Set<UUID> spanIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, UUID projectId) {
        super(workspaceId, userName);
        this.traceIds = traceIds;
        this.spanIds = spanIds;
        this.projectId = projectId;
    }
}
