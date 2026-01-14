package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetExportMessage(
        UUID jobId,
        UUID datasetId,
        String workspaceId) {
}
