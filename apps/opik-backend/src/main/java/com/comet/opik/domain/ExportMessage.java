package com.comet.opik.domain;

import com.comet.opik.api.ExportParams;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record ExportMessage(
        @NotNull UUID jobId,
        @NotNull ExportParams params,
        @NotNull String workspaceId,
        String workspaceName) implements RedisSubscriberMessage {
}
