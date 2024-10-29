package com.comet.opik.api;

import java.time.Instant;
import java.util.UUID;

public record DatasetLastExperimentCreated(UUID datasetId, Instant experimentCreatedAt) {
}
