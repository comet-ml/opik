package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

/**
 * A batch of entities whose feedback scores just changed, handed to the routing consumer.
 *
 * <p>Deliberately carries no decision and no scores. The consumer re-loads automation config and re-reads
 * scores itself, so the decision reflects configuration as of <em>processing</em> time — disabling an
 * automation does not leave already-decided messages in flight — and the payload cannot go stale. It also
 * keeps the message small, which matters because one is published per score event.
 *
 * <p>The {@code @class} type id is required: the shared Redis stream codec deserializes into
 * {@code Object} and resolves the concrete type from that property. Without it the consumer fails to
 * decode every message. Implementing {@link RedisSubscriberMessage} is what lets
 * {@code BaseRedisSubscriber} attribute its per-message metrics to the right workspace and user.
 */
@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record AnnotationQueueRoutingMessage(
        @NonNull String workspaceId,
        String userName,
        @NonNull AnnotationQueue.AnnotationScope scope,
        @NonNull Set<UUID> entityIds,
        /**
         * Which scores the triggering event wrote, where the emitter said. Names only — no values, so this
         * cannot go stale: a name that existed still exists, and the value is always read fresh. It exists
         * so the consumer can tell a score that has not replicated yet from a score that genuinely does
         * not satisfy a condition. Empty means no information, which is not the same as no scores.
         *
         * <p>One set for the whole message rather than one per entity, because a message is one score
         * event and the event reports the names it wrote across its whole batch without attributing them
         * to individual entities. Storing it per entity would put N identical copies in the payload. The
         * consumer still reads it per entity through {@link #expectedScoreNames(UUID)}, which is the shape
         * it needs once it folds several messages for the same entity together.
         */
        @Nullable Set<String> scoreNames) implements RedisSubscriberMessage {

    /**
     * Copies both collections, so a message cannot be observed differently on two deliveries. Redelivery
     * deserializes afresh and would not share state, but the publisher hands in collections it built and
     * its caller can still reach.
     *
     * <p>Null-tolerant on the names: this record is also rebuilt by the stream codec from JSON, where an
     * absent value deserializes to null, and {@code Set.copyOf} would throw — failing the whole message
     * rather than the freshness check it feeds.
     */
    public AnnotationQueueRoutingMessage {
        entityIds = Set.copyOf(entityIds);
        scoreNames = scoreNames == null ? Set.of() : Set.copyOf(scoreNames);
    }

    public Set<String> expectedScoreNames(UUID entityId) {
        return entityIds.contains(entityId) ? scoreNames : Set.of();
    }
}
