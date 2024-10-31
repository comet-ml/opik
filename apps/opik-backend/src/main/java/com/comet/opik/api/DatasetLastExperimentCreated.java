package com.comet.opik.api;

import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

public record DatasetLastExperimentCreated(@NonNull UUID datasetId, Instant experimentCreatedAt) {
}
