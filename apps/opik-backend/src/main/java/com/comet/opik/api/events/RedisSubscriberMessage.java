package com.comet.opik.api.events;

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
     * User the message is attributed to, or {@code null} for system-initiated streams that carry no end
     * user. {@code BaseRedisSubscriber} attributes those to {@code RequestContext.SYSTEM_USER}; keeping
     * that fallback out of this api-layer marker avoids leaking an auth-layer dependency onto every
     * implementor. Messages produced from a user request override this with the real user.
     */
    default String userName() {
        return null;
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

    /**
     * Returns a copy of this message carrying the given workspace name, or {@code this} for message types
     * that don't carry a separate name field. {@code OnlineScorePublisher} calls this at enqueue time to
     * stamp the resolved name onto the message, so async consumers and their metrics get a real workspace
     * name (resolved from the reactive context or the workspace-name service) rather than the id fallback.
     */
    default RedisSubscriberMessage withWorkspaceName(String workspaceName) {
        return this;
    }
}
