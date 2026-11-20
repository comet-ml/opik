package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.InferenceErrorCode;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/** The topic vectors tick's metrics, and the last snapshots its gauges publish. */
@Singleton
class TopicVectorsMetrics {

    static final String OLDEST_UNVECTORED = "topics.vectors.oldest_unvectored_seconds";
    static final String UNDRAINED_PROJECTS = "topics.vectors.backfill.undrained_projects";
    static final String OLDEST_BACKFILL = "topics.vectors.backfill.oldest_started_seconds";

    private final TopicsConfig config;
    private final WorkspaceNameService names;
    private final Clock clock;

    /**
     * The oldest done status without a vector per workspace ({@link Instant#MAX} with none), with the workspace's name,
     * as this pod's latest tick found it, and when. Only the leader ticks, so on any other pod it is null or goes stale.
     */
    private volatile OldestSnapshot lastOldest;

    private record OldestSnapshot(Instant at, Map<String, Oldest> oldest) {
    }

    // The name is resolved when the snapshot is taken: the gauge's callback must not look names up.
    private record Oldest(String workspaceName, Instant statusAt) {
    }

    /** Per workspace, the projects not drained after this pod's latest tick; published like {@link #lastOldest}. */
    private volatile UndrainedSnapshot lastUndrained;

    private record UndrainedSnapshot(Instant at, Map<String, Undrained> undrained) {
    }

    private record Undrained(String workspaceName, int projects, @Nullable Instant oldestStartedAt) {
    }

    private final LongCounter written;
    private final LongCounter skipped;
    private final LongCounter embedFailures;
    private final LongCounter readFailures;
    private final LongCounter workspacesNotRead;
    private final LongCounter longWindowMissed;
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    @Inject
    TopicVectorsMetrics(@NonNull @Config("topics") TopicsConfig config, @NonNull WorkspaceNameService names) {
        this(config, names, GlobalOpenTelemetry.get().getMeter("opik.topics"), Clock.systemUTC());
    }

    TopicVectorsMetrics(@NonNull TopicsConfig config, @NonNull WorkspaceNameService names, @NonNull Meter meter,
            @NonNull Clock clock) {
        this.config = config;
        this.names = names;
        this.clock = clock;
        meter.gaugeBuilder(OLDEST_UNVECTORED).setUnit("s")
                .setDescription("Age of the oldest done trace_summary without a vector, per enabled workspace, among "
                        + "the projects whose backfill has drained, as the latest tick's workspace read found it; 0 "
                        + "with none. Projects still in backfill are not in it: see topics.vectors.backfill.*. "
                        + "Published only by the pod that ticked within two tick intervals; a workspace whose read "
                        + "failed or was not reached has no value")
                .buildWithCallback(measurement -> {
                    var snapshot = lastOldest;
                    if (snapshot == null || !fresh(snapshot.at())) {
                        return;
                    }
                    var now = clock.instant();
                    snapshot.oldest().forEach((workspaceId, oldest) -> measurement.record(
                            oldest.statusAt().equals(Instant.MAX) ? 0 : seconds(oldest.statusAt(), now),
                            workspace(workspaceId, oldest.workspaceName()).build()));
                });
        meter.gaugeBuilder(UNDRAINED_PROJECTS)
                .setDescription("Projects whose backfill has not drained, per enabled workspace, after the latest "
                        + "tick. Published only by the pod that ticked within two tick intervals")
                .buildWithCallback(measurement -> {
                    var snapshot = lastUndrained;
                    if (snapshot == null || !fresh(snapshot.at())) {
                        return;
                    }
                    snapshot.undrained().forEach((workspaceId, undrained) -> measurement.record(undrained.projects(),
                            workspace(workspaceId, undrained.workspaceName()).build()));
                });
        meter.gaugeBuilder(OLDEST_BACKFILL).setUnit("s")
                .setDescription("Age of the oldest backfill not drained yet, per enabled workspace, from its start; 0 "
                        + "with none. Published only by the pod that ticked within two tick intervals")
                .buildWithCallback(measurement -> {
                    var snapshot = lastUndrained;
                    if (snapshot == null || !fresh(snapshot.at())) {
                        return;
                    }
                    var now = clock.instant();
                    snapshot.undrained().forEach((workspaceId, undrained) -> measurement.record(
                            undrained.oldestStartedAt() == null ? 0 : seconds(undrained.oldestStartedAt(), now),
                            workspace(workspaceId, undrained.workspaceName()).build()));
                });
        this.written = meter.counterBuilder("topics.vectors.written")
                .setDescription("Vectors written to topic_vectors, by workspace")
                .build();
        this.skipped = meter.counterBuilder("topics.vectors.skipped")
                .setDescription("Traces a tick found but did not write, by reason and workspace; a later tick finds "
                        + "them again")
                .build();
        this.embedFailures = meter.counterBuilder("topics.vectors.embed.failures")
                .setDescription("Embed calls that failed, by HTTP status (0 without a response), code, error type and "
                        + "workspace")
                .build();
        this.readFailures = meter.counterBuilder("topics.vectors.read.failures")
                .setDescription("Reads that failed, by phase: find (one workspace's window read), discover (one "
                        + "workspace's project discovery) or backfill (one project's window read), workspace and error "
                        + "type")
                .build();
        this.workspacesNotRead = meter.counterBuilder("topics.vectors.workspaces_not_read")
                .setDescription("Enabled workspaces a tick's workspace read did not read, by workspace: its budget "
                        + "ran out, or the workspace has more projects in backfill than the read can leave out (see "
                        + UNDRAINED_PROJECTS + "). Such a workspace has no value in " + OLDEST_UNVECTORED
                        + " for that tick")
                .build();
        this.longWindowMissed = meter.counterBuilder("topics.vectors.long_window.missed")
                .setDescription("Vectors the long-window pass wrote for statuses older than the short window, that is, "
                        + "rows the short window missed, by workspace; should stay near zero")
                .build();
        this.embedDuration = meter.histogramBuilder("topics.vectors.embed.duration_seconds").setUnit("s")
                .setDescription("Wall time of one embed call, by outcome (ok or the error code) and workspace")
                .build();
        this.phaseDuration = meter.histogramBuilder("topics.vectors.tick.phase_seconds").setUnit("s")
                .setDescription("Time one tick spent in each phase: discover (project discovery), find (workspace "
                        + "reads, summed over workspaces), embed (their embed calls and writes, summed over "
                        + "workspaces), backfill (project reads, embed calls and writes)")
                .build();
    }

