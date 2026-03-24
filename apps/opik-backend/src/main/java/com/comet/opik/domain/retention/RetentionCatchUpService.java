package com.comet.opik.domain.retention;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.RetentionConfig.CatchUpConfig;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Progressive catch-up service for historical data deletion.
 * Processes rules with applyToPast=true that have data older than the sliding window.
 *
 * Priority order: small workspaces first (batch one-shot), then medium (7-day chunks),
 * then large (2-day chunks). Only one tier is processed per cycle.
 */
@Slf4j
@Singleton
public class RetentionCatchUpService {

    private final TransactionTemplate template;
    private final TraceDAO traceDAO;
    private final SpanDAO spanDAO;
    private final InstantToUUIDMapper uuidMapper;
    private final RetentionConfig config;

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
    }

    /**
     * Execute one catch-up cycle. Processes rules in priority order:
     * small → medium → large. Only one tier per cycle.
     */
    public Mono<Void> executeCatchUpCycle(Instant now) {
        var catchUpConfig = config.getCatchUp();
        if (!catchUpConfig.isEnabled()) {
            return Mono.empty();
        }

        return processSmall(catchUpConfig, now)
                .switchIfEmpty(processMedium(catchUpConfig, now))
                .switchIfEmpty(processLarge(catchUpConfig, now))
                .then();
    }

    /**
     * Process small workspaces: batch up to N rules, one-shot delete entire catch-up range.
     * Returns non-empty Mono if any small rules were found and processed.
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
                    return deleteChunked(List.of(rule), catchUpConfig.getLargeChunkDays(), now);
                });
    }

    /**
     * One-shot delete for small workspaces. Groups rules by retention period to batch DELETE.
     * Deletes from catch_up_cursor to cutoff-slidingWindowDays in a single pass.
     */
    private Mono<Long> deleteSmallBatch(List<RetentionRule> rules, Instant now) {
        // Group by retention period to compute cutoffs
        var grouped = rules.stream().collect(Collectors.groupingBy(RetentionRule::retention));

        return Flux.fromIterable(grouped.entrySet())
                .concatMap(entry -> {
                    RetentionPeriod period = entry.getKey();
                    List<RetentionRule> rulesForPeriod = entry.getValue();

                    UUID upperBound = computeSlidingWindowLowerBound(period, now);
                    var workspaceIds = rulesForPeriod.stream().map(RetentionRule::workspaceId).toList();

                    // Use the oldest cursor as the lower bound for the batch
                    UUID lowerBound = rulesForPeriod.stream()
                            .map(RetentionRule::catchUpCursor)
                            .filter(Objects::nonNull)
                            .min(RetentionCatchUpService::compareUUID)
                            .orElse(upperBound); // no valid cursors → empty range, no-op

                    log.info("Catch-up small batch: '{}' workspaces, period='{}', range=['{}', '{}')",
                            workspaceIds.size(), period, lowerBound, upperBound);

                    return Flux.concat(
                            spanDAO.deleteForRetention(workspaceIds, upperBound, lowerBound),
                            traceDAO.deleteForRetention(workspaceIds, upperBound, lowerBound));
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

        UUID upperBound = computeSlidingWindowLowerBound(rule.retention(), now);

        // Advance cursor by chunkDays, but don't exceed the sliding window boundary
        Instant cursorInstant = extractInstant(cursor);
        Instant chunkEndInstant = cursorInstant.plus(chunkDays, ChronoUnit.DAYS);
        Instant upperBoundInstant = extractInstant(upperBound);

        boolean isLastChunk = !chunkEndInstant.isBefore(upperBoundInstant);
        UUID chunkEnd = isLastChunk ? upperBound : uuidMapper.toLowerBound(chunkEndInstant);

        log.info("Catch-up chunk: workspace='{}', range=['{}', '{}'), lastChunk='{}'",
                rule.workspaceId(), cursor, chunkEnd, isLastChunk);

        var workspaceIds = List.of(rule.workspaceId());

        return Flux.concat(
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
     * Compute the UUID v7 lower bound of the sliding window for a retention period.
     * This is where the regular retention job starts, so catch-up stops here.
     * = cutoff - slidingWindowDays = now - retentionDays - slidingWindowDays
     */
    private UUID computeSlidingWindowLowerBound(RetentionPeriod period, Instant now) {
        Instant cutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .minusDays(period.getDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Instant slidingWindowStart = cutoff.minus(config.getSlidingWindowDays(), ChronoUnit.DAYS);
        return uuidMapper.toLowerBound(slidingWindowStart);
    }

    /**
     * Extract the timestamp from a UUID v7's MSB.
     */
    private static Instant extractInstant(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long epochMilli = msb >>> 16; // top 48 bits are the timestamp
        return Instant.ofEpochMilli(epochMilli);
    }

    /**
     * Compare two UUID v7 values by their MSB (timestamp portion).
     */
    static int compareUUID(UUID a, UUID b) {
        return Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
    }

    private Mono<Void> markDoneAsync(UUID ruleId) {
        return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {
            handle.attach(RetentionRuleDAO.class).markCatchUpDone(ruleId);
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> markBatchDoneAsync(List<RetentionRule> rules) {
        if (rules.isEmpty()) return Mono.empty();
        var ids = rules.stream()
                .map(r -> "'" + r.id().toString() + "'")
                .collect(Collectors.joining(","));
        return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {
            handle.attach(RetentionRuleDAO.class).markCatchUpDoneBatch(ids);
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> updateCursorAsync(UUID ruleId, UUID newCursor) {
        return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {
            handle.attach(RetentionRuleDAO.class).updateCatchUpCursor(ruleId, newCursor);
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
