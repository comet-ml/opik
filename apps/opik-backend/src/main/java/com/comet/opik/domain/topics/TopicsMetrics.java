package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.domain.WorkspaceNameService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The topic builds' and the topics read API's metrics. Every metric of one workspace's work carries
 * {@code workspace_id} and {@code workspace_name}; {@code topics.nightly.budget_used_seconds} and
 * {@code topics.nightly.nights}, a whole night's, have neither, nor does {@code topics.api.duration_seconds}, where
 * every caller's workspace would add series.
 */
@Singleton
public class TopicsMetrics {

    static final String FIRST_BUILD_GATE = "topics.first_build.gate";
    static final String FIRST_BUILD_LONGEST_WAIT = "topics.first_build.gate.longest_wait_seconds";
    static final String FIRST_BUILD_WAITING = "topics.first_build.waiting_projects";
    static final String NIGHTLY_DECISIONS = "topics.nightly.decisions";
    static final String NIGHTLY_NIGHTS = "topics.nightly.nights";

    /** The run could not start: its attempt's write on {@code topic_projects}, or the read before it, failed. */
    static final String ERROR_START_FAILED = "start_failed";
    static final String API_STATUS_ERROR = "error";

    private static final AttributeKey<String> WORKSPACE_ID = stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME = stringKey("workspace_name");
    private static final AttributeKey<String> STATUS = stringKey("status");
    private static final AttributeKey<String> ERROR_CODE = stringKey("error_code");
    private static final AttributeKey<String> PHASE = stringKey("phase");
    private static final AttributeKey<String> OUTCOME = stringKey("outcome");
    private static final AttributeKey<String> CALL = stringKey("call");
    private static final AttributeKey<String> TABLE = stringKey("table");
    private static final AttributeKey<String> TRIGGER = stringKey("trigger");
    private static final AttributeKey<String> REASON = stringKey("reason");
    private static final AttributeKey<String> DECISION = stringKey("decision");

    private final Function<String, String> names;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram otherShare;
    private final LongCounter firstBuildGate;
    private final LongCounter labelPasses;
    private final DoubleHistogram budgetUsed;
    private final LongCounter nightlyDecisions;
    private final LongCounter nights;
    private final DoubleHistogram liveTopics;
    private final LongCounter retired;
    private final LongCounter born;
    private final DoubleHistogram churn;
    private final DoubleHistogram churnAny;
    private volatile FirstBuildState firstBuildState;

    /**
     * What one first-build run saw, per workspace, with the workspace names resolved when the run set it: the longest
     * backfill wait in seconds, and the projects waiting for a list.
     */
    private record FirstBuildState(@NonNull Instant until, @NonNull Map<Attributes, Long> longestWaitSeconds,
            @NonNull Map<Attributes, Long> waitingProjects) {
    }
    private final DoubleHistogram apiDuration;

    @Inject
    public TopicsMetrics(@NonNull WorkspaceNameService names) {
        this(GlobalOpenTelemetry.get().getMeter("opik.topics"), names::nameOrId);
    }

    /** Workspace names fall back to the id. */
    TopicsMetrics(@NonNull Meter meter) {
        this(meter, workspaceId -> workspaceId);
    }