    void written(@NonNull String workspaceId, int rows) {
        if (rows > 0) {
            written.add(rows, workspace(workspaceId).build());
        }
    }

    void skipped(@NonNull String workspaceId, @NonNull SkipReason reason, int traces) {
        if (traces > 0) {
            skipped.add(traces, workspace(workspaceId).put(stringKey("reason"), reason.getValue()).build());
        }
    }

    /** One embed call: its duration, and its failure when it failed. */
    void embedCall(@NonNull String workspaceId, @NonNull Duration duration, @Nullable InferenceException failure) {
        var workspace = workspace(workspaceId).build();
        var outcome = failure == null ? "ok" : metricCode(failure.getCode());
        embedDuration.record(duration.toNanos() / 1e9,
                workspace.toBuilder().put(stringKey("outcome"), outcome).build());
        if (failure != null) {
            embedFailures.add(1, workspace.toBuilder()
                    .put(longKey("http_status"), (long) failure.getHttpStatus())
                    .put(stringKey("code"), outcome)
                    .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(failure))
                    .build());
        }
    }

    void longWindowMissed(@NonNull String workspaceId, int vectors) {
        if (vectors > 0) {
            longWindowMissed.add(vectors, workspace(workspaceId).build());
        }
    }

    void readFailure(@NonNull String phase, @NonNull String workspaceId, @NonNull Throwable error) {
        readFailures.add(1, workspace(workspaceId).put(stringKey("phase"), phase)
                .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    void workspacesNotRead(@NonNull List<String> workspaceIds) {
        workspaceIds.forEach(workspaceId -> workspacesNotRead.add(1, workspace(workspaceId).build()));
    }

    /**
     * Replaces the snapshot the gauge publishes.
     *
     * @param oldest per workspace read, its oldest done status without a vector, {@link Instant#MAX} with none
     */
    void oldest(@NonNull Map<String, Instant> oldest) {
        lastOldest = new OldestSnapshot(clock.instant(),
                oldest.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new Oldest(names.nameOrId(entry.getKey()), entry.getValue()))));
    }

    /**
     * Replaces the snapshot the backfill gauges publish.
     *
     * @param undrained per enabled workspace, its projects not drained after the tick, none included
     */
    void undrained(@NonNull Map<String, List<TopicVectorBackfillDAO.Backfill>> undrained) {
        lastUndrained = new UndrainedSnapshot(clock.instant(),
                undrained.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> new Undrained(names.nameOrId(entry.getKey()), entry.getValue().size(),
                                entry.getValue().stream().map(TopicVectorBackfillDAO.Backfill::backfillStartedAt)
                                        .min(Comparator.naturalOrder()).orElse(null)))));
    }

    private AttributesBuilder workspace(String workspaceId) {
        return workspace(workspaceId, names.nameOrId(workspaceId));
    }

    static AttributesBuilder workspace(@NonNull String workspaceId, @NonNull String workspaceName) {
        return Attributes.builder().put(WORKSPACE_ID_KEY, workspaceId).put(WORKSPACE_NAME_KEY, workspaceName);
    }

    /** A tick's time in one phase. */
    void phase(@NonNull String phase, @NonNull Duration duration) {
        phaseDuration.record(duration.toNanos() / 1e9, Attributes.of(stringKey("phase"), phase));
    }

    /** Whether a snapshot taken at {@code at} is still this pod's: it ticked within two tick intervals. */
    private boolean fresh(Instant at) {
        var maxAge = config.getVectors().getTickInterval().toJavaDuration().multipliedBy(2);
        return Duration.between(at, clock.instant()).compareTo(maxAge) <= 0;
    }

    /** The service's error code as a metric attribute, from the known set only, so the attribute stays bounded. */
    static String metricCode(String code) {
        boolean known = InferenceErrorCode.find(code).isPresent() || InferenceException.OUT_OF_TIME.equals(code)
                || InferenceException.CLIENT_BUSY.equals(code);
        return known ? code : "unknown";
    }

    private static double seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis() / 1_000.0);
    }
}
