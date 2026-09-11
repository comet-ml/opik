package com.comet.opik.api.attachment;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record AttachmentSearchCriteria(
        UUID containerId,
        UUID entityId,
        EntityType entityType) {
}
