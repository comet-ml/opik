package com.comet.opik.api.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class ExperimentUpdated extends BaseEvent {
    private final @NonNull UUID experimentId;
    private final @NonNull ExperimentStatus newStatus;

    public ExperimentUpdated(@NonNull UUID experimentId, @NonNull ExperimentStatus newStatus,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.experimentId = experimentId;
        this.newStatus = newStatus;
    }
}
