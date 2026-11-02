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

    static final String DECISION_SKIP = "skip";
    static final String DECISION_FIRST_BUILD = "first_build";
    static final String DECISION_NOT_REACHED = "not_reached";

    /** Why a project was skipped: under {@code minVectors}, deleted, its lock held, or its list already saved. */
    static final String REASON_SMALL = "small";
    static final String REASON_DELETED = "deleted";
    static final String REASON_LOCKED = "locked";
    static final String REASON_ALREADY_SAVED = "already_saved";

    /** The run could not start: its {@code topic_runs} insert, or the read before it, failed. */
    static final String ERROR_START_FAILED = "start_failed";
    /** Housekeeping failed a run its pod left {@code running}: the code {@code TopicRunDAO.failAbandoned} writes. */
    static final String ERROR_ABANDONED = "abandoned";

    private static final AttributeKey<String> DECISION = stringKey("decision");
    private static final AttributeKey<String> REASON = stringKey("reason");
    private static final AttributeKey<String> STATUS = stringKey("status");
    private static final AttributeKey<String> ERROR_CODE = stringKey("error_code");
    private static final AttributeKey<String> PHASE = stringKey("phase");
    private static final AttributeKey<String> OUTCOME = stringKey("outcome");
    private static final AttributeKey<String> TABLE = stringKey("table");

    private final TopicsWorkspaceNames names;
    private final LongCounter projects;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram budgetUsed;
    private final LongCounter countFailures;
    private final LongCounter takeBackFailures;
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
        this.projects = meter.counterBuilder("topics.nightly.projects")
                .setDescription("Projects the nightly job looked at, by decision: skip (by reason: small, under "
                        + "minVectors; deleted; locked, held by a run started by hand; already_saved, a list already "
                        + "built), first_build, not_reached (the night's budget ran out)")
                .build();
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
        this.budgetUsed = meter.histogramBuilder("topics.nightly.budget_used_seconds").setUnit("s")
                .setDescription("Wall time of the night, from its start to its last run's end")
                .build();
        this.countFailures = meter.counterBuilder("topics.nightly.count_failures")
                .setDescription("Workspaces whose window count read failed: the night skips all their projects")
                .build();
        this.takeBackFailures = meter.counterBuilder("topics.save.take_back_failures")
                .setDescription("Failed saves whose trace_topics rows could not be deleted: readers see them until "
                        + "the project's next saved run")
                .build();
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's window traces labelled Other, the stats.other_share of its "
                        + "topic_runs row")
                .build();
    }

    /** {@code reason} is null for a decision other than skip. */
    void project(@NonNull String workspaceId, @NonNull String decision, String reason) {
        var attributes = names.builder(workspaceId).put(DECISION, decision);
        if (reason != null) {
            attributes.put(REASON, reason);
        }
        projects.add(1, attributes.build());
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

    void countFailure(@NonNull String workspaceId) {
        countFailures.add(1, names.of(workspaceId));
    }

    void takeBackFailure(@NonNull String workspaceId) {
        takeBackFailures.add(1, names.of(workspaceId));
    }

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, names.of(workspaceId));
    }

    void budgetUsed(@NonNull Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }
}
