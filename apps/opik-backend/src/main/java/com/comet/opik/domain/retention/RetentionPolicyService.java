package com.comet.opik.domain.retention;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.retention.RetentionLevel;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.infrastructure.RetentionConfig;
import com.google.common.collect.Lists;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
@Singleton
public class RetentionPolicyService {

    private final TransactionTemplate template;
    private final TraceDAO traceDAO;
    private final SpanDAO spanDAO;
    private final InstantToUUIDMapper uuidMapper;
    private final RetentionConfig config;

    private final LongCounter workspacesProcessed;
    private final LongCounter rowsToDelete;

    @Inject
    public RetentionPolicyService(
            @NonNull TransactionTemplate template,
            @NonNull TraceDAO traceDAO,
            @NonNull SpanDAO spanDAO,
            @NonNull InstantToUUIDMapper uuidMapper,
            @NonNull @Config("retention") RetentionConfig config) {
        this.template = template;
        this.traceDAO = traceDAO;
        this.spanDAO = spanDAO;
        this.uuidMapper = uuidMapper;
        this.config = config;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.retention");

        this.workspacesProcessed = meter
                .counterBuilder("opik.retention.sliding_window.workspaces_processed")
                .setDescription("Number of workspaces that had retention deletes issued")
                .build();

        this.rowsToDelete = meter
                .counterBuilder("opik.retention.sliding_window.rows_to_delete")
                .setDescription(
                        "Lightweight pre-delete row count (upper-bound ceiling, >99% precision, excludes experiment exclusion)")
                .build();
    }

    /**
     * Internal record holding resolved retention parameters for a workspace.
     */
    private record WorkspaceRetention(String workspaceId, UUID cutoffId, UUID lowerBound, UUID minId) {
    }

