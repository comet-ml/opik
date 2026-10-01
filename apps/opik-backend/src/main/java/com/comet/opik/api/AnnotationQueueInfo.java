package com.comet.opik.api;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

@Builder(toBuilder = true)
public record AnnotationQueueInfo(
        UUID id,
        UUID projectId,
        // The field this change adds; the rest predate it and are left alone rather than risk a new
        // null check firing on an existing path.
        @NonNull String name,
        AnnotationQueue.AnnotationScope scope,
        int annotatorsPerItem) {
}
