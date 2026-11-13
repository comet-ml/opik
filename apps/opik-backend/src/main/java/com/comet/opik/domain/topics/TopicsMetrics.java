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
import java.util.Optional;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The topic builds' metrics. Every metric of one workspace's work carries {@code workspace_id} and
 * {@code workspace_name}; only {@code topics.nightly.budget_used_seconds}, a whole night's, has neither.
 */
@Singleton
public class TopicsMetrics {

    static final String DECISION_SKIP = "skip";
    static final String DECISION_NIGHTLY_UPDATE = "nightly_update";
    static final String DECISION_NOT_REACHED = "not_reached";

    /**
     * Why the night skipped a project: under {@code minVectors}, deleted, its lock held, its last saved run already
     * covers the night, paused after its last runs failed, or no live topic left when the night took its lock.
     */
    static final String REASON_SMALL = "small";
    static final String REASON_DELETED = "deleted";
    static final String REASON_LOCKED = "locked";
    static final String REASON_ALREADY_SAVED = "already_saved";
    static final String REASON_PAUSED = "paused";
    static final String REASON_NO_LIST = "no_list";

    static final String FIRST_BUILD_GATE = "topics.first_build.gate";
    static final String FIRST_BUILD_LONGEST_WAIT = "topics.first_build.gate.longest_wait_seconds";
    static final String FIRST_BUILD_WAITING = "topics.first_build.waiting_projects";

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
    private static final AttributeKey<String> CALL = stringKey("call");
    private static final AttributeKey<String> TABLE = stringKey("table");
    private static final AttributeKey<String> TRIGGER = stringKey("trigger");

    private final TopicsWorkspaceNames names;
    private final LongCounter projects;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram otherShare;
    private final LongCounter firstBuildGate;
    private final LongCounter labelPasses;
    private final DoubleHistogram budgetUsed;
    private final DoubleHistogram liveTopics;
    private final LongCounter retired;
    private final LongCounter born;
    private final DoubleHistogram churn;
    private final DoubleHistogram churnAny;
    private final LongCounter countFailures;
    private volatile FirstBuildState firstBuildState;

