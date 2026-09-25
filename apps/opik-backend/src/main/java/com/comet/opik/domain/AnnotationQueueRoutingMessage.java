package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

/**
 * A batch of entities whose feedback scores just changed, handed to the routing consumer.
 *
 * <p>One message is one flush of the Redis buffer for one (workspace, scope): every entity scored in the
 * buffer window, already deduplicated, so the consumer evaluates each once per message rather than once
 * per score event.
 *
 * <p>Deliberately carries no decision, no scores and no author. The consumer re-loads automation config
 * and re-reads scores itself, so the decision reflects configuration as of <em>processing</em> time —
 * disabling an automation does not leave already-decided messages in flight — and the payload cannot go
 * stale. Items it adds are attributed to the system user, as with every other background write: the
 * item's {@code source} already says it was automation.
 *
 * <p>The {@code @class} type id is required: the shared Redis stream codec deserializes into
 * {@code Object} and resolves the concrete type from that property. Without it the consumer fails to
 * decode every message. Implementing {@link RedisSubscriberMessage} is what lets
 * {@code BaseRedisSubscriber} attribute its per-message metrics to the right workspace.
 */
@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record AnnotationQueueRoutingMessage(
        @NonNull String workspaceId,
        @NonNull AnnotationQueue.AnnotationScope scope,
        @NonNull Set<UUID> entityIds) implements RedisSubscriberMessage {

    // Copied, so a message cannot be observed differently on two deliveries.
    public AnnotationQueueRoutingMessage {
        entityIds = Set.copyOf(entityIds);
    }
}
