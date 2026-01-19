package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record DatasetExportMessage(
        @NotNull UUID jobId,
        @NotNull UUID datasetId,
        @NotNull String workspaceId) {
}