    /**
     * What one first-build run saw, per workspace, with the workspace names resolved when the run set it: the longest
     * backfill wait in seconds, and the projects waiting for a list.
     */
    private record FirstBuildState(@NonNull Instant until, @NonNull Map<Attributes, Long> longestWaitSeconds,
            @NonNull Map<Attributes, Long> waitingProjects) {
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
                .setDescription("Projects the nightly job looked at, by decision: nightly_update, not_reached (the "
                        + "night's budget ran out), skip (by reason: small, under minVectors; deleted; locked, its "
                        + "lock held by another run; already_saved, its last saved run already covers the night; "
                        + "paused, its last 3 runs failed within 7 nights; no_list, no live topic left)")
                .build();
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code; start_failed when the run "
                        + "could not start, abandoned when housekeeping failed a run its pod left running")
                .build();
        this.runDuration = meter.histogramBuilder("topics.run.duration_seconds").setUnit("s")
                .setDescription("Duration of each phase of a run: window, kmeans, qwen, assign, save (first build); "
                        + "window, state, update, save (nightly update); label (both)")
                .build();
        this.qwenCalls = meter.counterBuilder("topics.qwen.calls")
                .setDescription("Qwen calls, by call (author) and outcome: ok, timeout, error, bad_answer")
                .build();
        this.saveRows = meter.counterBuilder("topics.save.rows")
                .setDescription("Rows a saved run wrote, by table")
                .build();
        this.firstBuildGate = meter.counterBuilder(FIRST_BUILD_GATE)
                .setDescription("Projects without a list each first-build run found, by the outcome of the vectors "
                        + "backfill gate: open (the project's backfill drained), waiting, timed_out_open (the backfill "
                        + "ran past topics.firstBuild.backfillMaxWait and at least 99% of its traces have a vector, so "
                        + "the list is built) or timed_out_waiting (past it with fewer: the project keeps waiting)")
                .build();
        meter.gaugeBuilder(FIRST_BUILD_LONGEST_WAIT).ofLongs().setUnit("s")
                .setDescription("Per workspace, how long the oldest vectors backfill a first build waits for has run, "
                        + "as the last first-build run on this pod saw it; 0 with no waiting project. Published for "
                        + "two first-build intervals after a run: aggregate with max")
                .buildWithCallback(measurement -> firstBuildState()
                        .ifPresent(state -> state.longestWaitSeconds().forEach(
                                (attributes, seconds) -> measurement.record(seconds, attributes))));
        meter.gaugeBuilder(FIRST_BUILD_WAITING).ofLongs()
                .setDescription("Per workspace, the projects with enough vectors and no list whose first failed first "
                        + "build started more than a day ago, as the last first-build run on this pod saw them. "
                        + "Published for two first-build intervals after a run: aggregate with max")
                .buildWithCallback(measurement -> firstBuildState()
                        .ifPresent(state -> state.waitingProjects().forEach(
                                (attributes, projects) -> measurement.record(projects, attributes))));
        this.labelPasses = meter.counterBuilder("topics.label.passes")
                .setDescription("Labelling passes over a saved run's window, by trigger (run: right after the save; "
                        + "resume: the first-build job going on with a pass that did not finish) and outcome "
                        + "(complete, or incomplete when the pass was cut or failed)")
                .build();
        this.budgetUsed = meter.histogramBuilder("topics.nightly.budget_used_seconds").setUnit("s")
                .setDescription("Wall time of the night, from its start to its last run's end")
                .build();
        this.liveTopics = meter.histogramBuilder("topics.topics.live")
                .setDescription("Live topics of a project after each saved nightly update")
                .build();
        this.retired = meter.counterBuilder("topics.topics.retired")
                .setDescription("Topics retired by saved nightly updates, by reason")
                .build();
        this.born = meter.counterBuilder("topics.topics.born")
                .setDescription("Topics born by saved nightly updates")
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
                .setDescription("Projects whose window count read failed, by error_type: the night skips them")
                .build();
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's traces labelled Other, among the traces it read (the window, "
                        + "or its sample): the stats.other_share of its topic_runs row")
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

    void qwen(@NonNull String workspaceId, @NonNull TopicAlgorithm.Call call, @NonNull String outcome) {
        qwenCalls.add(1, names.builder(workspaceId).put(CALL, call.getValue()).put(OUTCOME, outcome).build());
    }

    /** A saved nightly update's live topics and the topics it gave birth to. */
    void topics(@NonNull String workspaceId, int live, int bornTonight) {
        liveTopics.record(live, names.of(workspaceId));
        if (bornTonight > 0) {
            born.add(bornTonight, names.of(workspaceId));
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

    void countFailure(@NonNull String workspaceId, @NonNull Throwable error) {
        countFailures.add(1,
                names.builder(workspaceId).put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    void budgetUsed(@NonNull Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }

    void saved(@NonNull String workspaceId, @NonNull String table, long rows) {
        saveRows.add(rows, names.builder(workspaceId).put(TABLE, table).build());
    }

    void labelPass(@NonNull String workspaceId, @NonNull TopicWindowLabelService.Trigger trigger, boolean complete) {
        labelPasses.add(1, names.builder(workspaceId).put(TRIGGER, trigger.getValue())
                .put(OUTCOME, complete ? "complete" : "incomplete").build());
    }

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, names.of(workspaceId));
    }

    void firstBuildGate(@NonNull String workspaceId, @NonNull TopicsBackfillGate.State state) {
        firstBuildGate.add(1, names.builder(workspaceId).put(OUTCOME, state.getValue()).build());
    }

    /**
     * A first-build run's longest backfill wait and its projects waiting for a list, per workspace, published for
     * {@code kept} from now.
     */
    void firstBuildState(@NonNull Map<String, Duration> longestWaits, @NonNull Map<String, Long> waitingProjects,
            @NonNull Duration kept) {
        var seconds = new LinkedHashMap<Attributes, Long>();
        longestWaits.forEach((workspaceId, wait) -> seconds.put(names.of(workspaceId), wait.toSeconds()));
        var waiting = new LinkedHashMap<Attributes, Long>();
        waitingProjects.forEach((workspaceId, projects) -> waiting.put(names.of(workspaceId), projects));
        firstBuildState = new FirstBuildState(Instant.now().plus(kept), seconds, waiting);
    }

    private Optional<FirstBuildState> firstBuildState() {
        var state = firstBuildState;
        return state != null && Instant.now().isBefore(state.until()) ? Optional.of(state) : Optional.empty();
    }
}
