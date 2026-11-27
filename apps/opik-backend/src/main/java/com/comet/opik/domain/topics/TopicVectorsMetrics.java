package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.db.HasValue;
import com.comet.opik.infrastructure.inference.InferenceErrorCode;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/** The topic vectors tick's metrics. */
@Singleton
class TopicVectorsMetrics {

    /** A workspace as the metrics tag it: its id, and its attributes with the name looked up once per tick. */
    record Workspace(@NonNull String id, @NonNull Attributes attributes) {

        /** Its name falls back to the id, as for a failed lookup: for a workspace the tick had no time to read. */
        static Workspace unnamed(@NonNull String id) {
            return new Workspace(id, workspace(id, id).build());
        }

        /** The name its attributes carry: the one looked up, or the id. */
        String name() {
            var name = attributes.get(WORKSPACE_NAME_KEY);
            return name == null ? id : name;
        }
    }

    private final WorkspaceNameService names;

    private final LongCounter written;
    private final LongCounter skipped;
    private final LongCounter embedFailures;
    private final LongCounter readFailures;
    private final LongCounter readsNarrowed;
    private final LongCounter workspacesNotRead;
    private final LongCounter discoveries;
    private final LongCounter longWindowMissed;
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    @Inject
    TopicVectorsMetrics(@NonNull WorkspaceNameService names) {
        this.names = names;
        var meter = GlobalOpenTelemetry.get().getMeter("opik.topics");
        this.written = meter.counterBuilder("topics.vectors.written_total")
                .setDescription("Vectors written to topic_vectors, by workspace")
                .build();
        this.skipped = meter.counterBuilder("topics.vectors.skipped_total")
                .setDescription("Traces a tick found but did not write, by reason and workspace; a later tick finds "
                        + "them again")
                .build();
        this.embedFailures = meter.counterBuilder("topics.vectors.embed.failures_total")
                .setDescription("Embed calls that failed, by HTTP status (0 without a response), code, error type and "
                        + "workspace")
                .build();
        this.readFailures = meter.counterBuilder("topics.vectors.read.failures_total")
                .setDescription("Reads that failed, by phase: find (one workspace's window read), discover (one "
                        + "workspace's project discovery) or backfill (one project's window read), workspace and error "
                        + "type")
                .build();
        this.readsNarrowed = meter.counterBuilder("topics.vectors.read.narrowed_total")
                .setDescription("Fresh workspace reads over a find size guard that were read again over the first half "
                        + "of their status span, by workspace")
                .build();
        this.workspacesNotRead = meter.counterBuilder("topics.vectors.workspaces_not_read_total")
                .setDescription("Enabled workspaces a tick's workspace read did not read, by reason and workspace: "
                        + "budget (the tick's budget ran out) or over_excluded (more projects in backfill than the "
                        + "read can leave out)")
                .build();
        this.discoveries = meter.counterBuilder("topics.vectors.discoveries_total")
                .setDescription("Project discoveries a tick started, by outcome and workspace: complete (its rows "
                        + "written), cut (the tick's budget ended it before its last slice; the next tick starts it "
                        + "again) or failed (a read or the write failed)")
                .build();
        this.longWindowMissed = meter.counterBuilder("topics.vectors.long_window.missed_total")
                .setDescription("Vectors the long-window pass wrote for statuses older than the short window, that is, "
                        + "rows the short window missed, by workspace; should stay near zero")
                .build();
        this.embedDuration = meter.histogramBuilder("topics.vectors.embed.duration_seconds").setUnit("s")
                .setDescription("Wall time of one embed call, by outcome (ok or the error code) and workspace")
                .build();
        this.phaseDuration = meter.histogramBuilder("topics.vectors.tick.phase_seconds").setUnit("s")
                .setDescription("Time one tick spent in each phase: discover (project discovery), find (workspace "
                        + "reads, summed over workspaces), embed (their embed calls, writes and arrival steps, summed "
                        + "over workspaces), backfill (project reads, embed calls, writes and arrival steps)")
                .build();
    }

