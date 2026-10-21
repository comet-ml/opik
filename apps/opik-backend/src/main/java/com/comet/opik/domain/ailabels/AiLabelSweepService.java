package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelSweepDAO.Candidate;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.SweepTask;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.WorkspaceHealth;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.google.common.hash.Hashing;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Runs the sweep and the headline query bucket by bucket (design §6). Buckets are built here, never in SQL: a hash
 * predicate in the query would defeat the primary key. A query that trips a guard, or fails, skips only its own
 * bucket.
 */
@Slf4j
@Singleton
public class AiLabelSweepService {

    // ClickHouse error codes of the sweep's guards: max_rows_to_read, max_execution_time, max_memory_usage.
    private static final Map<Integer, String> GUARD_CODES = Map.of(
            158, "TOO_MANY_ROWS",
            159, "TIMEOUT_EXCEEDED",
            241, "MEMORY_LIMIT_EXCEEDED");

    private final AiLabelSweepDAO sweepDAO;
    private final TransactionTemplate template;
    private final AiLabelsConfig config;

    private final DoubleHistogram duration;
    private final LongCounter guardTrips;
    private final LongCounter errors;
    // Null until a refresh completes: the gauges report nothing rather than a backlog of 0 that was never measured.
    private final AtomicReference<HealthSnapshot> health = new AtomicReference<>();

    record HealthSnapshot(List<WorkspaceHealth> workspaces, long neverEnded) {
        static final HealthSnapshot EMPTY = new HealthSnapshot(List.of(), 0);
    }

    @Inject
    public AiLabelSweepService(@NonNull AiLabelSweepDAO sweepDAO, @NonNull TransactionTemplate template,
            @NonNull @Config("aiLabels") AiLabelsConfig config) {
        this.sweepDAO = sweepDAO;
        this.template = template;
        this.config = config;

        var meter = GlobalOpenTelemetry.get().getMeter("opik.ai_labels");
        this.duration = meter.histogramBuilder("ai_labels.sweep.duration_seconds")
                .setDescription("Duration of one bucket's sweep query")
                .setUnit("s")
                .build();
        this.guardTrips = meter.counterBuilder("ai_labels.sweep.guard_trips")
                .setDescription("Sweep queries stopped by a ClickHouse guard; raise the bucket count")
                .build();
        this.errors = meter.counterBuilder("ai_labels.sweep.errors")
                .setDescription("Sweep queries that failed for a reason other than a guard")
                .build();
        meter.gaugeBuilder("ai_labels.unsettled_past_slo")
                .ofLongs()
                .setDescription("Unsettled (trace, task) pairs past the SLO, per workspace that has any")
                .buildWithCallback(measurement -> measured().ifPresent(snapshot -> snapshot.workspaces().stream()
                        .filter(workspace -> workspace.unsettledPastSlo() > 0)
                        .forEach(workspace -> measurement.record(workspace.unsettledPastSlo(),
                                Attributes.of(stringKey("workspace_id"), workspace.workspaceId())))));
        meter.gaugeBuilder("ai_labels.unsettled")
                .ofLongs()
                .setDescription("Unsettled (trace, task) pairs across all enabled workspaces")
                .buildWithCallback(measurement -> measured().ifPresent(snapshot -> measurement.record(
                        snapshot.workspaces().stream().mapToLong(WorkspaceHealth::unsettled).sum())));
        meter.gaugeBuilder("ai_labels.never_ended")
                .ofLongs()
                .setDescription("Traces in the horizon that never ended, so are out of scope")
                .buildWithCallback(measurement -> measured()
                        .ifPresent(snapshot -> measurement.record(snapshot.neverEnded())));
    }

