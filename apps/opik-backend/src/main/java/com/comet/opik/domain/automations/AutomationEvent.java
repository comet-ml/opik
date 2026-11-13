package com.comet.opik.domain.automations;

import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;
import java.util.UUID;

/**
 * One firing of an event an automation can listen to, other than a score: a prompt committed, an
 * experiment finished, a CI run. Carries the identity of the object it happened on and the run that
 * caused it, when one did, so a chain of runs stays traceable.
 *
 * <p>The {@code @class} type id is what lets the shared stream codec decode it, as with the routing
 * message.
 */
@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record AutomationEvent(
        @NonNull String workspaceId,
        @NonNull String workspaceName,
        @NonNull String userName,
        @Nullable UUID projectId,
        @NonNull String event,
        @NonNull String itemId,
        @NonNull Map<String, Object> payload,
        @Nullable UUID originRunId,
        int depth) implements RedisSubscriberMessage {

    public AutomationEvent {
        payload = Map.copyOf(payload);
    }
}
