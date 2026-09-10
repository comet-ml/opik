package com.comet.opik.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Database row for {@code annotation_queue_automations}. {@code conditions} stays a JSON string at this
 * layer — serialisation happens in the service, matching how {@code automation_rules.filters} is handled.
 */
@Builder(toBuilder = true)
public record AnnotationQueueAutomationModel(
        String workspaceId,
        UUID queueId,
        UUID projectId,
        String scope,
        boolean enabled,
        String conditions,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) {
}
