package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class ExperimentsDeleted extends BaseEvent {

    private final Set<UUID> datasetIds;

    public ExperimentsDeleted(Set<UUID> datasetIds, String workspaceId, String userName) {
        super(workspaceId, userName);
        this.datasetIds = datasetIds;
    }
}
