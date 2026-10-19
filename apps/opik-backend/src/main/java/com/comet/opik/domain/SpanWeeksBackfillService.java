package com.comet.opik.domain;

import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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
 * install date (the first project's {@code created_at}, which no span precedes) to the Monday of the plan's own week.
 * The first chunk is open below, for every older id (past-dated and junk ids included), and the last open above, for
 * the current week and any later id. Each later step backfills the oldest pending chunk; once none is pending, steps do
 * nothing. Nothing here needs spans to be partitioned: a span's week always comes from its id.
 *
 * <p>The plan covers the spans written until it is taken; {@code span_weeks_mv} registers every span inserted since it
 * was created, so planning after the MV leaves no gap. A chunk that fails is retried by the next step; re-registering a
 * week is harmless, as span_weeks collapses repeats.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksBackfillService {

    private final @NonNull TransactionTemplate template;
    private final @NonNull SpanWeeksDAO spanWeeksDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull @Config("spanWeeksBackfill") SpanWeeksBackfillConfig config;

    /**
     * Whether the backfill is done cluster-wide: a plan exists and every one of its ranges is backfilled. Read from
     * the shared progress table, so any instance sees it as soon as another finishes; a finished plan never gains
     * ranges, so it stays true.
     */
    public Mono<Boolean> isCompleted() {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(SpanWeeksBackfillChunkDAO.class).isComplete()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Plans the backfill if it has no plan yet, else backfills the oldest pending chunk, if any. */
    public Mono<Void> runStep() {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var chunks = handle.attach(SpanWeeksBackfillChunkDAO.class);
            return Progress.builder().planned(chunks.count() > 0).next(chunks.findNextPending()).build();
        })).subscribeOn(Schedulers.boundedElastic())
                .flatMap(progress -> {
                    if (!progress.planned()) {
                        return plan();
                    }
                    return progress.next().map(this::backfill).orElseGet(this::complete);
                });
    }

    private Mono<Void> complete() {
        log.info(
                "Span weeks backfill is complete; databaseAnalyticsDataModel.spanWeeksBackfillEnabled can be turned off");
        return Mono.empty();
    }

    @Builder(toBuilder = true)
    private record Progress(boolean planned, Optional<SpanWeeksBackfillChunk> next) {
    }

    private Mono<Void> plan() {
        LocalDate last = monday(Instant.now());
        return projectService.findInstallationDate()
                .flatMap(installationDate -> Mono.<Void>fromRunnable(() -> {
                    LocalDate first = installationDate
                            .map(date -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                            .filter(monday -> monday.isBefore(last))
                            .orElse(last);
                    List<SpanWeeksBackfillChunk> chunks = chunks(first, last, config.weeksPerChunk());
                    template.inTransaction(WRITE, handle -> handle.attach(SpanWeeksBackfillChunkDAO.class)
                            .insert(chunks));
                    log.info("Span weeks backfill planned '{}' chunks from week '{}' to '{}'", chunks.size(), first,
                            last);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Void> backfill(SpanWeeksBackfillChunk chunk) {
        long started = System.currentTimeMillis();
        return spanWeeksDAO.backfill(chunk)
                .flatMap(rows -> Mono.fromCallable(() -> template.inTransaction(WRITE,
                        handle -> handle.attach(SpanWeeksBackfillChunkDAO.class).markBackfilled(chunk.fromWeek())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnSuccess(__ -> log.info(
                                "Span weeks backfilled '{}' rows for ids from week '{}' to '{}' in '{}' ms", rows,
                                chunk.fromWeek(), chunk.toWeek(), System.currentTimeMillis() - started)))
                .then();
    }

    /** Tiles the ids: open below up to {@code first}, then every {@code weeks} weeks to {@code last}, open above. */
    private static List<SpanWeeksBackfillChunk> chunks(LocalDate first, LocalDate last, int weeks) {
        Preconditions.checkArgument(!first.isAfter(last), "Argument 'first' must not be after 'last'");
        Preconditions.checkArgument(weeks > 0, "Argument 'weeks' must be positive");
        List<SpanWeeksBackfillChunk> chunks = new ArrayList<>();
        long from = 0;
        for (LocalDate boundary = first; boundary.isBefore(last); boundary = boundary.plusWeeks(weeks)) {
            chunks.add(SpanWeeksBackfillChunk.builder().fromWeek(from).toWeek(yyyymmdd(boundary)).build());
            from = yyyymmdd(boundary);
        }
        chunks.add(SpanWeeksBackfillChunk.builder().fromWeek(from).toWeek(yyyymmdd(last)).build());
        chunks.add(SpanWeeksBackfillChunk.builder().fromWeek(yyyymmdd(last)).toWeek(null).build());
        return chunks;
    }

    private static LocalDate monday(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** A Monday as the YYYYMMDD number a range bound is stored as. */
    private static long yyyymmdd(LocalDate monday) {
        return Long.parseLong(monday.format(DateTimeFormatter.BASIC_ISO_DATE));
    }
}
