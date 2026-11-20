package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The topic builds' metrics. Every metric carries {@code workspace_id} and {@code workspace_name}.
 */
@Singleton
public class TopicsMetrics {

    static final String FIRST_BUILD_GATE = "topics.first_build.gate";
    static final String FIRST_BUILD_LONGEST_WAIT = "topics.first_build.gate.longest_wait_seconds";
    static final String FIRST_BUILD_WAITING = "topics.first_build.waiting_projects";

    /** The run could not start: its attempt's write on {@code topic_projects}, or the read before it, failed. */
    static final String ERROR_START_FAILED = "start_failed";

    private static final AttributeKey<String> WORKSPACE_ID = stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME = stringKey("workspace_name");
    private static final AttributeKey<String> STATUS = stringKey("status");
    private static final AttributeKey<String> ERROR_CODE = stringKey("error_code");
    private static final AttributeKey<String> PHASE = stringKey("phase");
    private static final AttributeKey<String> OUTCOME = stringKey("outcome");
    private static final AttributeKey<String> CALL = stringKey("call");
    private static final AttributeKey<String> TABLE = stringKey("table");
    private static final AttributeKey<String> TRIGGER = stringKey("trigger");

    private final Function<String, String> names;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram otherShare;
    private final LongCounter firstBuildGate;
    private final LongCounter labelPasses;
    private volatile FirstBuildState firstBuildState;

    /**
     * What one first-build run saw, per workspace, with the workspace names resolved when the run set it: the longest
     * backfill wait in seconds, and the projects waiting for a list.
     */
    private record FirstBuildState(@NonNull Instant until, @NonNull Map<Attributes, Long> longestWaitSeconds,
            @NonNull Map<Attributes, Long> waitingProjects) {
    }

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
                .setDescription("Duration of each phase of a run: window, kmeans, qwen, assign, save, label")
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
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's traces labelled Other, among the traces it read (the window, "
                        + "or its sample): the other_share of its logged stats")
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
}
