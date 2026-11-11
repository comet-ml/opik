package com.comet.opik.domain.topics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Clock;
import java.time.Duration;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The nightly job's metrics. Every metric of one workspace's work carries {@code workspace_id} and
 * {@code workspace_name}; only {@code topics.nightly.budget_used_seconds}, a whole night's, has neither.
 */
@Singleton
public class TopicsMetrics {

    private static final AttributeKey<String> STATUS = stringKey("status");
    private static final AttributeKey<String> ERROR_CODE = stringKey("error_code");
    private static final AttributeKey<String> PHASE = stringKey("phase");
    private static final AttributeKey<String> OUTCOME = stringKey("outcome");
    private static final AttributeKey<String> TABLE = stringKey("table");

    private final TopicsWorkspaceNames names;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram otherShare;

    @Inject
    public TopicsMetrics(@NonNull TopicsWorkspaceNames names) {
        this(GlobalOpenTelemetry.get().getMeter("opik.topics"), names);
    }

    /** Workspace names fall back to the id. */
    TopicsMetrics(@NonNull Meter meter) {
        this(meter, new TopicsWorkspaceNames(workspaceId -> null, Clock.systemUTC()));
    }

    TopicsMetrics(@NonNull Meter meter, @NonNull TopicsWorkspaceNames names) {
        this.names = names;
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code; start_failed when the run "
                        + "could not start, abandoned when housekeeping failed a run its pod left running")
                .build();
        this.runDuration = meter.histogramBuilder("topics.run.duration_seconds").setUnit("s")
                .setDescription("Duration of each phase of a run: window, kmeans, qwen, assign, save")
                .build();
        this.qwenCalls = meter.counterBuilder("topics.qwen.calls")
                .setDescription("Qwen calls, by outcome: ok, timeout, error, bad_answer")
                .build();
        this.saveRows = meter.counterBuilder("topics.save.rows")
                .setDescription("Rows a saved run wrote, by table")
                .build();
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's window traces labelled Other, the stats.other_share of its "
                        + "topic_runs row")
                .build();
    }

    void run(@NonNull String workspaceId, @NonNull TopicRun.Status status, String errorCode) {
        var attributes = names.builder(workspaceId).put(STATUS, status.getValue());
        if (errorCode != null) {
            attributes.put(ERROR_CODE, errorCode);
        }
        runs.add(1, attributes.build());
    }

    void phase(@NonNull String workspaceId, @NonNull String phase, @NonNull Duration duration) {
        runDuration.record(duration.toNanos() / 1e9, names.builder(workspaceId).put(PHASE, phase).build());
    }

    void qwen(@NonNull String workspaceId, @NonNull String outcome) {
        qwenCalls.add(1, names.builder(workspaceId).put(OUTCOME, outcome).build());
    }

    void saved(@NonNull String workspaceId, @NonNull String table, long rows) {
        saveRows.add(rows, names.builder(workspaceId).put(TABLE, table).build());
    }

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, names.of(workspaceId));
    }
}
