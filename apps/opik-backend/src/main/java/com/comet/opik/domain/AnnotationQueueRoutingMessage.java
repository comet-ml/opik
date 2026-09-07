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
        @NonNull Set<UUID> entityIds) implements RedisSubscriberMessage {
}
