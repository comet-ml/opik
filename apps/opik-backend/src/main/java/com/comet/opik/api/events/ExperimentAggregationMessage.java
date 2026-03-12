package com.comet.opik.api.events;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record ExperimentAggregationMessage(@NonNull UUID experimentId, @NonNull String workspaceId,
        @NonNull String userName) {
}
