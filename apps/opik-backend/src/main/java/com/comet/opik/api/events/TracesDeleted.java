package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.util.Set;
import java.util.UUID;

@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(fluent = true)
public class TracesDeleted extends BaseEvent {
    private final @NonNull Set<UUID> traceIds;
    private final UUID projectId;

    public TracesDeleted(@NonNull Set<UUID> traceIds, UUID projectId, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.traceIds = traceIds;
        this.projectId = projectId;
    }
}
