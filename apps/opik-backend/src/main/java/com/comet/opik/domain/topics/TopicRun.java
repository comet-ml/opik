package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.With;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Who a run is (the workspace name resolved once, for its metrics) and its window. {@code born} is the day the window
 * ends on, but never after the UTC day the run started: the discovery job's window ends at tomorrow's start (it holds
 * today's traces), and its topics are born today. {@code uncached} sends the run's LLM calls without opik-inference's
 * cache: a discovery run that rebuilds a list reset by hand, so the same window does not get the old answer back.
 */
record TopicRun(@NonNull UUID runId, @NonNull String workspaceId, @NonNull String workspaceName,
        @NonNull UUID projectId, @NonNull LocalDate born, @NonNull Instant from, @NonNull Instant to,
        @With boolean uncached) {

    static TopicRun of(@NonNull UUID runId, @NonNull String workspaceId, @NonNull String workspaceName,
            @NonNull UUID projectId, @NonNull LocalDate night, int windowDays) {
        var started = LocalDate.now(ZoneOffset.UTC);
        var from = night.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        return new TopicRun(runId, workspaceId, workspaceName, projectId, night.isAfter(started) ? started : night,
                from, to, false);
    }

    TopicsMetrics.Workspace workspace() {
        return new TopicsMetrics.Workspace(workspaceId, workspaceName);
    }
}
