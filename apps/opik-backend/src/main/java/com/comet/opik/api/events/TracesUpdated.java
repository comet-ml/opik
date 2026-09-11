package com.comet.opik.api.events;

import com.comet.opik.api.TraceUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Map;
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
    // traceId -> projectId, so consumers can split traceIds per project. May be null for callers
    // that don't carry it; consumers then fall back to {@link #projectIds}.
    private final @Nullable Map<UUID, UUID> traceIdToProjectId;

    public TracesUpdated(@NonNull Set<UUID> projectIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull TraceUpdate traceUpdate) {
        this(projectIds, traceIds, workspaceId, userName, traceUpdate, null);
    }

    public TracesUpdated(@NonNull Set<UUID> projectIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull TraceUpdate traceUpdate, @Nullable String workspaceName) {
        this(projectIds, traceIds, workspaceId, userName, traceUpdate, workspaceName, null);
    }

    public TracesUpdated(@NonNull Set<UUID> projectIds, @NonNull Set<UUID> traceIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull TraceUpdate traceUpdate, @Nullable String workspaceName,
            @Nullable Map<UUID, UUID> traceIdToProjectId) {
        super(workspaceId, userName);
        this.projectIds = projectIds;
        this.traceIds = traceIds;
        this.traceUpdate = traceUpdate;
        this.workspaceName = workspaceName;
        this.traceIdToProjectId = traceIdToProjectId;
    }
}
