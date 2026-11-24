package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.topics.TopicVectorBackfillDAO.Backfill;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
 * vectors in {@code topic_vectors}. Runs on the leader only. A tick has two phases, each visiting the workspaces up to
 * {@code embedConcurrency} at a time, each workspace with one embed call in flight:
 * <ul>
 * <li>The fresh phase: per workspace, one read of the done statuses written in {@code window}, all its drained projects
 * together, up to {@code maxTracesPerTick}, oldest first, then their embed calls and writes. It keeps new traces
 * served. It starts at the workspace's watermark in {@code topic_vector_watermarks}, less {@code watermarkOverlap}, and
 * never before the window: the read moves it past the statuses it gave a vector, never will (a refused input, a zero
 * vector) or left to the backfill, never past one it left for a later try, and saves it when the workspace's turn ends,
 * so a cancelled tick keeps what its finished turns did. Once per {@code longWindowInterval} ({@link LongWindowPass}) it
 * reads the long window instead, in slices, for rows it missed, such as a status that became visible more than
 * {@code watermarkOverlap} after a later one was read, and leaves the watermark as it is.</li>
 * <li>The backfill phase, with the budget the fresh phase left: per workspace, its projects not drained yet
 * ({@link TopicVectorBackfiller}).</li>
 * </ul>
 * The fresh phase leaves out the projects not drained yet, and the backfill reads only those, so a trace is embedded
 * by one phase in a tick, and a project's backlog is never embedded mixed with the others'.
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

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig;
    private final @NonNull TopicWorkspaceService workspaces;
    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull AiLabelTaskRegistry registry;
    private final @NonNull TopicVectorWriter writer;
    private final @NonNull TopicVectorBackfiller backfiller;
    private final @NonNull LongWindowPass longWindowPass;
    private final @NonNull TopicVectorsMetrics metrics;

    /**
     * Fails with {@link IllegalStateException} when ai-labels has no {@code trace_summary} task: no tick can run, so the
     * job counts the tick as failed rather than as one that ran and found nothing.
     */
    public Mono<TickResult> tick(@NonNull Instant start) {
        return Mono.defer(() -> {
            var task = registry.find(TopicEmbedding.SUMMARY_TASK).orElseThrow(() -> new IllegalStateException(
                    "ai-labels has no task '%s'".formatted(TopicEmbedding.SUMMARY_TASK)));
            var appliesFrom = earliestAppliesFrom(task);
            return longWindowPass.startIfDue().flatMap(pass -> tick(start, appliesFrom, pass));
        });
    }

    private Mono<TickResult> tick(Instant start, Instant appliesFrom, boolean pass) {
        var vectors = config.getVectors();
        var lowerBound = lowerBound(appliesFrom, start,
                (pass ? longWindow(vectors) : vectors.getWindow()).toJavaDuration());
        var passResult = TickResult.EMPTY.toBuilder().longWindowPass(pass).build();
        if (lowerBound.isAfter(start)) {
            return (pass ? longWindowPass.finish(start, true) : Mono.<Void>empty()).thenReturn(passResult);
        }
        var backfillLowerBound = lowerBound(appliesFrom, start, longWindow(vectors).toJavaDuration());
        var budget = TickBudget.of(start, vectors.getTickBudget().toJavaDuration(), Clock.systemUTC());
        // Only the long-window pass can write a status older than the short window: those are the rows it recovers.
        var missedBefore = pass ? start.minus(vectors.getWindow().toJavaDuration()) : null;
        return Mono.fromCallable(() -> {
            var workspaceIds = workspaces.enabledWorkspaceIds();
            return new Enabled(shuffled(workspaceIds), workspaces.vectorsWatermarks(workspaceIds));
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(enabled -> backfiller.discover(enabled.workspaceIds(), backfillLowerBound,
                        createdAfter(backfillLowerBound), budget)
                        .then(backfiller.undrained(enabled.workspaceIds()))
                        .flatMap(undrained -> Flux.fromIterable(enabled.workspaceIds())
                                .flatMap(workspaceId -> visit(workspaceId,
                                        undrained.getOrDefault(workspaceId, List.of()),
                                        find(workspaceId, pass
                                                ? lowerBound
                                                : readFrom(lowerBound, enabled.watermarks().get(workspaceId),
                                                        vectors.getWatermarkOverlap().toJavaDuration()),
                                                start),
                                        pass, missedBefore, budget),
                                        vectors.getEmbedConcurrency())
                                .collectList()
                                .flatMap(visits -> finish(visits).flatMap(fresh -> {
                                    var drained = visits.stream().allMatch(Visit::complete);
                                    return (pass ? longWindowPass.finish(start, drained) : Mono.<Void>empty())
                                            .then(backfill(visits, undrained, backfillLowerBound, budget))
                                            .map(backfilled -> fresh.plus(backfilled).plus(passResult));
                                }))));
    }

    private record Enabled(@NonNull List<String> workspaceIds, @NonNull Map<String, Instant> watermarks) {
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
     * watermark saved. A long-window pass ({@code pass}) leaves its watermark as it is. Without time left for the read,
     * it skips the workspace name lookup too.
     */
    private Mono<Visit> visit(String workspaceId, List<Backfill> undrained, TopicVectorsFind find, boolean pass,
            @Nullable Instant missedBefore, TickBudget budget) {
        var embedTimeout = config.getVectors().getEmbedTimeout().toJavaDuration();
        if (!budget.hasTimeForRead(embedTimeout)) {
            return Mono.just(Visit.notRead(TopicVectorsMetrics.Workspace.unnamed(workspaceId), false));
        }
        return metrics.workspace(workspaceId).flatMap(workspace -> {
            var vectors = config.getVectors();
            var readableWithout = backfiller.readableWithout(workspaceId, undrained);
            if (readableWithout.isEmpty()) {
                // Its traces are the backfill's for now; it does not hold the long-window pass.
                return Mono.just(Visit.notRead(workspace, true));
            }
            long readStarted = System.nanoTime();
            return WindowRead.read(dao,
                    find.toBuilder().excludedProjectIds(readableWithout.get()).build(),
                    pass ? vectors.getFind().getSliceSize().toJavaDuration() : null, budget,
                    vectors.getFind().getMaxExecutionTime().toJavaDuration(), embedTimeout)
                    .flatMap(read -> !read.complete() && read.candidates().isEmpty()
                            // The budget ran out before the read: nothing was read, so the watermark stays.
                            ? Mono.just(Visit.notRead(workspace, false))
                            : serve(workspace, read, find, pass, missedBefore, budget, readStarted))
                    .onErrorResume(exception -> {
                        metrics.readFailure("find", workspace, exception);
                        log.warn("Topic vectors window read failed, reading it again next tick, workspace '{}'",
                                workspaceId, exception);
                        return Mono.just(new Visit(workspace, true, false, TickResult.EMPTY, List.of(),
                                since(readStarted), Duration.ZERO));
                    });
        });
    }

    /** A turn after its read: the candidates embedded and written, then, outside a pass, the watermark saved. */
    private Mono<Visit> serve(TopicVectorsMetrics.Workspace workspace, WindowRead read, TopicVectorsFind find,
            boolean pass, @Nullable Instant missedBefore, TickBudget budget, long readStarted) {
        var workspaceId = workspace.id();
        return backfiller.withoutNewProjects(workspaceId, read.candidates(), budget.start()).flatMap(served -> {
            var readTime = since(readStarted);
            long embedStarted = System.nanoTime();
            return writer.embedAndWrite(workspace, served.candidates(), budget, missedBefore).flatMap(written -> {
                var complete = read.complete() && !written.leftForLater(served.candidates());
                var visit = new Visit(workspace, true, complete, written.result(), served.started(), readTime,
                        since(embedStarted));
                if (pass) {
                    return Mono.just(visit);
                }
                // The traces of the projects it started are the backfill's from now on.
                var settled = new HashSet<>(written.settled());
                var servedKeys = served.candidates().stream().map(VectorKey::of).collect(Collectors.toSet());
                read.candidates().stream().map(VectorKey::of)
                        .filter(key -> !servedKeys.contains(key))
                        .forEach(settled::add);
                return saveWatermark(workspaceId,
                        watermark(read.candidates(), settled, find.limit(), find.upperBound())).thenReturn(visit);
            });
        });
    }

    /** One small write per workspace, as its turn ends: a tick cancelled later keeps the workspaces it finished. */
    private Mono<Void> saveWatermark(String workspaceId, Instant watermark) {
        return Mono.<Void>fromRunnable(() -> workspaces.setVectorsWatermark(workspaceId, watermark))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    // Only costs a wider read next tick: the old watermark stays behind the new one.
                    log.warn("Topic vectors watermark not saved, workspace '{}'", workspaceId, exception);
                    return Mono.empty();
                });
    }

    /**
     * The backfill phase: each workspace's projects not drained yet, the ones its workspace read started included,
     * except in a workspace the embed service backed off for in the fresh phase.
     */
    private Mono<TickResult> backfill(List<Visit> visits, Map<String, List<Backfill>> undrained,
            Instant lowerBound, TickBudget budget) {
        long started = System.nanoTime();
        return Flux.fromIterable(visits)
                .flatMap(visit -> {
                    var workspaceId = visit.workspace().id();
                    var projects = new ArrayList<>(undrained.getOrDefault(workspaceId, List.of()));
                    projects.addAll(visit.started());
                    return visit.result().skipped().containsKey(SkipReason.EMBED_FAILED)
                            ? Mono.just(TickResult.EMPTY)
                            : backfiller.backfill(visit.workspace(), projects,
                                    find(workspaceId, lowerBound, budget.start()), budget);
                }, config.getVectors().getEmbedConcurrency())
                .reduce(TickResult.EMPTY, TickResult::plus)
                .doOnNext(__ -> metrics.phase("backfill", since(started)));
    }

    private Mono<TickResult> finish(List<Visit> visits) {
        var notRead = new ArrayList<TopicVectorsMetrics.Workspace>();
        var result = TickResult.EMPTY;
        var read = Duration.ZERO;
        var embed = Duration.ZERO;
        for (var visit : visits) {
            if (!visit.reached()) {
                notRead.add(visit.workspace());
            }
            result = result.plus(visit.result());
            read = read.plus(visit.readTime());
            embed = embed.plus(visit.embedTime());
        }
        if (!notRead.isEmpty()) {
            metrics.workspacesNotRead(notRead);
            log.warn("Topic vectors workspace reads did not read workspaces '{}' of '{}'", notRead.size(),
                    visits.size());
        }
        metrics.phase("find", read);
        metrics.phase("embed", embed);
        return Mono.just(result);
    }

    /**
     * One workspace's part of the fresh phase. {@code reached} is false when the budget left no time to read it, or it
     * has too many projects in backfill; {@code complete} when its read saw its whole window and left none of its
     * traces for a later tick, or it was left to the backfill; {@code started} the projects its read saw for the first
     * time.
     */
    private record Visit(@NonNull TopicVectorsMetrics.Workspace workspace, boolean reached, boolean complete,
            @NonNull TickResult result, @NonNull List<Backfill> started, @NonNull Duration readTime,
            @NonNull Duration embedTime) {

        static Visit notRead(TopicVectorsMetrics.Workspace workspace, boolean complete) {
            return new Visit(workspace, false, complete, TickResult.EMPTY, List.of(), Duration.ZERO, Duration.ZERO);
        }
    }

    /** The long-window pass's and the backfill's window: {@code backfillWindow} when set, else {@code longWindow}. */
    static io.dropwizard.util.Duration longWindow(@NonNull TopicsConfig.VectorsConfig vectors) {
        return vectors.getBackfillWindow().toMilliseconds() > 0 ? vectors.getBackfillWindow() : vectors.getLongWindow();
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
