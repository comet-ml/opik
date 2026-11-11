package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
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

    /**
     * Why a project was skipped: under {@code minVectors}, deleted, its lock held, its list already saved, paused
     * after its last runs failed, or its vectors backfill not drained yet.
     */
    static final String REASON_SMALL = "small";
    static final String REASON_DELETED = "deleted";
    static final String REASON_LOCKED = "locked";
    static final String REASON_ALREADY_SAVED = "already_saved";
    static final String REASON_PAUSED = "paused";
    static final String REASON_BACKFILL = "backfill";

    static final String FIRST_BUILD_GATE = "topics.first_build.gate";
    static final String FIRST_BUILD_LONGEST_WAIT = "topics.first_build.gate.longest_wait_seconds";

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
    private final DoubleHistogram otherShare;
    private final LongCounter firstBuildGate;
    private volatile FirstBuildWaits firstBuildWaits;

    /** One first-build run's longest waits, with the workspace names resolved when the run set them. */
    private record FirstBuildWaits(@NonNull Instant until, @NonNull Map<Attributes, Long> seconds) {
    }

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
                        + "built; paused, its last 3 runs failed within 7 nights; backfill, its vectors backfill has "
                        + "not drained), first_build, not_reached (the night's budget ran out)")
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
                .setDescription("Workspaces whose window count read failed, by error_type: the night skips all "
                        + "their projects")
                .build();
        this.firstBuildGate = meter.counterBuilder(FIRST_BUILD_GATE)
                .setDescription("Projects each first-build run listed, by the outcome of the vectors backfill gate: "
                        + "open (the project's backfill drained), waiting, timed_out_open (the backfill ran past "
                        + "topics.firstBuild.backfillMaxWait and at least 99% of its traces have a vector, so the "
                        + "list is built) or timed_out_waiting (past it with fewer: the project keeps waiting)")
                .build();
        meter.gaugeBuilder(FIRST_BUILD_LONGEST_WAIT).ofLongs().setUnit("s")
                .setDescription("Per workspace, how long the oldest vectors backfill a first build waits for has run, "
                        + "as the last first-build run on this pod saw it; 0 with no waiting project. Published for "
                        + "two first-build intervals after a run: aggregate with max")
                .buildWithCallback(measurement -> {
                    var waits = firstBuildWaits;
                    if (waits != null && Instant.now().isBefore(waits.until())) {
                        waits.seconds().forEach((attributes, seconds) -> measurement.record(seconds, attributes));
                    }
                });
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

    void countFailure(@NonNull String workspaceId, @NonNull Throwable error) {
        countFailures.add(1,
                names.builder(workspaceId).put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, names.of(workspaceId));
    }

    void firstBuildGate(@NonNull String workspaceId, @NonNull TopicsBackfillGate.State state) {
        firstBuildGate.add(1, names.builder(workspaceId).put(OUTCOME, state.getValue()).build());
    }

    /** A first-build run's longest wait per workspace, published for {@code kept} from now. */
    void firstBuildWaits(@NonNull Map<String, Duration> longest, @NonNull Duration kept) {
        var seconds = new LinkedHashMap<Attributes, Long>();
        longest.forEach((workspaceId, wait) -> seconds.put(names.of(workspaceId), wait.toSeconds()));
        firstBuildWaits = new FirstBuildWaits(Instant.now().plus(kept), seconds);
    }

    void budgetUsed(@NonNull Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }
}
