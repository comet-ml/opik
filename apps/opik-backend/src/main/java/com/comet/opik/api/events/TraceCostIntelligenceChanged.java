package com.comet.opik.api.events;

import com.comet.opik.api.TraceUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.UUID;

/**
 * Fired on trace update so the Cost Intelligence subscriber can refresh the cipx_trace_identities
 * table. Carries the affected traces as traceId -> resolved projectId (project_id is part of the
 * merge key; a batch update spans multiple projects) plus the TraceUpdate. Trace creation reuses
 * {@link TracesCreated}. Posted only when the update's metadata contains cipx.session.identity.
 */
@Getter
@Accessors(fluent = true)
public class TraceCostIntelligenceChanged extends BaseEvent {

    private final @NonNull Map<UUID, UUID> traceProjectIds;
    private final @NonNull TraceUpdate traceUpdate;
    // Resolved from RequestContext.CIPX_DEVICE_ID at publish time (TraceService), like workspaceName on
    // TracesCreated. Null/blank for every non-device-token caller.
    private final @Nullable String cipxDeviceId;

    public TraceCostIntelligenceChanged(@NonNull Map<UUID, UUID> traceProjectIds, @NonNull TraceUpdate traceUpdate,
            @NonNull String workspaceId, @NonNull String userName) {
        this(traceProjectIds, traceUpdate, workspaceId, userName, null);
    }

    public TraceCostIntelligenceChanged(@NonNull Map<UUID, UUID> traceProjectIds, @NonNull TraceUpdate traceUpdate,
            @NonNull String workspaceId, @NonNull String userName, @Nullable String cipxDeviceId) {
        super(workspaceId, userName);
        this.traceProjectIds = traceProjectIds;
        this.traceUpdate = traceUpdate;
        this.cipxDeviceId = cipxDeviceId;
    }
}