    /** The enabled workspaces split into N stable buckets by {@code murmur3_32(workspace_id) mod N}. */
    public List<List<String>> buckets() {
        int count = config.getSweep().getBuckets();
        var workspaceIds = template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelWorkspaceDAO.class).enabledWorkspaceIds());
        List<List<String>> buckets = IntStream.range(0, count)
                .<List<String>>mapToObj(i -> new ArrayList<>())
                .toList();
        workspaceIds.forEach(workspaceId -> buckets.get(bucketOf(workspaceId, count)).add(workspaceId));
        return buckets.stream().map(List::copyOf).toList();
    }

    static int bucketOf(String workspaceId, int count) {
        return Math.floorMod(Hashing.murmur3_32_fixed().hashString(workspaceId, StandardCharsets.UTF_8).asInt(),
                count);
    }

    /**
     * One bucket's candidates, newest first, at most C per workspace; a failed or guarded query yields none. Traces
     * with a task in flight are read from MySQL just before, and left out.
     */
    public Flux<Candidate> sweep(@NonNull List<String> bucket, int index, @NonNull List<SweepTask> tasks) {
        if (bucket.isEmpty()) {
            return Flux.empty();
        }
        long start = System.nanoTime();
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelJobDAO.class).inFlightTraceIds(bucket)))
                .flatMapMany(inFlight -> sweepDAO.sweep(bucket, tasks, index, inFlight))
                // On error too, so the slowest queries (those a guard stopped) stay in the distribution.
                .doOnTerminate(() -> duration.record((System.nanoTime() - start) / 1e9,
                        Attributes.of(longKey("bucket"), (long) index)))
                .onErrorResume(error -> skipBucket("sweep", index, error));
    }

    /**
     * Runs the headline query and the never-ended count over every bucket and publishes them as gauges, then returns
     * what the gauges show. If any bucket's query fails, the gauges keep the last complete snapshot, or report nothing
     * before the first: a partial one would read as a smaller backlog during an outage.
     */
    public Mono<HealthSnapshot> refreshHealth(@NonNull List<List<String>> buckets, @NonNull List<SweepTask> tasks) {
        return Mono.defer(() -> {
            var complete = new AtomicBoolean(true);
            return Flux.range(0, buckets.size())
                    .concatMap(index -> Mono.zip(
                            sweepDAO.health(buckets.get(index), tasks, index)
                                    .onErrorResume(error -> {
                                        complete.set(false);
                                        return skipBucket("health", index, error);
                                    })
                                    .collectList(),
                            sweepDAO.neverEnded(buckets.get(index), index)
                                    .onErrorResume(error -> {
                                        complete.set(false);
                                        return skipBucket("never_ended", index, error).then(Mono.just(0L));
                                    })))
                    .reduce(HealthSnapshot.EMPTY, (snapshot, bucket) -> {
                        var workspaces = new ArrayList<>(snapshot.workspaces());
                        workspaces.addAll(bucket.getT1());
                        return new HealthSnapshot(List.copyOf(workspaces), snapshot.neverEnded() + bucket.getT2());
                    })
                    .flatMap(snapshot -> {
                        if (complete.get()) {
                            health.set(snapshot);
                        }
                        return Mono.justOrEmpty(health.get());
                    });
        });
    }

    private Optional<HealthSnapshot> measured() {
        return Optional.ofNullable(health.get());
    }

    private <T> Flux<T> skipBucket(String query, int bucket, Throwable error) {
        var guard = guardCode(error);
        if (guard.isPresent()) {
            guardTrips.add(1, Attributes.of(longKey("bucket"), (long) bucket, stringKey("code"), guard.get()));
            log.warn(
                    "ai-labels '{}' query for bucket '{}' tripped guard '{}'; skipping the bucket, raise the bucket count",
                    query, bucket, guard.get());
        } else {
            errors.add(1, Attributes.of(longKey("bucket"), (long) bucket, stringKey("query"), query,
                    stringKey("error_type"), ErrorMetricsResolver.errorType(error)));
            log.error("ai-labels '{}' query for bucket '{}' failed; skipping the bucket", query, bucket, error);
        }
        return Flux.empty();
    }

    // The driver surfaces ClickHouse's code only in the message ("Code: 158. DB::Exception: ..."), as
    // RetentionUtils.isTooManyRowsException reads it.
    static Optional<String> guardCode(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            var message = cause.getMessage();
            if (message == null) {
                continue;
            }
            for (var guard : GUARD_CODES.entrySet()) {
                if (message.contains("Code: " + guard.getKey() + ".")) {
                    return Optional.of(guard.getValue());
                }
            }
        }
        return Optional.empty();
    }
}
