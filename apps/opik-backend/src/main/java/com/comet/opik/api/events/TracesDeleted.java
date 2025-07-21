package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class TracesDeleted extends BaseEvent {
    private final @NonNull Set<UUID> traceIds;

    public TracesDeleted(@NonNull Set<UUID> traceIds, @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.traceIds = traceIds;
    }
}