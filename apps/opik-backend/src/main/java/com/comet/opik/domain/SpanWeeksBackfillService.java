package com.comet.opik.domain;

import com.comet.opik.domain.SpanWeeksBackfillChunkDAO.Chunk;
import com.comet.opik.domain.SpanWeeksDAO.WeekSpans;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Backfills span_weeks from the spans already written, one chunk of weeks per step, then marks every project as
 * backfilled.
 *
 * <p>The first step plans: it lists the weeks the spans table holds (its partitions, or, while it is unpartitioned,
 * the week of each span's id) and stores them as chunks of consecutive weeks. Each later step backfills the oldest
 * pending chunk. Once none is pending, every project is marked, and every later step marks the projects created since.
 *
 * <p>Correct only if span writes register their weeks ({@code spanWeeksWriteEnabled}) on every instance before the
 * plan is taken: the plan covers the spans written until then, and live registration everything after. A chunk that
 * fails is retried by the next step; re-registering a week is harmless, as span_weeks collapses repeats.
 */
@Singleton
@Slf4j
public class SpanWeeksBackfillService {

    private final TransactionTemplate template;
    private final SpanWeeksDAO spanWeeksDAO;
    private final ProjectService projectService;
    private final DatabaseAnalyticsDataModelConfig dataModelConfig;
    private final SpanWeeksBackfillConfig config;

    @Inject
    public SpanWeeksBackfillService(
            @NonNull TransactionTemplate template,
            @NonNull SpanWeeksDAO spanWeeksDAO,
            @NonNull ProjectService projectService,
            @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModelConfig,
            @NonNull @Config("spanWeeksBackfill") SpanWeeksBackfillConfig config) {
        this.template = template;
        this.spanWeeksDAO = spanWeeksDAO;
        this.projectService = projectService;
        this.dataModelConfig = dataModelConfig;
        this.config = config;
    }

    public Mono<Void> runStep() {
        if (!dataModelConfig.spanWeeksWriteEnabled()) {
            log.warn("Span weeks backfill skipped: databaseAnalyticsDataModel.spanWeeksWriteEnabled is off");
            return Mono.empty();
        }
        // Post-wrap, spans is Distributed: its partitions live in, and are read from, spans_local.
        String table = dataModelConfig.spansDistributedWrapEnabled() ? "spans_local" : "spans";
        return spanWeeksDAO.isPartitioned(table)
                .flatMap(partitioned -> Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                    var chunks = handle.attach(SpanWeeksBackfillChunkDAO.class);
                    return new Progress(chunks.count() > 0, chunks.findNextPending());
                })).subscribeOn(Schedulers.boundedElastic())
                        .flatMap(progress -> {
                            if (!progress.planned()) {
                                return plan(table, partitioned);
                            }
                            return progress.next()
                                    .map(chunk -> backfill(table, partitioned, chunk))
                                    .orElseGet(this::markProjects);
                        }));
    }

    private record Progress(boolean planned, Optional<Chunk> next) {
    }

    private Mono<Void> plan(String table, boolean partitioned) {
        long timeout = config.getQueryTimeout().toSeconds();
        return spanWeeksDAO.findWeeks(table, partitioned, timeout)
                .flatMap(weeks -> {
                    if (weeks.isEmpty()) {
                        // Nothing written yet, so live registration covers every span there will be.
                        return markProjects();
                    }
                    List<Chunk> chunks = chunk(weeks, config.getMaxSpansPerChunk());
                    log.info("Span weeks backfill planned '{}' chunks over '{}' weeks of '{}' (partitioned: '{}')",
                            chunks.size(), weeks.size(), table, partitioned);
                    return Mono.<Void>fromRunnable(() -> template.inTransaction(WRITE, handle -> {
                        handle.attach(SpanWeeksBackfillChunkDAO.class).insert(chunks);
                        return null;
                    })).subscribeOn(Schedulers.boundedElastic());
                });
    }

    private Mono<Void> backfill(String table, boolean partitioned, Chunk chunk) {
        long started = System.currentTimeMillis();
        return spanWeeksDAO.backfill(table, partitioned, chunk.fromWeek(), chunk.toWeek(),
                config.getMaxBytesBeforeExternalGroupBy(), config.getQueryTimeout().toSeconds())
                .then(Mono.<Void>fromRunnable(() -> template.inTransaction(WRITE, handle -> {
                    handle.attach(SpanWeeksBackfillChunkDAO.class).markBackfilled(chunk.fromWeek());
                    return null;
                })).subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(__ -> log.info("Span weeks backfilled weeks '{}' to '{}' ('{}' spans) in '{}' ms",
                        chunk.fromWeek(), chunk.toWeek(), chunk.spanCount(), System.currentTimeMillis() - started));
    }

    private Mono<Void> markProjects() {
        return Mono.<Void>fromRunnable(() -> {
            int batchSize = config.getProjectsBatchSize();
            long marked = 0;
            int batch;
            do {
                batch = projectService.markSpanWeeksBackfilled(batchSize);
                marked += batch;
            } while (batch == batchSize);
            if (marked > 0) {
                log.info("Span weeks backfill marked '{}' projects as backfilled", marked);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Merges consecutive weeks into chunks of at most maxSpans rows; a larger week is a chunk on its own. */
    static List<Chunk> chunk(List<WeekSpans> weeks, long maxSpans) {
        List<Chunk> chunks = new ArrayList<>();
        Chunk current = null;
        for (WeekSpans week : weeks) {
            if (current != null && current.spanCount() + week.spanCount() <= maxSpans) {
                current = current.toBuilder()
                        .toWeek(week.week())
                        .spanCount(current.spanCount() + week.spanCount())
                        .build();
            } else {
                if (current != null) {
                    chunks.add(current);
                }
                current = Chunk.builder().fromWeek(week.week()).toWeek(week.week()).spanCount(week.spanCount())
                        .build();
            }
        }
        if (current != null) {
            chunks.add(current);
        }
        return chunks;
    }
}
