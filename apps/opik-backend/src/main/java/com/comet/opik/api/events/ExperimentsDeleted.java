package com.comet.opik.api.events;

import com.comet.opik.domain.DatasetEventInfoHolder;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Accessors(fluent = true)
public class ExperimentsDeleted extends BaseEvent {

    private final @NonNull List<DatasetEventInfoHolder> datasetInfo;

    public ExperimentsDeleted(@NonNull List<DatasetEventInfoHolder> datasetInfo, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.datasetInfo = datasetInfo;
    }
}