    /**
     * Execute one retention cycle for the given fraction.
     * Testable: takes explicit fraction and timestamp, no timer/clock dependency.
     */
    public Mono<Void> executeRetentionCycle(int fraction, Instant now) {
        var range = RetentionUtils.computeWorkspaceRange(fraction, config.getTotalFractions());
        log.info("Retention cycle starting: fraction='{}', range=['{}', '{}')", fraction, range[0], range[1]);

        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);
            return dao.findActiveWorkspaceRulesInRange(range[0], range[1]);
        }))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rules -> {
                    if (rules.isEmpty()) {
                        log.info("Retention cycle: no active rules in range, skipping");
                        return Mono.empty();
                    }

                    log.info("Retention cycle: '{}' rules found", rules.size());
                    return executeDeletes(rules, now);
                })
                .doOnSuccess(__ -> log.info("Retention cycle completed: fraction='{}'", fraction))
                .doOnError(error -> log.error("Retention cycle failed: fraction='{}'", fraction, error));
    }

    private Mono<Void> executeDeletes(List<RetentionRule> rules, Instant now) {
        // Resolve priority: WORKSPACE > ORGANIZATION per workspace
        var resolved = resolveRules(rules);

        // Group by retention period
        Map<RetentionPeriod, List<RetentionRule>> grouped = resolved.stream()
                .collect(Collectors.groupingBy(RetentionRule::retention));

        log.info("Retention cycle: '{}' workspaces across '{}' retention levels",
                resolved.size(), grouped.size());
        workspacesProcessed.add(resolved.size());

        return Flux.fromIterable(grouped.entrySet())
                .concatMap(entry -> deleteForRetentionLevel(entry.getKey(), entry.getValue(), now))
                .then();
    }

    private Flux<Long> deleteForRetentionLevel(RetentionPeriod period, List<RetentionRule> rules, Instant now) {
        // Normalize cutoff to start-of-day UTC
        Instant cutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .minusDays(period.getDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        UUID cutoffId = uuidMapper.toLowerBound(cutoff);
        UUID lowerBound = uuidMapper.toLowerBound(cutoff.minus(config.getSlidingWindowDays(), ChronoUnit.DAYS));

        // Split rules into applyToPast=true and applyToPast=false
        var applyToPastRules = rules.stream()
                .filter(r -> Boolean.TRUE.equals(r.applyToPast()))
                .toList();
        var boundedRules = rules.stream()
                .filter(r -> !Boolean.TRUE.equals(r.applyToPast()))
                .toList();

        return Flux.concat(
                deleteApplyToPast(applyToPastRules, cutoffId, lowerBound),
                deleteBounded(boundedRules, cutoffId, lowerBound));
    }

    /**
     * applyToPast=true: simple IN clause with [lowerBound, cutoffId) window.
     */
    private Flux<Long> deleteApplyToPast(List<RetentionRule> rules, UUID cutoffId, UUID lowerBound) {
        if (rules.isEmpty()) {
            return Flux.empty();
        }

        var workspaceIds = rules.stream().map(RetentionRule::workspaceId).toList();
        var batches = Lists.partition(workspaceIds, config.getWorkspaceBatchSize());

        return Flux.fromIterable(batches)
                .concatMap(batch -> countAndDelete(batch, cutoffId, lowerBound));
    }

    private Flux<Long> countAndDelete(List<String> batch, UUID cutoffId, UUID lowerBound) {
        // Lightweight pre-delete count for observability (upper-bound ceiling, >99% precision).
        // Omits experiment_items exclusion to avoid join cost.
        // Counts run sequentially before deletes (not in parallel) so the metric reflects what's about
        // to be removed, and to avoid overloading ClickHouse with concurrent queries. Cost is minimal:
        // SELECT count() hits the primary key index via UUID range filter. The collected metrics also
        // help assess the actual cost of these queries over time.
        return Flux.concat(
                traceDAO.countForRetention(batch, cutoffId, lowerBound)
                        .doOnNext(count -> {
                            log.info("Retention count before delete: '{}' traces in range for '{}' workspaces",
                                    count, batch.size());
                            rowsToDelete.add(count, Attributes.of(stringKey("table"), "traces"));
                        })
                        .then(Mono.empty()),
                spanDAO.countForRetention(batch, cutoffId, lowerBound)
                        .doOnNext(count -> {
                            log.info("Retention count before delete: '{}' spans in range for '{}' workspaces",
                                    count, batch.size());
                            rowsToDelete.add(count, Attributes.of(stringKey("table"), "spans"));
                        })
                        .then(Mono.empty()),
                spanDAO.deleteForRetention(batch, cutoffId, lowerBound)
                        .onErrorResume(e -> logAndSkip("spans", batch.size(), e)),
                traceDAO.deleteForRetention(batch, cutoffId, lowerBound)
                        .onErrorResume(e -> logAndSkip("traces", batch.size(), e)));
    }

    /**
     * applyToPast=false: per-workspace OR conditions with max(minId, lowerBound).
     */
    private Flux<Long> deleteBounded(List<RetentionRule> rules, UUID cutoffId, UUID lowerBound) {
        if (rules.isEmpty()) {
            return Flux.empty();
        }

        // Build map of workspace_id -> effective lower bound = max(minId, lowerBound)
        Map<String, UUID> workspaceMinIds = new HashMap<>();
        for (var rule : rules) {
            UUID minId = uuidMapper.toLowerBound(rule.createdAt());
            workspaceMinIds.put(rule.workspaceId(), maxUUID(minId, lowerBound));
        }

        var batches = partitionMap(workspaceMinIds, config.getWorkspaceBatchSize());

        return Flux.fromIterable(batches)
                .concatMap(batch -> {
                    var batchWsIds = List.copyOf(batch.keySet());
                    return Flux.concat(
                            traceDAO.countForRetention(batchWsIds, cutoffId, lowerBound)
                                    .doOnNext(count -> {
                                        log.info(
                                                "Retention count before delete (bounded): '{}' traces for '{}' workspaces",
                                                count, batchWsIds.size());
                                        rowsToDelete.add(count, Attributes.of(stringKey("table"), "traces"));
                                    })
                                    .then(Mono.empty()),
                            spanDAO.countForRetention(batchWsIds, cutoffId, lowerBound)
                                    .doOnNext(count -> {
                                        log.info(
                                                "Retention count before delete (bounded): '{}' spans for '{}' workspaces",
                                                count, batchWsIds.size());
                                        rowsToDelete.add(count, Attributes.of(stringKey("table"), "spans"));
                                    })
                                    .then(Mono.empty()),
                            spanDAO.deleteForRetentionBounded(batch, cutoffId, lowerBound)
                                    .onErrorResume(e -> logAndSkip("spans", batch.size(), e)),
                            traceDAO.deleteForRetentionBounded(batch, cutoffId, lowerBound)
                                    .onErrorResume(e -> logAndSkip("traces", batch.size(), e)));
                });
    }

    private Mono<Long> logAndSkip(String table, int batchSize, Throwable error) {
        log.error("Retention delete failed: table='{}', batchSize='{}'", table, batchSize, error);
        return Mono.just(0L);
    }

    /**
     * Resolve priority per workspace (WORKSPACE > ORGANIZATION), keeping the winning rule.
     */
    private List<RetentionRule> resolveRules(List<RetentionRule> rules) {
        var priorityOrder = Comparator.comparing(
                (RetentionRule r) -> r.level() == RetentionLevel.WORKSPACE ? 0 : 1);

        return rules.stream()
                .collect(Collectors.groupingBy(RetentionRule::workspaceId))
                .values().stream()
                .map(rulesForWs -> rulesForWs.stream().min(priorityOrder).orElseThrow())
                .toList();
    }

    /**
     * Compare two UUID v7 values by their MSB (timestamp portion).
     * Returns the one with the higher (more recent) timestamp.
     */
    static UUID maxUUID(UUID a, UUID b) {
        return Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits()) >= 0 ? a : b;
    }

    /**
     * Partition a map into batches of the given size.
     */
    private static <K, V> List<Map<K, V>> partitionMap(Map<K, V> map, int batchSize) {
        var entries = List.copyOf(map.entrySet());
        var partitioned = Lists.partition(entries, batchSize);
        return partitioned.stream()
                .map(batch -> batch.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .toList();
    }
}
