package com.comet.opik.domain;

import com.comet.opik.domain.SpanWeeksBackfillChunkDAO.Chunk;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Backfills span_weeks from the spans already written, one range of span ids per step.
 *
 * <p>The first step plans: it tiles the id space into chunks of {@code weeksPerChunk} weeks, from the Monday of the
 * first span's {@code created_at} to the Monday of the plan's own week. The first chunk is open below, for every older
 * id (past-dated and junk ids included), and the last open above, for the current week and any later id. Each later
 * step backfills the oldest pending chunk; once none is pending, steps do nothing. Nothing here needs spans to be
 * partitioned: a span's week always comes from its id.
 *
 * <p>Correct only if span writes register their weeks ({@code spanWeeksWriteEnabled}) on every instance before the
 * plan is taken: the chunks cover the spans written until then, and live registration everything after. A chunk that
 * fails is retried by the next step; re-registering a week is harmless, as span_weeks collapses repeats.
 */
@Singleton
@Slf4j
public class SpanWeeksBackfillService {

    private final TransactionTemplate template;
    private final SpanWeeksDAO spanWeeksDAO;
    private final DatabaseAnalyticsDataModelConfig dataModelConfig;
    private final SpanWeeksBackfillConfig config;

    @Inject
    public SpanWeeksBackfillService(
            @NonNull TransactionTemplate template,
            @NonNull SpanWeeksDAO spanWeeksDAO,
            @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModelConfig,
            @NonNull @Config("spanWeeksBackfill") SpanWeeksBackfillConfig config) {
        this.template = template;
        this.spanWeeksDAO = spanWeeksDAO;
        this.dataModelConfig = dataModelConfig;
        this.config = config;
    }

    public Mono<Void> runStep() {
        if (!dataModelConfig.spanWeeksWriteEnabled()) {
            log.warn("Span weeks backfill skipped: databaseAnalyticsDataModel.spanWeeksWriteEnabled is off");
            return Mono.empty();
        }
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var chunks = handle.attach(SpanWeeksBackfillChunkDAO.class);
            return new Progress(chunks.count() > 0, chunks.findNextPending());
        })).subscribeOn(Schedulers.boundedElastic())
                .flatMap(progress -> {
                    if (!progress.planned()) {
                        return plan();
                    }
                    return progress.next().map(this::backfill).orElseGet(Mono::empty);
                });
    }

    private record Progress(boolean planned, Optional<Chunk> next) {
    }

    private Mono<Void> plan() {
        LocalDate last = monday(Instant.now());
        return spanWeeksDAO.findFirstCreatedAt(config.getQueryTimeout().toSeconds())
                .flatMap(firstCreatedAt -> Mono.<Void>fromRunnable(() -> {
                    LocalDate first = firstCreatedAt.map(SpanWeeksBackfillService::monday)
                            .filter(monday -> monday.isBefore(last))
                            .orElse(last);
                    List<Chunk> chunks = chunks(first, last, config.getWeeksPerChunk());
                    template.inTransaction(WRITE, handle -> {
                        handle.attach(SpanWeeksBackfillChunkDAO.class).insert(chunks);
                        return null;
                    });
                    log.info("Span weeks backfill planned '{}' chunks from week '{}' to '{}'", chunks.size(), first,
                            last);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Void> backfill(Chunk chunk) {
        long started = System.currentTimeMillis();
        return spanWeeksDAO.backfill(chunk.fromWeek(), chunk.toWeek(), config.getMaxThreads(),
                config.getQueryTimeout().toSeconds())
                .then(Mono.<Void>fromRunnable(() -> template.inTransaction(WRITE, handle -> {
                    handle.attach(SpanWeeksBackfillChunkDAO.class).markBackfilled(chunk.fromWeek());
                    return null;
                })).subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(__ -> log.info("Span weeks backfilled ids from week '{}' to '{}' in '{}' ms",
                        chunk.fromWeek(), chunk.toWeek(), System.currentTimeMillis() - started));
    }

    /** Tiles the ids: open below up to {@code first}, then every {@code weeks} weeks to {@code last}, open above. */
    static List<Chunk> chunks(@NonNull LocalDate first, @NonNull LocalDate last, int weeks) {
        Preconditions.checkArgument(!first.isAfter(last), "Argument 'first' must not be after 'last'");
        Preconditions.checkArgument(weeks > 0, "Argument 'weeks' must be positive");
        List<Chunk> chunks = new ArrayList<>();
        long from = 0;
        for (LocalDate boundary = first; boundary.isBefore(last); boundary = boundary.plusWeeks(weeks)) {
            chunks.add(Chunk.builder().fromWeek(from).toWeek(week(boundary)).build());
            from = week(boundary);
        }
        chunks.add(Chunk.builder().fromWeek(from).toWeek(week(last)).build());
        chunks.add(Chunk.builder().fromWeek(week(last)).toWeek(null).build());
        return chunks;
    }

    private static LocalDate monday(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static long week(LocalDate monday) {
        return Long.parseLong(monday.format(DateTimeFormatter.BASIC_ISO_DATE));
    }
}
