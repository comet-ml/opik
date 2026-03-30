package com.comet.opik.domain.retention;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.RetentionConfig.CatchUpConfig;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.retention.RetentionUtils.compareUUID;
import static com.comet.opik.domain.retention.RetentionUtils.extractInstant;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Progressive catch-up service for historical data deletion.
 * Processes rules with applyToPast=true that have data older than the sliding window.
 *
 * All three tiers run independently per cycle (no starvation):
 * - Small workspaces: batch one-shot delete
 * - Medium workspaces: 7-day chunks (configurable)
 * - Large workspaces: 1-day chunks (configurable)
 */
@Slf4j
@Singleton
public class RetentionCatchUpService {

    private static final String TIER_SMALL = "small";
    private static final String TIER_MEDIUM = "medium";
    private static final String TIER_LARGE = "large";

    private final TransactionTemplate template;
    private final TraceDAO traceDAO;
    private final SpanDAO spanDAO;
    private final InstantToUUIDMapper uuidMapper;
    private final RetentionConfig config;

    private final LongCounter rulesProcessed;
    private final LongCounter rulesCompleted;
    private final LongCounter rowsToDelete;

    @Inject
    public RetentionCatchUpService(
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

        this.rulesProcessed = meter
                .counterBuilder("opik.retention.catch_up.rules_processed")
                .setDescription("Number of catch-up rules processed per cycle")
                .build();

        this.rulesCompleted = meter
                .counterBuilder("opik.retention.catch_up.rules_completed")
                .setDescription("Number of catch-up rules that finished all historical deletion")
                .build();

        this.rowsToDelete = meter
                .counterBuilder("opik.retention.catch_up.rows_to_delete")
                .setDescription(
                        "Lightweight pre-delete row count (upper-bound ceiling, >99% precision, excludes experiment exclusion)")
                .build();
    }

    /**
     * Execute one catch-up cycle. All three tiers run independently per cycle
     * to prevent starvation of medium/large workspaces.
     */
    public Mono<Void> executeCatchUpCycle(@NonNull Instant now) {
        var catchUpConfig = config.getCatchUp();
        if (!catchUpConfig.isEnabled()) {
            log.debug("Catch-up cycle skipped: catch-up is disabled");
            return Mono.empty();
        }

        return Flux.concat(
                processSmall(catchUpConfig, now),
                processMedium(catchUpConfig, now),
                processLarge(catchUpConfig, now))
                .then();
    }

