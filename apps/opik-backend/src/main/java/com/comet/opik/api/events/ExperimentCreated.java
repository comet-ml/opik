package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class ExperimentCreated extends BaseEvent {

    private final @NonNull UUID experimentId;
    private final @NonNull UUID datasetId;
    private final @NonNull Instant createdAt;

    public ExperimentCreated(@NonNull UUID experimentId, @NonNull UUID datasetId, @NonNull Instant createdAt,
            @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.experimentId = experimentId;
        this.datasetId = datasetId;
        this.createdAt = createdAt;
    }
}
