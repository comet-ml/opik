package com.comet.opik.api.events;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Accessors(fluent = true)
public class TracesCreated extends BaseEvent {
    private final @NonNull List<Trace> traces;
    // Resolved from RequestContext.WORKSPACE_NAME at publish time (TraceService). May be null/blank
    // for callers that don't carry it; consumers fall back to workspaceId.
    private final @Nullable String workspaceName;

    public TracesCreated(@NonNull List<Trace> traces, @NonNull String workspaceId, @NonNull String userName) {
        this(traces, workspaceId, userName, null);
    }

    public TracesCreated(@NonNull List<Trace> traces, @NonNull String workspaceId, @NonNull String userName,
            @Nullable String workspaceName) {
        super(workspaceId, userName);
        this.traces = traces;
        this.workspaceName = workspaceName;
    }

    public Set<UUID> projectIds() {
        return traces.stream()
                .map(Trace::projectId)
                .collect(Collectors.toSet());
    }
}
