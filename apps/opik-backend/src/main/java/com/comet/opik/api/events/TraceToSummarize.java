package com.comet.opik.api.events;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToSummarize(
        @NotNull UUID traceId,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
