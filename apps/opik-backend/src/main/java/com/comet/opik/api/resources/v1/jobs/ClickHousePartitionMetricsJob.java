package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.PartitionMetricsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.PartitionStat;
import com.comet.opik.infrastructure.metrics.PartitionRange;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple4;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Quartz job publishing ClickHouse partition-health metrics (OPIK-6904, Section 11.1) as
 * OpenTelemetry gauges under the {@code opik.clickhouse.partition.*} namespace.
 *
 * <p>These are cluster-global metrics, so a single instance must own the poll: {@code doJob}
 * acquires a distributed lock (held until the next interval), refreshes an in-memory snapshot from
 * {@link ClickHousePartitionMetricsDAO}, and the registered observable gauges report that snapshot
 * on every OTel collection. Instances that fail to acquire the lock clear their snapshot so they
 * stop reporting — this keeps exactly one series per (table, partition) and lets partitions that
 * age out drop from Prometheus instead of lingering as stale values.
 *
 * <p>Out-of-range weekly partitions (far-future, or older than the estate's oldest project) are folded into one
 * series per table per side by {@link PartitionRange}, so the series count stays bounded however many
 * junk partitions a table carries. The partition count is computed before folding and stays exact.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class ClickHousePartitionMetricsJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("clickhouse:partition_metrics_lock");

    private static final AttributeKey<String> TABLE_KEY = stringKey("table");
    private static final AttributeKey<String> PARTITION_KEY = stringKey("partition");
    private static final AttributeKey<String> RANGE_KEY = stringKey("range");

    /** {@code partitionCounts}: exact distinct partitions per table, then per range. */
    @Builder(toBuilder = true)
    private record Snapshot(
            Map<String, Map<String, Long>> partitionCounts,
            List<PartitionStat> partitionStats,
            List<LwdStat> lwdStats) {
        private static final Snapshot EMPTY = Snapshot.builder()
                .partitionCounts(Map.of())
                .partitionStats(List.of())
                .lwdStats(List.of())
                .build();
    }

    private final ClickHousePartitionMetricsDAO partitionMetricsDAO;
    private final LockService lockService;
    private final PartitionMetricsConfig config;
    private final ProjectService projectService;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();
    // Partition-date floor derived from the oldest project; looked up on the first poll after startup and
    // cached, since the oldest project doesn't get older.
    private final AtomicReference<LocalDate> cachedPartitionRangeStart = new AtomicReference<>();

    @Inject
    public ClickHousePartitionMetricsJob(
            @NonNull ClickHousePartitionMetricsDAO partitionMetricsDAO,
            @NonNull LockService lockService,
            @NonNull @Config("partitionMetrics") PartitionMetricsConfig config,
            @NonNull ProjectService projectService) {
        this.partitionMetricsDAO = partitionMetricsDAO;
        this.lockService = lockService;
        this.config = config;
        this.projectService = projectService;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.clickhouse");

        // Aggregated one series per table (partition counts are the only non-per-partition metrics).
        meter.gaugeBuilder("opik.clickhouse.partition.count").ofLongs()
                .setDescription("Number of active partitions per table")
                .buildWithCallback(measurement -> snapshot.get().partitionCounts()
                        .forEach((table, byRange) -> measurement.record(
                                byRange.values().stream().mapToLong(Long::longValue).sum(),
                                Attributes.of(TABLE_KEY, table))));
        meter.gaugeBuilder("opik.clickhouse.partition.range_count").ofLongs()
                .setDescription("Number of active partitions per table by range "
                        + "(in_range, out_of_range_future, out_of_range_past)")
                .buildWithCallback(measurement -> snapshot.get().partitionCounts()
                        .forEach((table, byRange) -> byRange.forEach((range, count) -> measurement.record(count,
                                Attributes.of(TABLE_KEY, table, RANGE_KEY, range)))));

        // Per-(table, partition) series sourced from system.parts.
        registerPartitionGauge(meter, "opik.clickhouse.partition.size_bytes",
                "Total size on disk of active parts per partition", PartitionStat::bytes);
        registerPartitionGauge(meter, "opik.clickhouse.partition.max_part_size_bytes",
                "Largest single active part size per partition (max by table = largest active part)",
                PartitionStat::maxPartBytes);
        registerPartitionGauge(meter, "opik.clickhouse.partition.parts",
                "Number of active parts per partition", PartitionStat::parts);
        registerPartitionGauge(meter, "opik.clickhouse.partition.rows",
                "Total physical rows (including LWD-masked) of active parts per partition", PartitionStat::rows);
        registerPartitionGauge(meter, "opik.clickhouse.partition.last_activity_seconds",
                "Unix timestamp of the most recent part modification per partition",
                PartitionStat::lastActivityEpochSeconds);

        // Per-(table, partition) series sourced from the LWD mask scan.
        registerLwdGauge(meter, "opik.clickhouse.partition.lwd_rows",
                "Number of lightweight-deleted (masked) rows per partition", LwdStat::lwdRows);
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("ClickHouse partition metrics job interrupted before execution, skipping");
            return;
        }

        // Deferred so the DAO calls (and their query rendering) run only once the lock is held —
        // bestEffortLock subscribes to this Mono only after acquiring the permit. The LWD scan is
        // the expensive, failure-prone arm (full-table mask scan needing a read-write CH user); its
        // failure must not sink the cheap, reliable system.parts gauges, so it degrades to an empty
        // list and the LWD gauges simply stop reporting until the next successful poll.
        Mono<Void> refresh = Mono.defer(() -> {
            if (interrupted.get()) {
                return Mono.empty();
            }
            Mono<List<LwdStat>> lwdRowCounts = partitionMetricsDAO.getLwdRowCounts(config.getLwdTables())
                    .onErrorResume(e -> {
                        log.warn("ClickHouse partition metrics: LWD row-count scan failed, "
                                + "partition gauges still refreshed", e);
                        return Mono.just(List.of());
                    });
            return Mono
                    .zip(partitionMetricsDAO.getPartitionStats(), lwdRowCounts, partitionMetricsDAO.getServerDate(),
                            loadPartitionRangeStart())
                    .doOnNext(this::updateSnapshot)
                    // A failed refresh stops reporting rather than publishing the last snapshot indefinitely.
                    .doOnError(exception -> snapshot.set(Snapshot.EMPTY))
                    .then();
        });

        var subscription = lockService.bestEffortLock(
                RUN_LOCK,
                refresh,
                Mono.fromRunnable(() -> {
                    log.debug(
                            "ClickHouse partition metrics: another instance holds the poll lock, clearing snapshot");
                    snapshot.set(Snapshot.EMPTY);
                }),
                config.getInterval().toJavaDuration(),
                Duration.ZERO,
                true) // holdUntilExpiry: exactly one instance polls per interval
                .onErrorResume(throwable -> {
                    if (interrupted.get()) {
                        log.warn("ClickHouse partition metrics poll interrupted", throwable);
                    } else {
                        log.error("ClickHouse partition metrics poll failed", throwable);
                    }
                    return Mono.empty();
                })
                .doFinally(signal -> currentExecution.set(null))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        currentExecution.set(subscription);
    }

    /** Empty while there are no projects; not cached then, so the lookup is retried next poll. */
    private Mono<Optional<LocalDate>> loadPartitionRangeStart() {
        var cached = cachedPartitionRangeStart.get();
        if (cached != null) {
            return Mono.just(Optional.of(cached));
        }
        return projectService.findEarliestCreationDate()
                .map(earliest -> earliest.map(date -> {
                    var start = PartitionRange.floorFor(date);
                    cachedPartitionRangeStart.set(start);
                    log.info("ClickHouse partition metrics: partition range starts '{}', from oldest project date '{}'",
                            start, date);
                    return start;
                }));
    }

    private void updateSnapshot(
            Tuple4<List<PartitionStat>, List<LwdStat>, LocalDate, Optional<LocalDate>> result) {
        // ClickHouse's date, not the JVM's: partition ids are computed in the ClickHouse server timezone.
        var serverDate = result.getT3();
        // No projects means no legitimate data: any leftover past partition groups, keeping series bounded.
        var start = result.getT4()
                .orElseGet(() -> serverDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        var range = PartitionRange.of(start, serverDate);
        var partitionStats = range.group(result.getT1());
        var lwdStats = range.groupLwd(result.getT2());
        snapshot.set(Snapshot.builder()
                .partitionCounts(range.countByRange(result.getT1()))
                .partitionStats(partitionStats)
                .lwdStats(lwdStats)
                .build());
        log.debug("ClickHouse partition metrics refreshed: '{}' partitions as '{}' series, '{}' LWD series",
                result.getT1().size(), partitionStats.size(), lwdStats.size());
    }

    private void registerPartitionGauge(Meter meter, String name, String description,
            ToLongFunction<PartitionStat> extractor) {
        meter.gaugeBuilder(name).ofLongs()
                .setDescription(description)
                .buildWithCallback(measurement -> snapshot.get().partitionStats()
                        .forEach(stat -> measurement.record(extractor.applyAsLong(stat),
                                attributes(stat.table(), stat.partition()))));
    }

    private void registerLwdGauge(Meter meter, String name, String description,
            ToLongFunction<LwdStat> extractor) {
        meter.gaugeBuilder(name).ofLongs()
                .setDescription(description)
                .buildWithCallback(measurement -> snapshot.get().lwdStats()
                        .forEach(stat -> measurement.record(extractor.applyAsLong(stat),
                                attributes(stat.table(), stat.partition()))));
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("ClickHouse partition metrics job interrupted");
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
        }
    }

    private static Attributes attributes(String table, String partition) {
        return Attributes.of(TABLE_KEY, table, PARTITION_KEY, partition);
    }
}
