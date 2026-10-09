package com.comet.opik.domain;

import com.comet.opik.domain.ProjectDAO.ProjectWorkspace;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Backfills span_weeks from the spans already written, one chunk of weeks per step, marking each project as
 * backfilled once every week it has spans in is.
 *
 * <p>The first step plans: it lists the weeks the spans table holds (its partitions, or, while it is unpartitioned,
 * the week of each span's id) and stores them as chunks of consecutive weeks. Each later step backfills the oldest
 * pending chunk. Chunks go in ascending order, so every planned week before the oldest pending one is done, and a week
 * the plan does not hold has only spans written after it, which live registration covers. A project is therefore
 * backfilled once it has no spans from the oldest pending week on, or none is pending. Every step ends by checking the
 * unmarked projects, by id, and marking those.
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
        // Post-wrap, spans is Distributed: its partitions live in, and are read from, spans_local.
        String table = dataModelConfig.spansDistributedWrapEnabled() ? "spans_local" : "spans";
        return spanWeeksDAO.isPartitioned(table)
                .flatMap(partitioned -> Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                    var chunks = handle.attach(SpanWeeksBackfillChunkDAO.class);
                    return new Progress(chunks.count() > 0, chunks.findNextPending());
                })).subscribeOn(Schedulers.boundedElastic())
                        .flatMap(progress -> {
                            Mono<Void> step = !progress.planned()
                                    ? plan(table, partitioned)
                                    : progress.next().map(chunk -> backfill(table, partitioned, chunk))
                                            .orElseGet(Mono::empty);
                            return step.then(markProjects(table, partitioned));
                        }));
    }

    private record Progress(boolean planned, Optional<Chunk> next) {
    }

    private Mono<Void> plan(String table, boolean partitioned) {
        long timeout = config.getQueryTimeout().toSeconds();
        return spanWeeksDAO.findWeeks(table, partitioned, timeout)
                .flatMap(weeks -> {
                    if (weeks.isEmpty()) {
                        // Nothing written yet: no chunk to plan, and live registration covers every span there will be.
                        return Mono.empty();
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
                config.getQueryTimeout().toSeconds())
                .then(Mono.<Void>fromRunnable(() -> template.inTransaction(WRITE, handle -> {
                    handle.attach(SpanWeeksBackfillChunkDAO.class).markBackfilled(chunk.fromWeek());
                    return null;
                })).subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(__ -> log.info("Span weeks backfilled weeks '{}' to '{}' ('{}' spans) in '{}' ms",
                        chunk.fromWeek(), chunk.toWeek(), chunk.spanCount(), System.currentTimeMillis() - started));
    }

    private Mono<Void> markProjects(String table, boolean partitioned) {
        return Mono.<Void>fromRunnable(() -> {
            Optional<Long> pendingFrom = template.inTransaction(READ_ONLY, handle -> handle
                    .attach(SpanWeeksBackfillChunkDAO.class).findNextPending().map(Chunk::fromWeek));
            int batchSize = config.getProjectsBatchSize();
            String cursor = "";
            long marked = 0;
            List<ProjectWorkspace> batch;
            do {
                String after = cursor;
                batch = template.inTransaction(READ_ONLY,
                        handle -> handle.attach(ProjectDAO.class).findNotSpanWeeksBackfilled(after, batchSize));
                Set<UUID> notBackfilled = pendingFrom.isEmpty()
                        ? Set.of()
                        : notBackfilled(table, partitioned, pendingFrom.get(), batch);
                List<UUID> backfilled = batch.stream()
                        .map(ProjectWorkspace::id)
                        .filter(id -> !notBackfilled.contains(id))
                        .toList();
                if (!backfilled.isEmpty()) {
                    template.inTransaction(WRITE, handle -> {
                        handle.attach(ProjectDAO.class).markSpanWeeksBackfilled(backfilled);
                        return null;
                    });
                    marked += backfilled.size();
                }
                if (!batch.isEmpty()) {
                    cursor = batch.getLast().id().toString();
                }
            } while (batch.size() == batchSize);
            if (marked > 0) {
                log.info("Span weeks backfill marked '{}' projects as backfilled (oldest pending week: '{}')", marked,
                        pendingFrom.map(String::valueOf).orElse("none"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Of the batch, the projects with spans in a week not backfilled yet. */
    private Set<UUID> notBackfilled(String table, boolean partitioned, long pendingFrom, List<ProjectWorkspace> batch) {
        return spanWeeksDAO.findProjectsWithSpansFrom(table, partitioned, pendingFrom,
                batch.stream().map(ProjectWorkspace::workspaceId).collect(Collectors.toSet()),
                batch.stream().map(ProjectWorkspace::id).toList(),
                config.getQueryTimeout().toSeconds())
                .block();
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
