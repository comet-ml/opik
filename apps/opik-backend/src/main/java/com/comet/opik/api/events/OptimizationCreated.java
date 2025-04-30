package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class OptimizationCreated extends BaseEvent {

    private final @NonNull UUID optimizationId;
    private final @NonNull UUID datasetId;
    private final @NonNull Instant createdAt;

    public OptimizationCreated(@NonNull UUID optimizationId, @NonNull UUID datasetId, @NonNull Instant createdAt,
            @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.optimizationId = optimizationId;
        this.datasetId = datasetId;
        this.createdAt = createdAt;
    }
}
