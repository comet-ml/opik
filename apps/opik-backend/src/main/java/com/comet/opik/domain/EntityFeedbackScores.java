package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The effective feedback scores of one entity, as a name to value map, alongside the project it belongs to.
 *
 * <p>Assembled by the caller from the {@link EffectiveFeedbackScore} rows the DAO streams, so that the
 * collecting — and the memory it holds — stays where the bound on it is known.
 */
@Builder(toBuilder = true)
public record EntityFeedbackScores(@NonNull UUID entityId, @NonNull UUID projectId,
        @NonNull Map<String, BigDecimal> scores) {
}
