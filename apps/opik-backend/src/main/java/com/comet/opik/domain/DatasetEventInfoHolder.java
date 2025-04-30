package com.comet.opik.domain;

import com.comet.opik.api.ExperimentType;
import lombok.Builder;

import java.util.UUID;

@Builder
public record DatasetEventInfoHolder(UUID datasetId, ExperimentType type) {
}