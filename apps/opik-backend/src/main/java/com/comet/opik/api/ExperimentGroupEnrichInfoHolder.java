package com.comet.opik.api;

import lombok.Builder;

import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
public record ExperimentGroupEnrichInfoHolder(Map<UUID, Dataset> datasetMap) {
}
