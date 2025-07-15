package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class TraceThreadsCreated extends BaseEvent {

    private final @NonNull UUID projectId;
    private final @NonNull List<UUID> traceThreadModelIds;

    public TraceThreadsCreated(@NonNull List<UUID> traceThreadModelIds, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.traceThreadModelIds = traceThreadModelIds;
        this.projectId = projectId;
    }

}