    TopicsMetrics(@NonNull Meter meter, @NonNull Function<String, String> names) {
        this.names = names;
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code; start_failed when the run "
                        + "could not start, abandoned when the project's next run found one its pod left running")
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
        this.nightlyDecisions = meter.counterBuilder(NIGHTLY_DECISIONS)
                .setDescription("Projects the nightly job decided on, by decision (nightly_update, skip, not_reached, "
                        + "count_failed) and, for a skip, reason (small, deleted, paused, already_saved, no_list, "
                        + "locked)")
                .build();
        this.nights = meter.counterBuilder(NIGHTLY_NIGHTS)
                .setDescription("Nights of the nightly job, by outcome: finished, or failed when a read of the "
                        + "workspaces, the lists or the projects failed")
                .build();
        this.liveTopics = meter.histogramBuilder("topics.topics.live")
                .setDescription("Live topics of a project after each saved nightly update")
                .build();
        this.retired = meter.counterBuilder("topics.retired")
                .setDescription("Topics retired (deleted) by saved nightly updates, by reason: low_share, replaced")
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
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's traces labelled Other, among the traces it read (the window, "
                        + "or its sample): the other_share of its logged stats")
                .build();
        this.apiDuration = meter.histogramBuilder("topics.api.duration_seconds").setUnit("s")
                .setDescription("Duration of a topics read API request after its project check, by status: ready, "
                        + "collecting, disabled, error")
                .build();
    }

    void run(@NonNull String workspaceId, @NonNull TopicProject.RunStatus status, String errorCode) {
        var attributes = workspace(workspaceId).put(STATUS, status.getValue());
        if (errorCode != null) {
            attributes.put(ERROR_CODE, errorCode);
        }
        runs.add(1, attributes.build());
    }

    void phase(@NonNull String workspaceId, @NonNull String phase, @NonNull Duration duration) {
        runDuration.record(duration.toNanos() / 1e9, workspace(workspaceId).put(PHASE, phase).build());
    }

    void qwen(@NonNull String workspaceId, @NonNull LlmCall call, @NonNull String outcome) {
        qwenCalls.add(1, workspace(workspaceId).put(CALL, call.getValue()).put(OUTCOME, outcome).build());
    }

    /** A saved nightly update's live topics and the topics it gave birth to. */
    void topics(@NonNull String workspaceId, int live, int bornTonight) {
        liveTopics.record(live, workspace(workspaceId).build());
        if (bornTonight > 0) {
            born.add(bornTonight, workspace(workspaceId).build());
        }
    }

    void retired(@NonNull String workspaceId, @NonNull NightState.RetiredReason reason) {
        retired.add(1, workspace(workspaceId).put(REASON, reason.value()).build());
    }

    void churn(@NonNull String workspaceId, @NonNull TopicChurn.Churn value) {
        var attributes = workspace(workspaceId).build();
        churn.record(value.churn(), attributes);
        churnAny.record(value.churnAny(), attributes);
    }

    void budgetUsed(@NonNull Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }

    /** {@code reason} is null for a decision other than skip. */
    void nightlyDecision(@NonNull String workspaceId, @NonNull String decision, String reason, long projects) {
        var attributes = workspace(workspaceId).put(DECISION, decision);
        if (reason != null) {
            attributes.put(REASON, reason);
        }
        nightlyDecisions.add(projects, attributes.build());
    }

    void night(@NonNull String outcome, long nights) {
        this.nights.add(nights, Attributes.of(OUTCOME, outcome));
    }

    void saved(@NonNull String workspaceId, @NonNull String table, long rows) {
        saveRows.add(rows, workspace(workspaceId).put(TABLE, table).build());
    }

    void labelPass(@NonNull String workspaceId, @NonNull TopicWindowLabelService.Trigger trigger, boolean complete) {
        labelPasses.add(1, workspace(workspaceId).put(TRIGGER, trigger.getValue())
                .put(OUTCOME, complete ? "complete" : "incomplete").build());
    }

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, workspace(workspaceId).build());
    }

    private AttributesBuilder workspace(String workspaceId) {
        return Attributes.builder().put(WORKSPACE_ID, workspaceId).put(WORKSPACE_NAME, names.apply(workspaceId));
    }

    void firstBuildGate(@NonNull String workspaceId, @NonNull TopicsBackfillGate.State state) {
        firstBuildGate.add(1, workspace(workspaceId).put(OUTCOME, state.getValue()).build());
    }

    /**
     * A first-build run's longest backfill wait and its projects waiting for a list, per workspace, published for
     * {@code kept} from now.
     */
    void firstBuildState(@NonNull Map<String, Duration> longestWaits, @NonNull Map<String, Long> waitingProjects,
            @NonNull Duration kept) {
        var seconds = new LinkedHashMap<Attributes, Long>();
        longestWaits.forEach((workspaceId, wait) -> seconds.put(workspace(workspaceId).build(), wait.toSeconds()));
        var waiting = new LinkedHashMap<Attributes, Long>();
        waitingProjects.forEach((workspaceId, projects) -> waiting.put(workspace(workspaceId).build(), projects));
        firstBuildState = new FirstBuildState(Instant.now().plus(kept), seconds, waiting);
    }

    private Optional<FirstBuildState> firstBuildState() {
        var state = firstBuildState;
        return state != null && Instant.now().isBefore(state.until()) ? Optional.of(state) : Optional.empty();
    }

    /** {@code status} is null when the request failed before it had one: recorded as {@code error}. */
    void apiRequest(@Nullable ProjectTopics.Status status, @NonNull Duration duration) {
        apiDuration.record(duration.toNanos() / 1e9,
                Attributes.of(STATUS, status == null ? API_STATUS_ERROR : status.getValue()));
    }
}
