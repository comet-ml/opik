package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.infrastructure.PartitionMetricsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.PartitionStat;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class ClickHousePartitionMetricsJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("clickhouse:partition_metrics_lock");

    private static final AttributeKey<String> TABLE_KEY = stringKey("table");
    private static final AttributeKey<String> PARTITION_KEY = stringKey("partition");

    private record Snapshot(List<PartitionStat> partitionStats, List<LwdStat> lwdStats) {
        private static final Snapshot EMPTY = new Snapshot(List.of(), List.of());
    }

    private final ClickHousePartitionMetricsDAO partitionMetricsDAO;
    private final LockService lockService;
    private final PartitionMetricsConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    private final LongCounter runCounter;

    @Inject
    public ClickHousePartitionMetricsJob(
            @NonNull ClickHousePartitionMetricsDAO partitionMetricsDAO,
            @NonNull LockService lockService,
            @NonNull @Config("partitionMetrics") PartitionMetricsConfig config) {
        this.partitionMetricsDAO = partitionMetricsDAO;
        this.lockService = lockService;
        this.config = config;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.clickhouse");

        // Aggregated one series per table (partition count is the only non-per-partition metric).
        meter.gaugeBuilder("opik.clickhouse.partition.count").ofLongs()
                .setDescription("Number of active partitions per table")
                .buildWithCallback(measurement -> {
                    Map<String, Set<String>> partitionsByTable = new HashMap<>();
                    for (PartitionStat stat : snapshot.get().partitionStats()) {
                        partitionsByTable.computeIfAbsent(stat.table(), key -> new HashSet<>()).add(stat.partition());
                    }
                    partitionsByTable.forEach((table, partitions) -> measurement.record(partitions.size(),
                            Attributes.of(TABLE_KEY, table)));
                });

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

        this.runCounter = meter
                .counterBuilder("opik.clickhouse.partition_metrics.run")
                .setDescription("Number of partition-health metric poll runs, tagged by result")
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("ClickHouse partition metrics job interrupted before execution, skipping");
            return;
        }

        // Deferred so the DAO calls (and their query rendering) run only once the lock is held —
        // bestEffortLock subscribes to this Mono only after acquiring the permit.
        Mono<Void> refresh = Mono.defer(() -> Mono
                .zip(partitionMetricsDAO.getPartitionStats(),
                        partitionMetricsDAO.getLwdRowCounts(config.getLwdTables()))
                .doOnNext(this::updateSnapshot)
                .then());

        try {
            lockService.bestEffortLock(
                    RUN_LOCK,
                    refresh,
                    Mono.fromRunnable(() -> {
                        log.debug(
                                "ClickHouse partition metrics: another instance holds the poll lock, clearing snapshot");
                        snapshot.set(Snapshot.EMPTY);
                        runCounter.add(1, Attributes.of(stringKey("result"), "skipped_lock"));
                    }),
                    config.getInterval(),
                    Duration.ZERO,
                    true) // holdUntilExpiry: exactly one instance polls per interval
                    .block();
        } catch (Exception e) {
            log.error("ClickHouse partition metrics poll failed", e);
            runCounter.add(1, Attributes.of(stringKey("result"), "error"));
        }
    }

    private void updateSnapshot(Tuple2<List<PartitionStat>, List<LwdStat>> result) {
        snapshot.set(new Snapshot(result.getT1(), result.getT2()));
        runCounter.add(1, Attributes.of(stringKey("result"), "success"));
        log.debug("ClickHouse partition metrics refreshed: '{}' partitions, '{}' LWD partitions",
                result.getT1().size(), result.getT2().size());
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
    }

    private static Attributes attributes(String table, String partition) {
        return Attributes.of(TABLE_KEY, table, PARTITION_KEY, partition);
    }
}
