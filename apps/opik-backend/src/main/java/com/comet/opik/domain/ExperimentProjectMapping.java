package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

@Builder(toBuilder = true)
public record ExperimentProjectMapping(@NonNull UUID experimentId, @NonNull UUID projectId) {
}
