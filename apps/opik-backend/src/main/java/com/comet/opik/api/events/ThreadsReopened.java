package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class ThreadsReopened extends BaseEvent {

    private final Set<UUID> threadModelIds;
    private final UUID projectId;

    public ThreadsReopened(@NonNull Set<UUID> threadModelIds, @NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.threadModelIds = threadModelIds;
        this.projectId = projectId;
    }
}
