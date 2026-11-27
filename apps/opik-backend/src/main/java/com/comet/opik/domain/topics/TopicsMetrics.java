package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The topic runs' and the topics read API's metrics, on the global meter. Every metric of one workspace's work
 * carries {@code workspace_id} and {@code workspace_name}: the caller resolves the name once, at its boundary, so
 * recording never blocks. {@code topics.api.duration_seconds} has neither, where every caller's workspace would add
 * series; nor has {@code topics.nightly.nights}, a whole night's.
 */
@Singleton
public class TopicsMetrics {

    static final String API_STATUS_ERROR = "error";

    /** The workspace a metric is recorded for. */
    public record Workspace(@NonNull String id, @NonNull String name) {

        /** The workspace of a run, which its subscriber puts in the Reactor context. */
        public static Mono<Workspace> fromContext() {
            return Mono.deferContextual(ctx -> Mono.just(new Workspace(ctx.get(RequestContext.WORKSPACE_ID),
                    ctx.get(RequestContext.WORKSPACE_NAME))));
        }
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
    private static final AttributeKey<String> REASON = stringKey("reason");
    private static final AttributeKey<String> DECISION = stringKey("decision");

    private static final String NIGHTLY_DECISIONS = "topics.nightly.decisions";
    private static final String NIGHTLY_NIGHTS = "topics.nightly.nights";

    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter llmCalls;
    private final LongCounter saveRows;
    private final LongCounter traceTopicsInserts;
    private final LongCounter labelPasses;
    private final DoubleHistogram otherShare;
    private final DoubleHistogram apiDuration;
    private final DoubleHistogram liveTopics;
    private final LongCounter retired;
    private final LongCounter born;
    private final DoubleHistogram churn;
    private final DoubleHistogram churnAny;
    private final LongCounter nightlyDecisions;
    private final LongCounter degradedCalls;
    private final LongCounter nights;

    public TopicsMetrics() {
        this(GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicsMetrics(@NonNull Meter meter) {
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code; start_failed when the run "
                        + "could not start, abandoned when the project's next run found one its pod left running")
                .build();
        this.runDuration = meter.histogramBuilder("topics.run.duration_seconds").setUnit("s")
                .setDescription("Duration of each phase of a run: window, cluster, llm, assign, save (discovery); "
                        + "window, state, update, llm, rows, save (nightly update); label (both)")
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
        this.liveTopics = meter.histogramBuilder("topics.topics.live")
                .setDescription("Live topics of a project after each saved nightly update")
                .build();
        this.retired = meter.counterBuilder("topics.topics.retired")
                .setDescription(
                        "Topics retired (deleted) by saved nightly updates, by reason: low_share, replaced, merged")
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
        this.nightlyDecisions = meter.counterBuilder(NIGHTLY_DECISIONS)
                .setDescription("Projects the night decided on, by decision (update: queued, skip, "
                        + "not_reached, count_failed) and, for a skip, reason (small, deleted, paused, already_saved, "
                        + "no_list). A queued run whose claim finds its night saved or no list is counted again, as a "
                        + "skip. Every series of a workspace listed at startup starts at 0; one listed later starts "
                        + "at its first decision")
                .build();
        this.degradedCalls = meter.counterBuilder("topics.nightly.degraded_calls")
                .setDescription("LLM calls of a nightly update that failed while the update went on without them, "
                        + "by call (naming, verify) and error code (llm_timeout, llm_error): their groups stay in "
                        + "Other until the next night")
                .build();
        this.nights = meter.counterBuilder(NIGHTLY_NIGHTS)
                .setDescription("Nights of the nightly job, by outcome: finished once its budget ended, counted by "
                        + "the one plan that ends the night, or failed once for each plan whose read of the night's "
                        + "projects failed, for every workspace or for one")
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

    void degradedCall(@NonNull Workspace workspace, @NonNull LlmCall call, @NonNull TopicProject.RunError code) {
        degradedCalls.add(1, attributes(workspace).put(CALL, call.getValue()).put(ERROR_CODE, code.getValue())
                .build());
    }

    /** A saved nightly update's live topics and the topics it gave birth to. */
    void topics(@NonNull Workspace workspace, int live, int bornTonight) {
        liveTopics.record(live, attributes(workspace).build());
        if (bornTonight > 0) {
            born.add(bornTonight, attributes(workspace).build());
        }
    }

    void retired(@NonNull Workspace workspace, @NonNull NightState.RetiredReason reason) {
        retired.add(1, attributes(workspace).put(REASON, reason.getValue()).build());
    }

    void churn(@NonNull Workspace workspace, @NonNull TopicChurn.Churn value) {
        var attributes = attributes(workspace).build();
        churn.record(value.churn(), attributes);
        churnAny.record(value.churnAny(), attributes);
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

    /** {@code reason} is null for a decision other than skip. */
    void nightlyDecision(@NonNull Workspace workspace, @NonNull NightDecision decision,
            @Nullable NightSkipReason reason, long projects) {
        var attributes = attributes(workspace).put(DECISION, decision.getValue());
        if (reason != null) {
            attributes.put(REASON, reason.getValue());
        }
        nightlyDecisions.add(projects, attributes.build());
    }

    void night(@NonNull NightOutcome outcome, long nights) {
        this.nights.add(nights, Attributes.of(OUTCOME, outcome.getValue()));
    }

    private static AttributesBuilder attributes(Workspace workspace) {
        return Attributes.builder().put(WORKSPACE_ID, workspace.id()).put(WORKSPACE_NAME, workspace.name());
    }

    /** {@code status} is null when the request failed before it had one: recorded as {@code error}. */
    void apiRequest(@Nullable ProjectTopics.Status status, @NonNull Duration duration) {
        apiDuration.record(duration.toNanos() / 1e9,
                Attributes.of(STATUS, status == null ? API_STATUS_ERROR : status.getValue()));
    }
}
