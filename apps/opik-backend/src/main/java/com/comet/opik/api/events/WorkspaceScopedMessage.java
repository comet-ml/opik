package com.comet.opik.api.events;

/**
 * Marker for online-scoring stream messages that carry the workspace and user they belong to.
 *
 * <p>Implemented by the {@code *ToScore*} message records so {@code OnlineScoringBaseScorer} can
 * attribute processing-error metrics ({@code online_scoring_*_processing_errors_total}) to the
 * originating workspace instead of reporting {@code unknown}.
 */
public interface WorkspaceScopedMessage {

    String workspaceId();

    String userName();

    /**
     * Human-readable workspace name, resolved once at produce time (see {@code OnlineScoringSampler})
     * and carried on the message so consumers/metrics need no per-message lookup.
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
