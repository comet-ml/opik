package com.comet.opik.domain.observability;

import lombok.NonNull;

/**
 * Identity an observability write runs under. Carried so each fire-and-forget write can supply its own
 * reactive context (workspace/user) independently of the request that triggered it — the write is
 * detached from that request's subscription.
 */
public record ObservabilityContext(@NonNull String workspaceId, @NonNull String userName) {
}