    /**
     * Process small workspaces: batch up to N rules, one-shot delete entire catch-up range.
     */
    private Mono<Long> processSmall(CatchUpConfig catchUpConfig, Instant now) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);
            return dao.findSmallCatchUpRules(catchUpConfig.getSmallThreshold(), catchUpConfig.getSmallBatchSize());
        }))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rules -> {
                    if (rules.isEmpty()) {
                        return Mono.empty();
                    }
                    log.info("Catch-up: processing '{}' small workspaces", rules.size());
                    rulesProcessed.add(rules.size(),
                            Attributes.of(stringKey("tier"), TIER_SMALL));
                    return deleteSmallBatch(rules, now);
                });
    }

    /**
     * Process medium workspaces: pick N most outdated, delete one chunk each (sequential).
     */
    private Mono<Long> processMedium(CatchUpConfig catchUpConfig, Instant now) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);
            return dao.findMediumCatchUpRules(
                    catchUpConfig.getSmallThreshold(),
                    catchUpConfig.getLargeThreshold(),
                    catchUpConfig.getMediumBatchSize());
        }))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rules -> {
                    if (rules.isEmpty()) {
                        return Mono.empty();
                    }
                    log.info("Catch-up: processing '{}' medium workspaces", rules.size());
                    rulesProcessed.add(rules.size(),
                            Attributes.of(stringKey("tier"), TIER_MEDIUM));
                    return deleteChunked(rules, catchUpConfig.getMediumChunkDays(), now);
                });
    }

    /**
     * Process large workspaces: pick the single most outdated, delete one small chunk.
     */
    private Mono<Long> processLarge(CatchUpConfig catchUpConfig, Instant now) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);
            return dao.findLargeCatchUpRule(catchUpConfig.getLargeThreshold());
        }))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optRule -> {
                    if (optRule.isEmpty()) {
                        return Mono.empty();
                    }
                    var rule = optRule.get();
                    log.info("Catch-up: processing large workspace '{}'", rule.workspaceId());
                    rulesProcessed.add(1,
                            Attributes.of(stringKey("tier"), TIER_LARGE));
                    return deleteChunked(List.of(rule), catchUpConfig.getLargeChunkDays(), now);
                });
    }

    /**
     * One-shot delete for small workspaces. Uses per-workspace cursors via deleteForRetentionBounded
     * so each workspace only scans from its own cursor position, not the group minimum.
     */
    private Mono<Long> deleteSmallBatch(List<RetentionRule> rules, Instant now) {
        var grouped = rules.stream().collect(Collectors.groupingBy(RetentionRule::retention));

        return Flux.fromIterable(grouped.entrySet())
                .concatMap(entry -> {
                    RetentionPeriod period = entry.getKey();
                    List<RetentionRule> rulesForPeriod = entry.getValue();

                    Instant slidingWindowStart = computeSlidingWindowStart(period, now);
                    UUID cutoffId = uuidMapper.toLowerBound(slidingWindowStart);

                    // Build per-workspace bounds from each rule's own cursor
                    Map<String, UUID> workspaceMinIds = new HashMap<>();
                    UUID globalLowerBound = cutoffId; // track the overall minimum for experiment subquery
                    for (var rule : rulesForPeriod) {
                        UUID cursor = rule.catchUpCursor();
                        if (cursor != null) {
                            workspaceMinIds.put(rule.workspaceId(), cursor);
                            if (compareUUID(cursor, globalLowerBound) < 0) {
                                globalLowerBound = cursor;
                            }
                        }
                    }

                    if (workspaceMinIds.isEmpty()) {
                        return Flux.empty();
                    }

                    log.info("Catch-up small batch: '{}' workspaces, period='{}'",
                            workspaceMinIds.size(), period);

                    var batchWsIds = List.copyOf(workspaceMinIds.keySet());
                    var finalGlobalLowerBound = globalLowerBound;

                    // Lightweight pre-delete count for observability (upper-bound ceiling, >99% precision).
                    // Runs sequentially before deletes to avoid overloading ClickHouse; cost is minimal
                    // via primary key index. Collected metrics help assess query cost over time.
                    return Flux.concat(
                            traceDAO.countForRetention(batchWsIds, cutoffId, finalGlobalLowerBound)
                                    .doOnNext(c -> {
                                        rowsToDelete.add(c, Attributes.of(
                                                stringKey("table"), "traces"));
                                    }).then(Mono.empty()),
                            spanDAO.countForRetention(batchWsIds, cutoffId, finalGlobalLowerBound)
                                    .doOnNext(c -> {
                                        rowsToDelete.add(c, Attributes.of(
                                                stringKey("table"), "spans"));
                                    }).then(Mono.empty()),
                            spanDAO.deleteForRetentionBounded(workspaceMinIds, cutoffId, finalGlobalLowerBound),
                            traceDAO.deleteForRetentionBounded(workspaceMinIds, cutoffId, finalGlobalLowerBound));
                })
                .reduce(0L, Long::sum)
                .flatMap(totalDeleted -> markBatchDoneAsync(rules)
                        .thenReturn(totalDeleted)
                        .doOnSuccess(d -> log.info(
                                "Catch-up small batch completed: '{}' rows deleted, '{}' rules done",
                                d, rules.size())));
    }

    /**
     * Chunked delete for medium/large workspaces. Each rule processed sequentially with
     * its own cursor window [cursor, cursor + chunkDays).
     */
    private Mono<Long> deleteChunked(List<RetentionRule> rules, int chunkDays, Instant now) {
        return Flux.fromIterable(rules)
                .concatMap(rule -> deleteOneChunk(rule, chunkDays, now))
                .reduce(0L, Long::sum);
    }

    /**
     * Delete one chunk for a single rule, advancing its cursor.
     */
    private Mono<Long> deleteOneChunk(RetentionRule rule, int chunkDays, Instant now) {
        UUID cursor = rule.catchUpCursor();
        if (cursor == null) {
            return Mono.just(0L);
        }

        // Compute the boundary once (not per-rule in a loop)
        Instant slidingWindowStart = computeSlidingWindowStart(rule.retention(), now);
        UUID upperBound = uuidMapper.toLowerBound(slidingWindowStart);

        // Cursor already past the boundary — just mark done
        if (compareUUID(cursor, upperBound) >= 0) {
            return markDoneAsync(rule.id()).thenReturn(0L);
        }

        // Advance cursor by chunkDays, preserving the Instant to avoid UUID round-trip precision loss
        Instant cursorInstant = extractInstant(cursor);
        Instant chunkEndInstant = cursorInstant.plus(chunkDays, ChronoUnit.DAYS);

        boolean isLastChunk = !chunkEndInstant.isBefore(slidingWindowStart);
        UUID chunkEnd = isLastChunk ? upperBound : uuidMapper.toLowerBound(chunkEndInstant);

        log.info("Catch-up chunk: workspace='{}', range=['{}', '{}'), lastChunk='{}'",
                rule.workspaceId(), cursor, chunkEnd, isLastChunk);

        var workspaceIds = List.of(rule.workspaceId());

        // Lightweight pre-delete count for observability (upper-bound ceiling, >99% precision).
        // Runs sequentially before deletes to avoid overloading ClickHouse; cost is minimal
        // via primary key index. Collected metrics help assess query cost over time.
        return Flux.concat(
                traceDAO.countForRetention(workspaceIds, chunkEnd, cursor)
                        .doOnNext(c -> rowsToDelete.add(c, Attributes.of(
                                stringKey("table"), "traces")))
                        .then(Mono.empty()),
                spanDAO.countForRetention(workspaceIds, chunkEnd, cursor)
                        .doOnNext(c -> rowsToDelete.add(c, Attributes.of(
                                stringKey("table"), "spans")))
                        .then(Mono.empty()),
                spanDAO.deleteForRetention(workspaceIds, chunkEnd, cursor),
                traceDAO.deleteForRetention(workspaceIds, chunkEnd, cursor))
                .reduce(0L, Long::sum)
                .flatMap(deleted -> {
                    Mono<Void> cursorUpdate = isLastChunk
                            ? markDoneAsync(rule.id())
                                    .doOnSuccess(__ -> log.info("Catch-up completed for workspace '{}'",
                                            rule.workspaceId()))
                            : updateCursorAsync(rule.id(), chunkEnd);
                    return cursorUpdate.thenReturn(deleted);
                });
    }

    /**
     * Compute the start of the sliding window for a retention period.
     * This is where the regular retention job starts, so catch-up stops here.
     * = now - retentionDays - slidingWindowDays, normalized to start-of-day UTC.
     */
    private Instant computeSlidingWindowStart(RetentionPeriod period, Instant now) {
        Instant cutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .minusDays(period.getDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        return cutoff.minus(config.getSlidingWindowDays(), ChronoUnit.DAYS);
    }

    private Mono<Void> markDoneAsync(UUID ruleId) {
        return Mono.fromRunnable(() -> {
            template.inTransaction(WRITE, handle -> {
                handle.attach(RetentionRuleDAO.class).markCatchUpDone(ruleId);
                return null;
            });
            rulesCompleted.add(1);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> markBatchDoneAsync(List<RetentionRule> rules) {
        if (rules.isEmpty()) return Mono.empty();
        var ids = rules.stream().map(RetentionRule::id).toList();
        return Mono.fromRunnable(() -> {
            template.inTransaction(WRITE, handle -> {
                handle.attach(RetentionRuleDAO.class).markCatchUpDoneBatch(ids);
                return null;
            });
            rulesCompleted.add(ids.size());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> updateCursorAsync(UUID ruleId, UUID newCursor) {
        return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {
            handle.attach(RetentionRuleDAO.class).updateCatchUpCursor(ruleId, newCursor);
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
