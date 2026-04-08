package com.comet.opik.api.events;

import com.comet.opik.api.ExperimentExecutionRequest;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record ExperimentItemToProcess(
        @NonNull UUID batchId,
        @NonNull ExperimentExecutionRequest.PromptVariant prompt,
        @NonNull UUID datasetItemId,
        @NonNull UUID experimentId,
        @NonNull UUID datasetId,
        String versionHash,
        @NonNull String projectName,
        @NonNull String workspaceId,
        @NonNull String userName,
        @NonNull List<UUID> allExperimentIds) {
}
