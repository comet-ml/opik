package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * A batch of entities whose feedback scores just changed, handed to the routing consumer.
 *
 * <p>Deliberately carries no decision and no scores. The consumer re-loads automation config and re-reads
 * scores itself, so the decision reflects configuration as of <em>processing</em> time — disabling an
 * automation does not leave already-decided messages in flight — and the payload cannot go stale. It also
 * keeps the message small, which matters because one is published per score event.
 */
@Builder(toBuilder = true)
public record AnnotationQueueRoutingMessage(
        String workspaceId,
        String userName,
        AnnotationQueue.AnnotationScope scope,
        Set<UUID> entityIds) {
}
