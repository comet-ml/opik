package com.comet.opik.domain.topics;

import lombok.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Who a run is (the workspace name resolved once, for its metrics) and its window. {@code born} is the day the window
 * ends on, but never after the UTC day the run started: the first-build job's window ends at tomorrow's start (it holds
 * today's traces), and its topics are born today.
 */
record TopicRun(@NonNull UUID runId, @NonNull String workspaceId, @NonNull String workspaceName,
        @NonNull UUID projectId, @NonNull LocalDate born, @NonNull Instant from, @NonNull Instant to) {

    static TopicRun of(@NonNull UUID runId, @NonNull String workspaceId, @NonNull String workspaceName,
            @NonNull UUID projectId, @NonNull LocalDate night, int windowDays) {
        var started = LocalDate.now(ZoneOffset.UTC);
        var from = night.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        return new TopicRun(runId, workspaceId, workspaceName, projectId, night.isAfter(started) ? started : night,
                from, to);
    }

    TopicsMetrics.Workspace workspace() {
        return new TopicsMetrics.Workspace(workspaceId, workspaceName);
    }
}
