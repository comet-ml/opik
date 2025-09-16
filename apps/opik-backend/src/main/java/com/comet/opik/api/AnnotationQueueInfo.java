package com.comet.opik.api;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record AnnotationQueueInfo(
        UUID id,
        UUID projectId) {
}
