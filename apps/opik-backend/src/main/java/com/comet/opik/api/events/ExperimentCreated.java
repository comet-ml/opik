package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class ExperimentCreated extends BaseEvent {

    private final UUID experimentId;
    private final UUID datasetId;
    private final Instant createdAt;

    public ExperimentCreated(UUID experimentId, UUID datasetId, Instant createdAt, String workspaceId,
            String userName) {
        super(workspaceId, userName);
        this.experimentId = experimentId;
        this.datasetId = datasetId;
        this.createdAt = createdAt;
    }
}
