package com.comet.opik.api.events;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.events.BaseEvent;
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

    public TracesCreated(@NonNull List<Trace> traces, @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.traces = traces;
    }

    public Set<UUID> projectIds() {
        return traces.stream()
                .map(Trace::projectId)
                .collect(Collectors.toSet());
    }
}
