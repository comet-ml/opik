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
import java.util.function.Function;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The topic builds' metrics. Every metric carries {@code workspace_id} and {@code workspace_name}.
 */
@Singleton
public class TopicsMetrics {

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

    private final Function<String, String> names;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram otherShare;

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

    void otherShare(@NonNull String workspaceId, double share) {
        otherShare.record(share, workspace(workspaceId).build());
    }

    private AttributesBuilder workspace(String workspaceId) {
        return Attributes.builder().put(WORKSPACE_ID, workspaceId).put(WORKSPACE_NAME, names.apply(workspaceId));
    }
}
