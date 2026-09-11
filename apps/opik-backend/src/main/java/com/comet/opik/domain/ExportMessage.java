package com.comet.opik.domain;

import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

/**
 * Notification that an export job is ready to be processed.
 *
 * <p>Carries only the job id, never the params. The stream codec builds its own {@code ObjectMapper} as a copy of
 * the shared one, so subtypes registered at startup never reach it and a polymorphic payload here would fail to
 * decode — leaving the job stuck in PENDING. The job row is the source of truth for what to export.</p>
 */
@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record ExportMessage(
        @NotNull UUID jobId,
        @NotNull String workspaceId,
        String workspaceName) implements RedisSubscriberMessage {
}
