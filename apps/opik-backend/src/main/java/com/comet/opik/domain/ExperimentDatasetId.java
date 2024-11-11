package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

@Builder
record ExperimentDatasetId(UUID datasetId) {
}