package com.comet.opik.api.events;

import com.comet.opik.api.TraceUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class TracesUpdated extends BaseEvent {
    private final @NonNull Set<UUID> projectIds;
    private final @NonNull Set<UUID> traceIds;
    private final @NonNull TraceUpdate traceUpdate;

    public TracesUpdated(@NonNull Set<UUID> projectIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull TraceUpdate traceUpdate) {
        super(workspaceId, userName);
        this.projectIds = projectIds;
        this.traceIds = traceIds;
        this.traceUpdate = traceUpdate;
    }
}
