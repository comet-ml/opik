package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Set;
import java.util.UUID;

@Getter
@Accessors(fluent = true)
public class ExperimentItemsCreated extends BaseEvent {
    private final @NonNull Set<UUID> experimentIds;

    public ExperimentItemsCreated(@NonNull Set<UUID> experimentIds, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentIds),
                "Argument 'experimentIds' must not be empty");
        this.experimentIds = experimentIds;
    }
}
