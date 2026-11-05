package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Clock;
import java.time.Duration;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The nightly job's and the read API's metrics. Every metric of one workspace's work carries {@code workspace_id} and
 * {@code workspace_name}; {@code topics.nightly.budget_used_seconds}, a whole night's, has neither, nor has
 * {@code topics.api.duration_seconds}, where every caller's workspace would add series.
 */
@Singleton
public class TopicsMetrics {

    static final String DECISION_SKIP = "skip";
    static final String DECISION_FIRST_BUILD = "first_build";
    static final String DECISION_NIGHTLY_UPDATE = "nightly_update";
    static final String DECISION_NOT_REACHED = "not_reached";

    /**
     * Why a project was skipped: under {@code minVectors}, deleted, its lock held, or its last saved run already covers
     * the night.
     */
    static final String REASON_SMALL = "small";
    static final String REASON_DELETED = "deleted";
    static final String REASON_LOCKED = "locked";
    static final String REASON_ALREADY_SAVED = "already_saved";

    /** The run could not start: its {@code topic_runs} insert, or the read before it, failed. */
    static final String ERROR_START_FAILED = "start_failed";
    /** Housekeeping failed a run its pod left {@code running}: the code {@code TopicRunDAO.failAbandoned} writes. */
    static final String ERROR_ABANDONED = "abandoned";
    /** The read API's status of a failed request, beside ready, collecting and disabled. */
    static final String API_STATUS_ERROR = "error";

    private static final AttributeKey<String> DECISION = stringKey("decision");
    private static final AttributeKey<String> REASON = stringKey("reason");
    private static final AttributeKey<String> STATUS = stringKey("status");
    private static final AttributeKey<String> ERROR_CODE = stringKey("error_code");
    private static final AttributeKey<String> PHASE = stringKey("phase");
    private static final AttributeKey<String> KIND = stringKey("kind");
    private static final AttributeKey<String> OUTCOME = stringKey("outcome");
    private static final AttributeKey<String> TABLE = stringKey("table");
    private static final AttributeKey<String> MODE = stringKey("mode");

    private final TopicsWorkspaceNames names;
    private final LongCounter projects;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram budgetUsed;
    private final DoubleHistogram liveTopics;
    private final LongCounter retired;
    private final LongCounter born;
    private final DoubleHistogram churn;
    private final DoubleHistogram churnAny;
    private final LongCounter countFailures;
    private final LongCounter takeBackFailures;
    private final DoubleHistogram otherShare;
    private final DoubleHistogram apiDuration;
    private final LongCounter apiStale;

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
                        + "minVectors; deleted; locked, held by a run started by hand; already_saved, its last saved "
                        + "run already covers the night), first_build, nightly_update, not_reached (the night's budget "
                        + "ran out)")
                .build();
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code; start_failed when the run "
                        + "could not start, abandoned when housekeeping failed a run its pod left running")
                .build();
        this.runDuration = meter.histogramBuilder("topics.run.duration_seconds").setUnit("s")
                .setDescription("Duration of each phase of a run: window, kmeans, qwen, assign, save (first build); "
                        + "window, state, update, churn, save (nightly update)")
                .build();
        this.qwenCalls = meter.counterBuilder("topics.qwen.calls")
                .setDescription("Qwen calls, by kind (author, verify, naming) and outcome: ok, timeout, "
                        + "error, bad_answer")
                .build();
        this.saveRows = meter.counterBuilder("topics.save.rows")
                .setDescription("Rows a saved run wrote, by table")
                .build();
        this.budgetUsed = meter.histogramBuilder("topics.nightly.budget_used_seconds").setUnit("s")
                .setDescription("Wall time of the night, from its start to its last run's end")
                .build();
        this.liveTopics = meter.histogramBuilder("topics.topics.live")
                .setDescription("Live topics of a project after each saved run")
                .build();
        this.retired = meter.counterBuilder("topics.topics.retired")
                .setDescription("Topics retired by saved nightly updates, by reason: low_share, cap, replaced")
                .build();
        this.born = meter.counterBuilder("topics.topics.born")
                .setDescription("Topics born by saved runs, by mode: first_build, nightly_update")
                .build();
        this.churn = meter.histogramBuilder("topics.run.churn")
                .setDescription("Of the window traces in a topic on both the last and this saved night, the share "
                        + "whose topic changed")
                .build();
        this.churnAny = meter.histogramBuilder("topics.run.churn_any")
                .setDescription("Of the window traces labelled on both the last and this saved night, the share "
                        + "whose label changed, Other included")
                .build();
        this.countFailures = meter.counterBuilder("topics.nightly.count_failures")
                .setDescription("Workspaces whose window count read failed, by error_type: the night skips all "
                        + "their projects")
                .build();
        this.takeBackFailures = meter.counterBuilder("topics.save.take_back_failures")
                .setDescription("Failed saves whose trace_topics rows could not be deleted, by error_type: readers "
                        + "see them until the project's next saved run")
                .build();
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's window traces labelled Other, the stats.other_share of its "
                        + "topic_runs row")
                .build();
        this.apiDuration = meter.histogramBuilder("topics.api.duration_seconds").setUnit("s")
                .setDescription("Duration of a topics read API request after its project check, by status: ready, "
                        + "collecting, disabled, error")
                .build();
        this.apiStale = meter.counterBuilder("topics.api.stale_traces")
                .setDescription("Traces a topics read API response counted as Other because their topic is no "
                        + "longer live: an arrival before a night, a trace past the night's maxVectors, or a night "
                        + "that saved during the request")
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

    void qwen(@NonNull String workspaceId, @NonNull String kind, @NonNull String outcome) {
        qwenCalls.add(1, names.builder(workspaceId).put(KIND, kind).put(OUTCOME, outcome).build());
    }

    void topics(@NonNull String workspaceId, @NonNull TopicRun.Mode mode, int live, int bornTonight) {
        liveTopics.record(live, names.of(workspaceId));
        if (bornTonight > 0) {
            born.add(bornTonight, names.builder(workspaceId).put(MODE, mode.getValue()).build());
        }
    }

    void retired(@NonNull String workspaceId, @NonNull String reason) {
        retired.add(1, names.builder(workspaceId).put(REASON, reason).build());
    }

    void churn(@NonNull String workspaceId, @NonNull TopicChurn.Churn value) {
        var attributes = names.of(workspaceId);
        churn.record(value.churn(), attributes);
        churnAny.record(value.churnAny(), attributes);
    }

    void saved(@NonNull String workspaceId, @NonNull String table, long rows) {
        saveRows.add(rows, names.builder(workspaceId).put(TABLE, table).build());
    }

    void countFailure(@NonNull String workspaceId, @NonNull Throwable error) {
        countFailures.add(1,
                names.builder(workspaceId).put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    void takeBackFailure(@NonNull String workspaceId, @NonNull Throwable error) {
        takeBackFailures.add(1,
                names.builder(workspaceId).put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, names.of(workspaceId));
    }

    void budgetUsed(@NonNull Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }

    /** {@code status} is null for a request that failed, recorded as {@code error}. */
    void apiRequest(ProjectTopics.Status status, @NonNull Duration duration) {
        apiDuration.record(duration.toNanos() / 1e9,
                Attributes.of(STATUS, status == null ? API_STATUS_ERROR : status.getValue()));
    }

    /** {@code workspace} holds both workspace attributes, with the name the request came with. */
    void apiStale(@NonNull AttributesBuilder workspace, long traces) {
        apiStale.add(traces, workspace.build());
    }
}
