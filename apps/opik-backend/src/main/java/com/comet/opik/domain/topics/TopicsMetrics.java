package com.comet.opik.domain.topics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Duration;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The topic runs' metrics, on the global meter. Every metric carries {@code workspace_id} and {@code workspace_name}:
 * the caller resolves the name once, at its boundary, so recording never blocks.
 */
@Singleton
public class TopicsMetrics {

    /** The workspace a metric is recorded for. */
    public record Workspace(@NonNull String id, @NonNull String name) {
    }

    private static final AttributeKey<String> WORKSPACE_ID = stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME = stringKey("workspace_name");
    private static final AttributeKey<String> STATUS = stringKey("status");
    private static final AttributeKey<String> ERROR_CODE = stringKey("error_code");
    private static final AttributeKey<String> PHASE = stringKey("phase");
    private static final AttributeKey<String> OUTCOME = stringKey("outcome");
    private static final AttributeKey<String> RESULT = stringKey("result");
    private static final AttributeKey<String> CALL = stringKey("call");
    private static final AttributeKey<String> TABLE = stringKey("table");
    private static final AttributeKey<String> TRIGGER = stringKey("trigger");

    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter llmCalls;
    private final LongCounter saveRows;
    private final LongCounter traceTopicsInserts;
    private final LongCounter labelPasses;
    private final DoubleHistogram otherShare;

    public TopicsMetrics() {
        this(GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicsMetrics(@NonNull Meter meter) {
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code; start_failed when the run "
                        + "could not start, abandoned when the project's next run found one its pod left running")
                .build();
        this.runDuration = meter.histogramBuilder("topics.run.duration_seconds").setUnit("s")
                .setDescription("Duration of each phase of a run: window, cluster, llm, assign, save, label")
                .build();
        this.llmCalls = meter.counterBuilder("topics.llm.calls")
                .setDescription("LLM calls, by call (author) and outcome: ok, timeout, error, bad_answer")
                .build();
        this.saveRows = meter.counterBuilder("topics.save.rows")
                .setDescription("Rows a saved run wrote, by table")
                .build();
        this.traceTopicsInserts = meter.counterBuilder("topics.trace_topics.inserts")
                .setDescription("trace_topics inserts of a saved run, by result: success, error (after its retries). "
                        + "The run inserts once after its commit, the labelling pass once per page")
                .build();
        this.labelPasses = meter.counterBuilder("topics.label.passes")
                .setDescription("Labelling passes over a saved run's window, by trigger (run: right after the save; "
                        + "resume: the discovery job going on with a pass that did not finish) and outcome "
                        + "(complete; cut by its deadline or its caller; failed)")
                .build();
        // No unit: "1" would add a _ratio suffix to the Prometheus name.
        this.otherShare = meter.histogramBuilder("topics.run.other_share")
                .setDescription("Share of a saved run's traces labelled Other, among the traces it read (the window, "
                        + "or its sample): the other_share of its logged stats")
                .build();
    }

    void run(@NonNull Workspace workspace, @NonNull TopicProject.RunStatus status, String errorCode) {
        var attributes = attributes(workspace).put(STATUS, status.getValue());
        if (errorCode != null) {
            attributes.put(ERROR_CODE, errorCode);
        }
        runs.add(1, attributes.build());
    }

    void phase(@NonNull Workspace workspace, @NonNull String phase, @NonNull Duration duration) {
        runDuration.record(duration.toNanos() / 1e9, attributes(workspace).put(PHASE, phase).build());
    }

    void llm(@NonNull Workspace workspace, @NonNull LlmCall call, @NonNull String outcome) {
        llmCalls.add(1, attributes(workspace).put(CALL, call.getValue()).put(OUTCOME, outcome).build());
    }

    void saved(@NonNull Workspace workspace, @NonNull String table, long rows) {
        saveRows.add(rows, attributes(workspace).put(TABLE, table).build());
    }

    void traceTopicsInsert(@NonNull Workspace workspace, boolean success) {
        traceTopicsInserts.add(1, attributes(workspace).put(RESULT, success ? "success" : "error").build());
    }

    void labelPass(@NonNull Workspace workspace, @NonNull LabelTrigger trigger, @NonNull LabelPassOutcome outcome) {
        labelPasses.add(1, attributes(workspace).put(TRIGGER, trigger.getValue()).put(OUTCOME, outcome.getValue())
                .build());
    }

    void otherShare(@NonNull Workspace workspace, double share) {
        otherShare.record(share, attributes(workspace).build());
    }

    private static AttributesBuilder attributes(Workspace workspace) {
        return Attributes.builder().put(WORKSPACE_ID, workspace.id()).put(WORKSPACE_NAME, workspace.name());
    }
}