    /**
     * The workspace's metric attributes. The name lookup is a Redis read, and on a miss a call to the React service, so
     * it runs on a blocking-friendly thread and never in the tick's reactive chain.
     */
    Mono<Workspace> workspace(@NonNull String workspaceId) {
        return Mono.fromCallable(() -> new Workspace(workspaceId,
                workspace(workspaceId, names.nameOrId(workspaceId)).build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    void written(@NonNull Workspace workspace, int rows) {
        if (rows > 0) {
            written.add(rows, workspace.attributes());
        }
    }

    void skipped(@NonNull Workspace workspace, @NonNull SkipReason reason, int traces) {
        if (traces > 0) {
            skipped.add(traces, workspace.attributes().toBuilder().put(stringKey("reason"), reason.getValue()).build());
        }
    }

    /** One embed call: its duration, and its failure when it failed. */
    void embedCall(@NonNull Workspace workspace, @NonNull Duration duration, @Nullable InferenceException failure) {
        var attributes = workspace.attributes();
        var outcome = failure == null ? "ok" : metricCode(failure.getCode());
        embedDuration.record(duration.toNanos() / 1e9,
                attributes.toBuilder().put(stringKey("outcome"), outcome).build());
        if (failure != null) {
            embedFailures.add(1, attributes.toBuilder()
                    .put(longKey("http_status"), (long) failure.getHttpStatus())
                    .put(stringKey("code"), outcome)
                    .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(failure))
                    .build());
        }
    }

    void longWindowMissed(@NonNull Workspace workspace, int vectors) {
        if (vectors > 0) {
            longWindowMissed.add(vectors, workspace.attributes());
        }
    }

    void readFailure(@NonNull String phase, @NonNull Workspace workspace, @NonNull Throwable error) {
        readFailures.add(1, workspace.attributes().toBuilder().put(stringKey("phase"), phase)
                .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    void readNarrowed(@NonNull Workspace workspace) {
        readsNarrowed.add(1, workspace.attributes());
    }

    void workspaceNotRead(@NonNull Workspace workspace, @NonNull NotRead reason) {
        workspacesNotRead.add(1,
                workspace.attributes().toBuilder().put(stringKey("reason"), reason.getValue()).build());
    }

    void discovery(@NonNull Workspace workspace, @NonNull DiscoveryOutcome outcome) {
        discoveries.add(1, workspace.attributes().toBuilder().put(stringKey("outcome"), outcome.getValue()).build());
    }

    /** How a project discovery a tick started ended. */
    @Getter
    @RequiredArgsConstructor
    enum DiscoveryOutcome implements HasValue {
        COMPLETE("complete"),
        CUT("cut"),
        FAILED("failed");

        private final String value;
    }

    /** Why a tick's workspace read did not read an enabled workspace. */
    @Getter
    @RequiredArgsConstructor
    enum NotRead implements HasValue {
        BUDGET("budget"),
        OVER_EXCLUDED("over_excluded");

        private final String value;
    }

    static AttributesBuilder workspace(@NonNull String workspaceId, @NonNull String workspaceName) {
        return Attributes.builder().put(WORKSPACE_ID_KEY, workspaceId).put(WORKSPACE_NAME_KEY, workspaceName);
    }

    /** A tick's time in one phase. */
    void phase(@NonNull String phase, @NonNull Duration duration) {
        phaseDuration.record(duration.toNanos() / 1e9, Attributes.of(stringKey("phase"), phase));
    }

    /** The service's error code as a metric attribute, from the known set only, so the attribute stays bounded. */
    static String metricCode(String code) {
        boolean known = InferenceErrorCode.find(code).isPresent() || InferenceException.OUT_OF_TIME.equals(code)
                || InferenceException.CLIENT_BUSY.equals(code);
        return known ? code : "unknown";
    }
}
