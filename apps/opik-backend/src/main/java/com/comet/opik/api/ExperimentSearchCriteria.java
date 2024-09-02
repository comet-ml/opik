package com.comet.opik.api;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

import static com.comet.opik.domain.FeedbackScoreDAO.EntityType;

@Builder(toBuilder = true)
public record ExperimentSearchCriteria(String name, UUID datasetId, @NonNull EntityType entityType) {
}
