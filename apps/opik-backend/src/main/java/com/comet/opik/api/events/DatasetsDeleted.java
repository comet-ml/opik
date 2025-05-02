package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class DatasetsDeleted extends BaseEvent {

    private final @NonNull Set<UUID> datasetIds;

    public DatasetsDeleted(@NonNull Set<UUID> datasetIds, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.datasetIds = datasetIds;
    }
}
