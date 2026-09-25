package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One entity's effective value for one feedback score name, as the UI shows it: the latest row per author
 * wins, and where several authors scored the same name the value is their average.
 *
 * <p>The row shape {@code FeedbackScoreDAO} streams. Assembling these into a per-entity view is the
 * caller's business — see {@link EntityFeedbackScores}.
 *
 * <p>The project comes back with each row because the caller generally does not know it: the
 * {@code FeedbackScoresCreated} event carries entity ids and, on the batch path, no project at all, since a
 * batch may span several projects.
 */
@Builder(toBuilder = true)
public record EffectiveFeedbackScore(@NonNull UUID entityId, @NonNull UUID projectId, @NonNull String name,
        @NonNull BigDecimal value) {
}
