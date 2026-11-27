package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.domain.topics.TopicVectorsMetrics.NotRead;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsStage;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One topic vectors tick: for every workspace topics and ai-labels are enabled for, find the traces whose latest
 * {@code trace_summary} status is done and that have no vector yet, embed their summaries and store the unit-length
 * vectors in {@code topic_vectors}. Runs on the leader only. A tick has three phases; the fresh and the backfill phase
 * visit the workspaces up to {@code embedConcurrency} at a time, each workspace with one embed call in flight:
 * <ul>
 * <li>The fresh phase: per workspace, one read of the done statuses written in {@code window}, all its drained projects
 * together, up to {@code maxTracesPerTick}, oldest first, then their embed calls and writes. It keeps new traces
 * served. It starts at the workspace's watermark in {@code topic_vector_watermarks}, less {@code watermarkOverlap}, and
 * never before the window: the read moves it past the statuses it gave a vector, never will (a refused input, a zero
 * vector) or left to the backfill, never past one it left for a later try, and saves it when the workspace's turn ends,
 * so a cancelled tick keeps what its finished turns did. Once per {@code longWindowInterval} per workspace (the daily
 * pass, due by {@code topic_vector_watermarks.long_window_read_at}) it reads the long window instead, in slices, for
 * rows it missed, such as a status that became visible more than {@code watermarkOverlap} after a later one was read,
 * and leaves the watermark as it is. A long read that does not finish is tried again by itself after one fresh read
 * ({@link LongReadRetry}); the other workspaces keep their fresh reads. On a tick with a
 * workspace due for project discovery, it leaves {@code discoveryMinShare} of the budget to the discovery.</li>
 * <li>Project discovery of the due workspaces ({@link TopicVectorDiscovery}), after the fresh phase, so new traces get
 * their vectors first. The backfill phase reads the rows it writes.</li>
 * <li>The backfill phase, with the budget the others left: per workspace, its projects not drained yet, one turn per
 * workspace per round ({@link TopicVectorBackfiller}).</li>
 * </ul>
 * The fresh phase leaves out the projects not drained yet, and the backfill reads only those, so a trace is embedded
 * by one phase in a tick, and a project's backlog is never embedded mixed with the others'.
 *
 * <p>In both phases, the live traces of each read's written vectors get their topic on arrival ({@link TopicsArrivalService}), for a
 * workspace listed at the arrival stage or a later one, read once per tick.
 *
 * <p>A workspace is read only with time left for the read and one embed call, so the tick never spends its budget
 * reading what it cannot embed. ClickHouse alone says which traces have no vector: every read is the done statuses less
 * the keys already in {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader
 * change loses nothing. Work per tick is bounded per read by {@code maxTracesPerTick} and in total by the tick budget;
 * what is left is found again by the next tick. Workspaces are visited in a new random order every tick, so when the
 * budget does not reach them all, the ones left out differ from tick to tick.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicVectorsTickService {

    /** The shortest status span a fresh read over its guard is narrowed to; one still over it fails as before. */
    static final Duration MIN_NARROWED_SPAN = Duration.ofMinutes(1);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig;
    private final @NonNull TopicWorkspaceService workspaces;
    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull AiLabelTaskRegistry registry;
    private final @NonNull TopicVectorWriter writer;
    private final @NonNull TopicVectorDiscovery discovery;
    private final @NonNull TopicVectorBackfiller backfiller;
    private final @NonNull TopicVectorsMetrics metrics;
    private final @NonNull LongReadRetry longReadRetry;

    /**
     * Fails with {@link IllegalStateException} when ai-labels has no {@code trace_summary} task: no tick can run, so the
     * job counts the tick as failed rather than as one that ran and found nothing.
     */
    public Mono<TickResult> tick(@NonNull Instant start) {
        return Mono.defer(() -> {
            var task = registry.find(TopicEmbedding.SUMMARY_TASK).orElseThrow(() -> new IllegalStateException(
                    "ai-labels has no task '%s'".formatted(TopicEmbedding.SUMMARY_TASK)));
            var appliesFrom = earliestAppliesFrom(task);
            return tick(start, appliesFrom);
        });
    }

    private Mono<TickResult> tick(Instant start, Instant appliesFrom) {
        var vectors = config.getVectors();
        var lowerBound = lowerBound(appliesFrom, start, vectors.getWindow().toJavaDuration());
        if (lowerBound.isAfter(start)) {
            return Mono.just(TickResult.EMPTY);
        }
        // The long window: the backfill's, and the daily pass's.
        var backfillLowerBound = lowerBound(appliesFrom, start, vectors.backfillOrLongWindow());
        var budget = TickBudget.of(start, vectors.getTickBudget().toJavaDuration(), Clock.systemUTC());
        // Only the daily pass can write a status older than the short window: those are the rows it recovers.
        var missedBefore = start.minus(vectors.getWindow().toJavaDuration());
        var longReadBefore = start.minus(vectors.getLongWindowInterval().toJavaDuration());
        return Mono.fromCallable(() -> {
            var workspaceIds = workspaces.enabledWorkspaceIds();
            return new Enabled(shuffled(workspaceIds), workspaces.vectorsWatermarks(workspaceIds),
                    longWindowReads(workspaceIds), arrivalWorkspaceIds());
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(enabled -> {
                    var longDue = longWindowDue(enabled, longReadBefore);
                    return Mono.zip(discovery.due(enabled.workspaceIds(), start),
                            backfiller.undrained(enabled.workspaceIds()), longReadRetry.waiting(longDue))
                            .flatMap(reads -> {
                                var due = reads.getT1();
                                var undrained = reads.getT2();
                                // Due by the column, less those whose long read did not finish last tick.
                                var longReads = new HashSet<>(longDue);
                                longReads.removeAll(reads.getT3());
                                // With a discovery due, the fresh phase leaves it its share; without, the whole budget.
                                var fresh = due.isEmpty() ? budget : budget.before(discovery.share(budget));
                                return Flux.fromIterable(enabled.workspaceIds())
                                        .flatMap(workspaceId -> {
                                            boolean longWindow = longReads.contains(workspaceId);
                                            return visit(workspaceId, undrained.getOrDefault(workspaceId, List.of()),
                                                    find(workspaceId, longWindow
                                                            ? backfillLowerBound
                                                            : readFrom(lowerBound,
                                                                    enabled.watermarks().get(workspaceId),
                                                                    vectors.getWatermarkOverlap().toJavaDuration()),
                                                            start),
                                                    longWindow, longWindow ? missedBefore : null, fresh,
                                                    enabled.arrivalWorkspaceIds().contains(workspaceId));
                                        }, vectors.getEmbedConcurrency())
                                        .collectList()
                                        .flatMap(visits -> longReadRetry.notFinished(visits.stream()
                                                .filter(visit -> visit.longWindow() && !visit.complete())
                                                .map(visit -> visit.workspace().id()).toList(), start)
                                                .thenReturn(visits))
                                        .flatMap(visits -> finish(visits).flatMap(written -> {
                                            return discovery.discover(due, backfillLowerBound,
                                                    createdAfter(backfillLowerBound), budget)
                                                    .then(due.isEmpty()
                                                            ? Mono.just(undrained)
                                                            : undrainedAfterDiscovery(enabled.workspaceIds(),
                                                                    undrained))
                                                    .flatMap(
                                                            backfills -> backfill(visits, backfills, backfillLowerBound,
                                                                    budget))
                                                    .map(written::plus);
                                        }));
                            });
                });
    }

    /**
     * @param longWindowReads when each workspace's daily pass last read it completely, for those with a read; null
     *        when the read failed
     */
    private record Enabled(@NonNull List<String> workspaceIds, @NonNull Map<String, Instant> watermarks,
            @Nullable Map<String, Instant> longWindowReads, @NonNull Set<String> arrivalWorkspaceIds) {
    }

    /**
     * The workspaces whose daily pass is due: a watermark row, and a last complete long read older than
     * {@code longWindowInterval}, or none. A workspace without a watermark row gets its fresh read first; that row
     * starts the interval.
     */
    private static List<String> longWindowDue(Enabled enabled, Instant longReadBefore) {
        if (enabled.longWindowReads() == null) {
            return List.of();
        }
        return enabled.workspaceIds().stream()
                .filter(enabled.watermarks()::containsKey)
                .filter(id -> {
                    var readAt = enabled.longWindowReads().get(id);
                    return readAt == null || !readAt.isAfter(longReadBefore);
                })
                .toList();
    }

    /** The daily pass's last complete reads; null when the read fails: the tick runs the short window only. */
    private @Nullable Map<String, Instant> longWindowReads(List<String> workspaceIds) {
        try {
            return workspaces.vectorsLongWindowReads(workspaceIds);
        } catch (RuntimeException exception) {
            log.warn("Topic vectors daily pass reads not read, running the short window", exception);
            return null;
        }
    }

    /**
     * The workspaces listed at the arrival stage or a later one, read once per tick. None when the read fails: no
     * arrival this tick, and the vectors go on.
     */
    private Set<String> arrivalWorkspaceIds() {
        try {
            return Set.copyOf(workspaces.enabledWorkspaceIds(TopicsStage.ARRIVAL));
        } catch (RuntimeException exception) {
            log.warn("Topic arrival stage read failed, no arrival this tick", exception);
            return Set.of();
        }
    }

    /** One workspace's read of the statuses written from {@code lowerBound} to the tick's start. */
    private TopicVectorsFind find(String workspaceId, Instant lowerBound, Instant start) {
        return TopicVectorsFind.builder()
                .workspaceId(workspaceId)
                .task(TopicEmbedding.SUMMARY_TASK)
                .lowerBound(lowerBound)
                .upperBound(start)
                .createdAfter(createdAfter(lowerBound))
                .limit(config.getVectors().getMaxTracesPerTick())
                .build();
    }

    private Instant createdAfter(Instant lowerBound) {
        return createdAfter(lowerBound, aiLabelsConfig.getHorizon().toJavaDuration(),
                config.getVectors().getFind().getCreatedAtSlack().toJavaDuration());
    }

    /**
     * One workspace's turn in the fresh phase: its workspace read, then its embed calls and writes, then its new
     * watermark saved. A long-window read ({@code longWindow}) leaves its watermark as it is and, when complete, saves
     * that the pass read the workspace. Without time left for the read, it skips the workspace name lookup too.
     */
    private Mono<Visit> visit(String workspaceId, List<TopicBackfill> undrained, TopicVectorsFind find,
            boolean longWindow, @Nullable Instant missedBefore, TickBudget budget, boolean arrival) {
        var embedTimeout = config.getVectors().getEmbedTimeout().toJavaDuration();
        if (!budget.hasTimeForRead(embedTimeout)) {
            return Mono.just(Visit.notRead(TopicVectorsMetrics.Workspace.unnamed(workspaceId), NotRead.BUDGET,
                    longWindow, false,
                    ArrivalSteps.OFF));
        }
        var steps = arrival ? ArrivalSteps.STARTED : ArrivalSteps.OFF;
        return metrics.workspace(workspaceId).flatMap(workspace -> {
            var vectors = config.getVectors();
            var readableWithout = backfiller.readableWithout(workspaceId, undrained);
            if (readableWithout.isEmpty()) {
                // Its traces are the backfill's for now.
                return Mono.just(Visit.notRead(workspace, NotRead.OVER_EXCLUDED, longWindow, true, steps));
            }
            long readStarted = System.nanoTime();
            return read(workspace, find.toBuilder().excludedProjectIds(readableWithout.get()).build(), longWindow,
                    budget, embedTimeout)
                    .flatMap(read -> !read.read().complete() && read.read().candidates().isEmpty()
                            // The budget ran out before the read: nothing was read, so the watermark stays.
                            ? Mono.just(Visit.notRead(workspace, NotRead.BUDGET, longWindow, false, steps))
                            : serve(workspace, read.read(), read.find(), longWindow, missedBefore, budget,
                                    readStarted, steps))
                    .onErrorResume(exception -> {
                        metrics.readFailure("find", workspace, exception);
                        log.warn("Topic vectors window read failed, reading it again next tick, workspace '{}'",
                                workspaceId, exception);
                        return Mono.just(Visit.builder().workspace(workspace).longWindow(longWindow).complete(false)
                                .result(TickResult.EMPTY).started(List.of()).readTime(since(readStarted))
                                .embedTime(Duration.ZERO).arrival(steps).build());
                    });
        });
    }

    /** A window read and the find it read, whose upper bound the watermark takes. */
    private record FoundRead(@NonNull TopicVectorsFind find, @NonNull WindowRead read) {
    }

    /**
     * The workspace's window read. A fresh read over a find size guard would fail the same way on every tick and keep
     * its watermark, so it is read again as of the middle of its status span ({@link #narrowed}), halving until one fits
     * or the span is under {@link #MIN_NARROWED_SPAN}. The watermark then moves to that bound, and the next tick reads
     * from there. The daily pass is read in slices already and keeps its watermark, so it fails as it is.
     */
    private Mono<FoundRead> read(TopicVectorsMetrics.Workspace workspace, TopicVectorsFind find, boolean longWindow,
            TickBudget budget, Duration embedTimeout) {
        var vectors = config.getVectors();
        return WindowRead.read(dao, find, longWindow ? vectors.getFind().getSliceSize().toJavaDuration() : null,
                budget, vectors.getFind().getMaxExecutionTime().toJavaDuration(), embedTimeout)
                .map(read -> new FoundRead(find, read))
                .onErrorResume(exception -> {
                    var narrowed = longWindow ? null : narrowed(find);
                    if (narrowed == null || !TopicsReadErrors.isTooLarge(exception)) {
                        return Mono.error(exception);
                    }
                    metrics.readNarrowed(workspace);
                    log.warn("Topic vectors window read over its guard, reading the statuses up to '{}' instead, "
                            + "workspace '{}'", narrowed.upperBound(), workspace.id(), exception);
                    return read(workspace, narrowed, false, budget, embedTimeout);
                });
    }

    /** The find as of the middle of its status span; null when that half is under {@link #MIN_NARROWED_SPAN}. */
    static @Nullable TopicVectorsFind narrowed(@NonNull TopicVectorsFind find) {
        var half = Duration.between(find.lowerBound(), find.upperBound()).dividedBy(2);
        return half.compareTo(MIN_NARROWED_SPAN) < 0
                ? null
                : find.toBuilder().upperBound(find.lowerBound().plus(half)).asOf(true).build();
    }

    /**
     * A turn after its read: the candidates embedded and written, then the watermark saved, or, after a complete
     * long-window read, that the pass read the workspace.
     */
    private Mono<Visit> serve(TopicVectorsMetrics.Workspace workspace, WindowRead read, TopicVectorsFind find,
            boolean longWindow, @Nullable Instant missedBefore, TickBudget budget, long readStarted,
            ArrivalSteps steps) {
        var workspaceId = workspace.id();
        return backfiller.withoutNewProjects(workspaceId, read.candidates(), budget.start()).flatMap(served -> {
            var readTime = since(readStarted);
            long embedStarted = System.nanoTime();
            return writer.embedAndWrite(workspace, served.candidates(), budget, missedBefore, steps)
                    .flatMap(written -> {
                        var complete = read.complete() && !written.leftForLater(served.candidates());
                        var visit = Visit.builder().workspace(workspace).longWindow(longWindow).complete(complete)
                                .result(written.result()).started(served.started()).readTime(readTime)
                                .embedTime(since(embedStarted)).arrival(written.arrival()).build();
                        if (longWindow) {
                            return complete
                                    ? saveProgress(workspaceId, () -> workspaces.setVectorsLongWindowRead(workspaceId,
                                            budget.start())).thenReturn(visit)
                                    : Mono.just(visit);
                        }
                        // The traces of the projects it started are the backfill's from now on.
                        var settled = new HashSet<>(written.settled());
                        var servedKeys = served.candidates().stream().map(VectorKey::of).collect(Collectors.toSet());
                        read.candidates().stream().map(VectorKey::of)
                                .filter(key -> !servedKeys.contains(key))
                                .forEach(settled::add);
                        var watermark = watermark(read.candidates(), settled, find.limit(), find.upperBound());
                        return saveProgress(workspaceId, () -> workspaces.setVectorsWatermark(workspaceId, watermark,
                                budget.start()))
                                .thenReturn(visit);
                    });
        });
    }

    /**
     * The backfills not drained yet, read again after a discovery wrote rows, so this tick's backfill already reads
     * them. When that read fails, the backfill reads those from before the discovery, and the new rows wait a tick.
     */
    private Mono<Map<String, List<TopicBackfill>>> undrainedAfterDiscovery(List<String> workspaceIds,
            Map<String, List<TopicBackfill>> before) {
        return backfiller.undrained(workspaceIds).onErrorResume(exception -> {
            log.warn("Topic vectors backfills not read after discovery, backfilling those from before it",
                    exception);
            return Mono.just(before);
        });
    }

    /** One small write per workspace, as its turn ends: a tick cancelled later keeps the workspaces it finished. */
    private Mono<Void> saveProgress(String workspaceId, Runnable save) {
        return Mono.<Void>fromRunnable(save)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    // Only costs a wider read next tick, or the workspace's long read again.
                    log.warn("Topic vectors progress not saved, workspace '{}'", workspaceId, exception);
                    return Mono.empty();
                });
    }

    /**
     * The backfill phase: each workspace's projects not drained yet, the ones its workspace read started included,
     * except in a workspace the embed service backed off for in the fresh phase.
     */
    private Mono<TickResult> backfill(List<Visit> visits, Map<String, List<TopicBackfill>> undrained,
            Instant lowerBound, TickBudget budget) {
        long started = System.nanoTime();
        var workspaces = visits.stream()
                .filter(visit -> !visit.result().skipped().containsKey(SkipReason.EMBED_FAILED))
                .map(visit -> {
                    var workspaceId = visit.workspace().id();
                    var projects = new ArrayList<>(undrained.getOrDefault(workspaceId, List.of()));
                    // After a discovery the backfills read again already hold the projects the fresh phase started.
                    var known = projects.stream().map(TopicBackfill::projectId).collect(Collectors.toSet());
                    visit.started().stream().filter(project -> !known.contains(project.projectId()))
                            .forEach(projects::add);
                    return TopicVectorBackfiller.WorkspaceBackfill.builder()
                            .workspace(visit.workspace())
                            .projects(projects)
                            .window(find(workspaceId, lowerBound, budget.start()))
                            .arrival(visit.arrival())
                            .build();
                })
                .toList();
        return backfiller.backfill(workspaces, budget)
                .doOnNext(__ -> metrics.phase("backfill", since(started)));
    }

    private Mono<TickResult> finish(List<Visit> visits) {
        int notRead = 0;
        int overExcluded = 0;
        var result = TickResult.EMPTY;
        var read = Duration.ZERO;
        var embed = Duration.ZERO;
        for (var visit : visits) {
            if (visit.notRead() != null) {
                metrics.workspaceNotRead(visit.workspace(), visit.notRead());
                notRead++;
                overExcluded += visit.notRead() == NotRead.OVER_EXCLUDED ? 1 : 0;
            }
            result = result.plus(visit.result());
            read = read.plus(visit.readTime());
            embed = embed.plus(visit.embedTime());
        }
        if (notRead > 0) {
            log.warn("Topic vectors workspace reads did not read workspaces '{}' of '{}', of them over the "
                    + "excluded-projects limit, left to the backfill '{}'", notRead, visits.size(), overExcluded);
        }
        metrics.phase("find", read);
        metrics.phase("embed", embed);
        return Mono.just(result);
    }

    /**
     * One workspace's part of the fresh phase. {@code longWindow} when it was the long-window pass's read;
     * {@code notRead} why the workspace read did not read it, null when it did;
     * {@code complete} when its read saw its whole window and left none of its traces for a later tick, or it was left
     * to the backfill; {@code started} the projects its read saw for the first time; {@code arrival} its topic on
     * arrival, which the backfill phase goes on with.
     */
    @Builder(toBuilder = true)
    private record Visit(@NonNull TopicVectorsMetrics.Workspace workspace, boolean longWindow,
            @Nullable NotRead notRead,
            boolean complete, @NonNull TickResult result, @NonNull List<TopicBackfill> started,
            @NonNull Duration readTime,
            @NonNull Duration embedTime, @NonNull ArrivalSteps arrival) {

        static Visit notRead(TopicVectorsMetrics.Workspace workspace, NotRead reason, boolean longWindow,
                boolean complete,
                ArrivalSteps arrival) {
            return Visit.builder().workspace(workspace).longWindow(longWindow).notRead(reason).complete(complete)
                    .result(TickResult.EMPTY).started(List.of()).readTime(Duration.ZERO).embedTime(Duration.ZERO)
                    .arrival(arrival).build();
        }
    }

    /**
     * A workspace's next watermark after a read that found {@code found}, oldest status first, and settled
     * {@code settled} (written, never to be embedded, or left to the backfill): the status of the first one not settled, so
     * the next read finds it again; with none, the last status found when the read hit {@code limit}, as more may share
     * it, and else the read's upper bound.
     */
    static Instant watermark(@NonNull List<Candidate> found, @NonNull Set<VectorKey> settled, int limit,
            @NonNull Instant upperBound) {
        return found.stream()
                .filter(candidate -> !settled.contains(VectorKey.of(candidate)))
                .findFirst()
                .map(Candidate::statusUpdatedAt)
                .orElseGet(() -> found.size() < limit ? upperBound : found.getLast().statusUpdatedAt());
    }

    /** A workspace's read starts {@code overlap} before its watermark, never before the window. */
    static Instant readFrom(@NonNull Instant windowStart, @Nullable Instant watermark, @NonNull Duration overlap) {
        if (watermark == null) {
            return windowStart;
        }
        var fromWatermark = watermark.minus(overlap);
        return fromWatermark.isAfter(windowStart) ? fromWatermark : windowStart;
    }

    private static Duration since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private static <T> List<T> shuffled(List<T> items) {
        var shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    /** Never before the task first applied, so no run scans statuses from before the task existed. */
    static Instant lowerBound(@NonNull Instant appliesFrom, @NonNull Instant now, @NonNull Duration window) {
        var windowStart = now.minus(window);
        return windowStart.isAfter(appliesFrom) ? windowStart : appliesFrom;
    }

    static Instant earliestAppliesFrom(@NonNull AiLabelTask task) {
        return task.versions().stream().map(AiLabelTask.Version::appliesFrom).min(Comparator.naturalOrder())
                .orElseThrow();
    }

    /**
     * The window read's {@code trace_created_at} bound: ai-labels labels only traces created within its horizon, so a
     * status written at or after {@code lowerBound} belongs to a trace created at or after {@code lowerBound - horizon};
     * {@code slack} covers the time from the sweep to the status write and a horizon changed since.
     */
    static Instant createdAfter(@NonNull Instant lowerBound, @NonNull Duration horizon, @NonNull Duration slack) {
        return lowerBound.minus(horizon).minus(slack);
    }
}
