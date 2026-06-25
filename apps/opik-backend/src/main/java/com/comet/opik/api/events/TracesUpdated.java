package com.comet.opik.api.events;

import com.comet.opik.api.TraceUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
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
    // Resolved from RequestContext.WORKSPACE_NAME at publish time (TraceService). May be null/blank
    // for callers that don't carry it; consumers fall back to workspaceId.
    private final @Nullable String workspaceName;

    public TracesUpdated(@NonNull Set<UUID> projectIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull TraceUpdate traceUpdate) {
        this(projectIds, traceIds, workspaceId, userName, traceUpdate, null);
    }

    public TracesUpdated(@NonNull Set<UUID> projectIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull TraceUpdate traceUpdate, @Nullable String workspaceName) {
        super(workspaceId, userName);
        this.projectIds = projectIds;
        this.traceIds = traceIds;
        this.traceUpdate = traceUpdate;
        this.workspaceName = workspaceName;
    }
}
