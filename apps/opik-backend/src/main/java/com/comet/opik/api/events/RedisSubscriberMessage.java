package com.comet.opik.api.events;

import com.comet.opik.infrastructure.auth.RequestContext;

/**
 * Common type for every {@code BaseRedisSubscriber} stream message, carrying the workspace and user
 * the message belongs to.
 *
 * <p>{@code BaseRedisSubscriber} attributes per-message metrics — {@code *_processing_errors_total} and
 * the {@code *_processing_time} histogram — to the originating workspace/user by reading this interface
 * off the message, so a subscriber whose message implements it needs no {@code messageContext} override.
 * {@code OnlineScoringBaseScorer} additionally bounds its type parameter to this interface.
 *
 * <p>Only {@link #workspaceId()} is mandatory. System-initiated streams that have no associated user
 * or human-readable workspace name inherit sensible defaults; messages that carry richer information
 * override the corresponding accessor.
 */
public interface RedisSubscriberMessage {

    String workspaceId();

    /**
     * User the message is attributed to. Defaults to {@link RequestContext#SYSTEM_USER} for
     * system-initiated streams (no end user); messages produced from a user request override it.
     */
    default String userName() {
        return RequestContext.SYSTEM_USER;
    }

    /**
     * Human-readable workspace name, resolved once at produce time and carried on the message so
     * consumers/metrics need no per-message lookup.
     *
     * <p><strong>Falls back to {@link #workspaceId()}</strong> when the message carries no name
     * (older in-flight messages, or producers that don't resolve it yet) — so a caller may receive
     * the workspace <em>id</em> here, not a display name. Callers that need to distinguish the two
     * should compare against {@link #workspaceId()}.
     */
    default String workspaceName() {
        return workspaceId();
    }
}
