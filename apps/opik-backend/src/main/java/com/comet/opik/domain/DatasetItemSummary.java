package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetItemSummary(@NonNull UUID datasetId, long datasetItemsCount) {
    public static DatasetItemSummary empty(UUID datasetId) {
        return new DatasetItemSummary(datasetId, 0);
    }
}
