package com.comet.opik.domain;

import java.util.UUID;

public record DatasetItemSummary(UUID datasetId, long datasetItemsCount) {
    public static DatasetItemSummary empty(UUID datasetId) {
        return new DatasetItemSummary(datasetId, 0);
    }
}
