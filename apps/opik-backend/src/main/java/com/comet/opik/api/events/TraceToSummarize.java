package com.comet.opik.api.events;

import com.comet.opik.api.Trace;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToSummarize(
        @NotNull Trace trace,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
