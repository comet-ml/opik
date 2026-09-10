package com.comet.opik.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The effective feedback scores of one entity, as a name to value map, alongside the project it belongs to.
 *
 * <p>The project comes back with the scores because the caller generally does not know it: the
 * {@code FeedbackScoresCreated} event carries entity ids and, on the batch path, no project at all — a
 * batch may span several projects.
 */
@Builder(toBuilder = true)
public record EntityFeedbackScores(UUID entityId, UUID projectId, Map<String, BigDecimal> scores) {
}
