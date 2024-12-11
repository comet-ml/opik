package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class TracesCreated extends BaseEvent {
    private final @NonNull Set<UUID> projectIds;

    public TracesCreated(@NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.projectIds = projectIds;
    }
}
