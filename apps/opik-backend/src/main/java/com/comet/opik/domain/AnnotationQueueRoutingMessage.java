package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;
import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;
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
         * Which scores the triggering events wrote, per entity, where the emitter said. Names only — no
         * values, so this cannot go stale: a name that existed still exists, and the value is always read
         * fresh. It exists so the consumer can tell a score that has not replicated yet from a score that
         * genuinely does not satisfy a condition. Absent for an entity means no information.
         */
        Map<UUID, Set<String>> scoreNamesByEntity) implements RedisSubscriberMessage {

    /**
     * Normalises a null map to empty and copies both levels, so a message cannot be observed differently
     * on two deliveries. Redelivery deserializes afresh and would not share state, but the publisher hands
     * in a map built by {@code Collectors.toMap} - mutable, and reachable from the caller.
     */
    public AnnotationQueueRoutingMessage {
        // Null-tolerant at both levels. The publisher never produces nulls, but this record is also
        // rebuilt by the stream codec from JSON, where an absent nested value deserializes to null - and
        // Set.copyOf would throw, failing the message instead of the freshness check it feeds.
        if (scoreNamesByEntity == null) {
            scoreNamesByEntity = Map.of();
        } else {
            var copy = new HashMap<UUID, Set<String>>(scoreNamesByEntity.size());
            scoreNamesByEntity.forEach((entityId, names) -> {
                if (entityId != null) {
                    copy.put(entityId, names == null ? Set.of() : Set.copyOf(names));
                }
            });
            scoreNamesByEntity = Map.copyOf(copy);
        }
    }

    public Set<String> expectedScoreNames(UUID entityId) {
        return scoreNamesByEntity.getOrDefault(entityId, Set.of());
    }
}
