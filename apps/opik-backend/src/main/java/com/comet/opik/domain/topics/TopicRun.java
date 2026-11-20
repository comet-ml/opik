package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One run of a project: who it is, its window, and the stats and phase seconds it logs. {@code born} is the day the
 * window ends on, but never after the UTC day the run started: the first-build job's window ends at tomorrow's start
 * (it holds today's traces), and its topics are born today.
 */
record TopicRun(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate born,
        @NonNull Instant from, @NonNull Instant to, @NonNull ObjectNode stats, @NonNull ObjectNode seconds) {

    static TopicRun of(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night, int windowDays) {
        var started = LocalDate.now(ZoneOffset.UTC);
        var from = night.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var stats = JsonUtils.createObjectNode();
        stats.put("window_from", from.toString());
        stats.put("window_until", to.toString());
        return new TopicRun(runId, workspaceId, projectId, night.isAfter(started) ? started : night, from, to, stats,
                JsonUtils.createObjectNode());
    }

    void add(String stat, long value) {
        stats.put(stat, stats.path(stat).asLong(0) + value);
    }
}
