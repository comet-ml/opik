package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The labelling pass: makes the latest {@code trace_topics} row of every trace of a saved run's window the topic the
 * saved list labels it with, by the {@link TopicAlgorithm#labeller} of its prototypes, the rule the run's own labels
 * follow. A list with no topic labels every trace Other, as its run did. It writes a row only where a trace's topic
 * changes or is stale (none yet, or one written before the trace's vector), with {@code source = 'run'} and the run's
 * id: a trace whose topic is already right keeps its row. A row is stamped with its page's read time, so a vector
 * written between the read and the insert leaves its trace stale.
 *
 * <p>Two sweeps of the window, slice by slice. The relabel sweep reads every trace with its vector and its current
 * topic, and writes the ones whose topic changes. The stale sweep reads only stale traces, picked before their vectors
 * are read: the ones vectored without a topic on arrival (arrival off, failed or over budget, or the project had no
 * list then), re-vectored since their topic, or vectored behind the relabel sweep's cursor. A pass makes the relabel
 * sweep only when the saved list's {@link TopicAlgorithm#labellerFingerprint} is not the one the labels were last
 * completed with ({@code labels_fingerprint}): a list whose labeller did not change since a complete pass labels every
 * trace as before, and since then every topic written with an equal labeller (arrival labels the stored vector, as this
 * pass reads it) or is stale. The stale sweep always runs, last.
 *
 * <p>Resumable: a pass cut by its deadline or its subscriber, or stopped by a failed read or insert, loses nothing:
 * the list stays saved, the rows written stay, {@code labels_complete} stays false, and the discovery job calls the
 * pass again. That pass reads the window again but writes only what is still to change. A save keeps the fingerprint
 * only when the labels were complete, so a pass never skips the relabel sweep after one that was cut.
 *
 * <p>Bounded: the window is cut at every {@code topics.run.labelSliceTraces}-th trace creation time, so each
 * slice holds that many traces however the traffic is spread, and a page's read costs about one slice. A slice is read
 * in pages of at most {@code topics.run.labelPageTraces} traces, each read, assigned and inserted before the next, so
 * the heap holds one page of vectors. The count, every read and every insert have their own timeout, and the whole
 * pass ends at the caller's deadline. A page's prototype assignment, the pass's CPU step, runs on {@code parallel()},
 * off the ClickHouse driver's thread that emitted the page.
 *
 * <p>A pass starts no sooner than {@code topics.arrival.timeout} after its list's save: an arrival step that read the
 * list before the save has ended by then, so no topic of the older list lands behind the pass's pages.
 *
 * <p>The run's saved sizes ({@code topics.size}) and its logged Other share stay the sample's: they are written before
 * this pass, and the sample is an even one, so its shares are the window's.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWindowLabelService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TopicRunStore store;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull TopicAlgorithm algorithm;

    /**
     * The saved list a pass labels with: the traces created in {@code [from, to)}. {@code from} is the run's window
     * start; {@code to} is its window end or its save, whichever is later. {@code relabel} when its labeller's
     * {@code fingerprint} is not the one the labels were last completed with.
     */
    private record Saved(UUID runId, Instant from, Instant to, Instant savedAt, TopicLabeller labeller,
            String fingerprint, boolean relabel) {
    }

    /** One sweep of the window: every trace with its current topic, or only the stale ones. */
    private enum Sweep {
        RELABEL,
        STALE
    }

    /** One slice of the window, {@code [from, to)}. */
    record Slice(Instant from, Instant to) {
    }

    /**
     * One page a slice read: {@code number} counts from 1, {@code after} is the trace id it read past. Its rows are in
     * no order: {@link #last} is their highest trace id.
     */
    private record Page(Sweep sweep, Slice slice, int number, String after, List<TopicWindowDAO.WindowVector> rows) {

        String last() {
            return rows.stream().map(row -> row.traceId().toString()).max(String::compareTo).orElse(after);
        }

        boolean advanced() {
            return !rows.isEmpty() && last().compareTo(after) > 0;
        }

        /** A full page that moved on: the slice may have more after it. */
        boolean hasNext(int chunk) {
            return rows.size() == chunk && advanced();
        }
    }

    /** One page, labelled and inserted, with what it added to the pass. */
    private record Step(Page page, Totals totals) {
    }

    /**
     * What a pass did, folded over its stream. {@code windowTraces} is the window's count, once read. {@code read} are
     * the traces its pages read, {@code written} the rows it inserted, {@code other} those of them Other.
     * {@code skipped} are the traces whose vector is not {@link TopicEmbedding#usable}, as the run leaves them out of
     * its window: they get no row, and every later relabel sweep reads and skips them again. {@code failed} when a page
     * check, a read or an insert stopped it; {@code complete} when the stream reached its end.
     */
    private record Totals(Long windowTraces, long read, long written, long other, long skipped, boolean failed,
            boolean complete) {

        static final Totals ZERO = new Totals(null, 0, 0, 0, 0, false, false);
        static final Totals END = new Totals(null, 0, 0, 0, 0, false, true);
        static final Totals FAILED = new Totals(null, 0, 0, 0, 0, true, false);

        static Totals window(long traces) {
            return new Totals(traces, 0, 0, 0, 0, false, false);
        }

        Totals plus(Totals that) {
            return new Totals(windowTraces != null ? windowTraces : that.windowTraces, read + that.read,
                    written + that.written, other + that.other, skipped + that.skipped, failed || that.failed,
                    complete || that.complete);
        }

        LabelPassOutcome outcome() {
            if (failed) {
                return LabelPassOutcome.FAILED;
            }
            return complete ? LabelPassOutcome.COMPLETE : LabelPassOutcome.CUT;
        }
    }

    /**
     * Gives the traces of the project's last saved window their topic by that list, writing only the ones that change
     * or are stale, until it is done or {@code deadline}. A pass that ends complete marks the run's labels complete,
     * with the list's labeller fingerprint. Never errors: how it
     * ended is the outcome. A project with no saved list is complete. The caller runs it as a claimed run of the project
     * ({@link TopicProjectDAO#claim}), so no run of the project saves meanwhile: that would make this pass label with a
     * list that is no longer the project's.
     */
    Mono<LabelPassOutcome> labelWindow(@NonNull UUID projectId, @NonNull Instant deadline,
            @NonNull LabelTrigger trigger) {
        return TopicsMetrics.Workspace.fromContext().flatMap(workspace -> labelWindow(workspace, projectId, deadline,
                trigger));
    }

    private Mono<LabelPassOutcome> labelWindow(TopicsMetrics.Workspace workspace, UUID projectId, Instant deadline,
            LabelTrigger trigger) {
        return store.readSaved(workspace.id(), projectId)
                .map(this::saved)
                .flatMap(saved -> pass(workspace, projectId, saved, deadline, trigger))
                .defaultIfEmpty(LabelPassOutcome.COMPLETE)
                .onErrorResume(exception -> {
                    log.warn("Topics labelling pass could not read the saved list, project '{}'", projectId,
                            exception);
                    metrics.labelPass(workspace, trigger, LabelPassOutcome.FAILED);
                    return Mono.just(LabelPassOutcome.FAILED);
                });
    }

    /**
     * The traces a saved list labels run from its window start to its window end or its save, whichever is later. A
     * nightly update saves after its window's end: its pass also labels the traces vectored since, which arrival
     * labelled by the list before the save. A discovery run's window ends at tomorrow's start, after its save.
     */
    private Saved saved(TopicRunStore.SavedList list) {
        var project = list.project();
        var windowUntil = project.windowUntil();
        var lastSuccessAt = project.lastSuccessAt();
        var to = lastSuccessAt != null && lastSuccessAt.isAfter(windowUntil) ? lastSuccessAt : windowUntil;
        var from = windowUntil.minus(Duration.ofDays(config.getRun().getWindowDays()));
        var fingerprint = algorithm.labellerFingerprint(list.topics(), project.otherVectors());
        return new Saved(project.listRunId(), from, to, lastSuccessAt != null ? lastSuccessAt : Instant.EPOCH,
                algorithm.labeller(list.topics(), project.otherVectors()), fingerprint,
                !fingerprint.equals(project.labelsFingerprint()));
    }

    private Mono<LabelPassOutcome> pass(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved,
            Instant deadline, LabelTrigger trigger) {
        long started = System.nanoTime();
        var budget = Duration.between(Instant.now(), deadline);
        if (!budget.isPositive()) {
            log.info("Topics labelling pass cut by its deadline before it started, run '{}', project '{}'",
                    saved.runId(), projectId);
            ended(workspace, projectId, saved, trigger, LabelPassOutcome.CUT, Totals.ZERO, started);
            return Mono.just(LabelPassOutcome.CUT);
        }
        // A pass's pages start once no arrival step can still be writing topics of the list before this one.
        var arrivalWait = Duration.between(Instant.now(),
                saved.savedAt().plus(config.getArrival().getTimeout().toJavaDuration()));
        return Mono.delay(arrivalWait.isNegative() ? Duration.ZERO : arrivalWait)
                .thenMany(steps(workspace, projectId, saved))
                .concatWith(Mono.just(Totals.END))
                .onErrorResume(exception -> {
                    log.warn("Topics labelling pass failed, a later pass labels the rest, run '{}', project '{}'",
                            saved.runId(), projectId, exception);
                    return Mono.just(Totals.FAILED);
                })
                // The deadline ends the stream: what it folded so far is the pass's.
                .takeUntilOther(Mono.delay(budget))
                .reduce(Totals.ZERO, Totals::plus)
                .flatMap(totals -> finish(workspace.id(), projectId, saved, totals)
                        // The check and the mark end at the deadline too: the pass is then cut, and a later one marks.
                        .timeout(left(deadline), Mono.fromSupplier(() -> {
                            log.info("Topics labelling pass cut by its deadline before it marked its run complete, "
                                    + "run '{}', project '{}'", saved.runId(), projectId);
                            return LabelPassOutcome.CUT;
                        }))
                        .doOnNext(outcome -> ended(workspace, projectId, saved, trigger, outcome, totals, started)))
                .doOnCancel(() -> {
                    log.info("Topics labelling pass cut by its caller, run '{}', project '{}'", saved.runId(),
                            projectId);
                    metrics.phase(workspace, "label", Duration.ofNanos(System.nanoTime() - started));
                    metrics.labelPass(workspace, trigger, LabelPassOutcome.CUT);
                });
    }

    /** The pass's outcome. One that reached the end marks the run's labels complete, and fails when the mark fails. */
    private Mono<LabelPassOutcome> finish(String workspaceId, UUID projectId, Saved saved, Totals totals) {
        var outcome = totals.outcome();
        if (outcome == LabelPassOutcome.CUT) {
            log.info("Topics labelling pass cut by its deadline, run '{}', project '{}'", saved.runId(), projectId);
        }
        if (outcome != LabelPassOutcome.COMPLETE) {
            return Mono.just(outcome);
        }
        return store.markLabelsComplete(workspaceId, projectId, saved.runId(), saved.fingerprint())
                .doOnNext(marked -> {
                    if (!marked) {
                        log.info("Topics labelling pass complete for a run that is no longer saved, run '{}', "
                                + "project '{}'", saved.runId(), projectId);
                    }
                })
                .thenReturn(LabelPassOutcome.COMPLETE)
                .onErrorResume(exception -> {
                    log.warn("Topics labelling pass could not mark its run complete, a later pass does, run '{}', "
                            + "project '{}'", saved.runId(), projectId, exception);
                    return Mono.just(LabelPassOutcome.FAILED);
                });
    }

    private void ended(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, LabelTrigger trigger,
            LabelPassOutcome outcome, Totals totals, long started) {
        var duration = Duration.ofNanos(System.nanoTime() - started);
        metrics.phase(workspace, "label", duration);
        metrics.labelPass(workspace, trigger, outcome);
        log.info("Topics labelling pass ended, run '{}', project '{}', trigger '{}', outcome '{}', relabel '{}', "
                + "window traces '{}', read '{}', written '{}', Other '{}', skipped '{}', seconds '{}'", saved.runId(),
                projectId, trigger.getValue(), outcome.getValue(), saved.relabel(), totals.windowTraces(),
                totals.read(), totals.written(), totals.other(), totals.skipped(), duration.toMillis() / 1000.0);
    }

    /** The window's count, then what each page of each sweep added: the relabel sweep when one is due, then the stale. */
    private Flux<Totals> steps(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved) {
        int sliceTraces = config.getRun().getLabelSliceTraces();
        var countTimeout = config.getRun().getCountTimeout().toJavaDuration();
        var sweeps = saved.relabel() ? List.of(Sweep.RELABEL, Sweep.STALE) : List.of(Sweep.STALE);
        return windowDAO.countAndBounds(projectId, saved.from(), saved.to(), sliceTraces,
                countTimeout.toSeconds())
                .timeout(countTimeout)
                .flatMapMany(window -> {
                    var slices = slices(saved.from(), saved.to(), window.bounds());
                    return Flux.fromIterable(sweeps)
                            .concatMap(sweep -> Flux.fromIterable(slices)
                                    .concatMap(slice -> sweep(workspace, projectId, saved, sweep, slice)))
                            .startWith(Totals.window(window.traces()));
                });
    }

    /** One slice of a sweep, page after page: the next page is read only once the one before has inserted. */
    private Flux<Totals> sweep(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Sweep sweep,
            Slice slice) {
        int chunk = config.getRun().getLabelPageTraces();
        // A full slice takes ceil(sliceTraces / chunk) pages and one empty page; the rest is margin for ties on a
        // bound and for traces that got their vector after the count.
        int maxPages = (config.getRun().getLabelSliceTraces() + chunk - 1) / chunk + 2;
        return step(workspace, projectId, saved, sweep, slice, 1, "", chunk, maxPages)
                .expand(step -> {
                    var page = step.page();
                    return page.hasNext(chunk) && page.number() < maxPages
                            ? step(workspace, projectId, saved, sweep, slice, page.number() + 1, page.last(), chunk,
                                    maxPages)
                            : Mono.empty();
                })
                .map(Step::totals);
    }

    private static Duration left(Instant deadline) {
        var left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** The window's slices: its bounds in order, without the empty ones that ties on a bound make. */
    static List<Slice> slices(@NonNull Instant from, @NonNull Instant to, @NonNull List<Instant> bounds) {
        var points = new ArrayList<Instant>(bounds.size() + 2);
        points.add(from);
        points.addAll(bounds);
        points.add(to);
        var slices = new ArrayList<Slice>(points.size() - 1);
        for (int i = 0; i + 1 < points.size(); i++) {
            if (points.get(i).isBefore(points.get(i + 1))) {
                slices.add(new Slice(points.get(i), points.get(i + 1)));
            }
        }
        return slices;
    }

    private Mono<Step> step(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Sweep sweep, Slice slice,
            int number, String after, int chunk, int maxPages) {
        var readTimeout = config.getRun().getReadTimeout().toJavaDuration();
        return windowDAO.readPage(projectId, slice.from(), slice.to(), after, chunk, sweep == Sweep.STALE,
                readTimeout.toSeconds())
                .collectList()
                .timeout(readTimeout)
                .map(rows -> new Page(sweep, slice, number, after, rows))
                .flatMap(page -> labelPage(workspace, projectId, saved, page, chunk, maxPages)
                        .map(totals -> new Step(page, totals)));
    }

    /**
     * Labels and inserts one page. A page that does not move past the last trace id, or a full last page of a slice
     * that used all its pages, stops the slice: the pass then ends failed.
     */
    private Mono<Totals> labelPage(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Page page,
            int chunk, int maxPages) {
        if (page.rows().isEmpty()) {
            return Mono.just(Totals.ZERO);
        }
        if (!page.advanced()) {
            log.warn("Topics labelling pass stopped a slice, a page did not move past its last trace, run '{}', "
                    + "project '{}', slice from '{}', to '{}', trace '{}'", saved.runId(), projectId,
                    page.slice().from(), page.slice().to(), page.after());
            return Mono.just(Totals.FAILED);
        }
        boolean capped = page.number() == maxPages && page.rows().size() == chunk;
        if (capped) {
            log.warn("Topics labelling pass stopped a slice past its page limit, run '{}', project '{}', slice "
                    + "from '{}', to '{}', max pages '{}'", saved.runId(), projectId, page.slice().from(),
                    page.slice().to(), maxPages);
        }
        var rows = page.rows();
        return Mono.fromCallable(() -> saved.labeller()
                .label(rows.stream().map(TopicWindowDAO.WindowVector::vector).toList()))
                .subscribeOn(Schedulers.parallel())
                .flatMap(labels -> {
                    var traces = new ArrayList<WindowTrace>();
                    var topics = new ArrayList<UUID>();
                    long skipped = 0;
                    for (int i = 0; i < rows.size(); i++) {
                        var row = rows.get(i);
                        if (labels[i] == null) {
                            skipped++;
                        } else if (row.stale() || !labels[i].equals(row.topicId())) {
                            traces.add(new WindowTrace(row.traceId(), row.traceCreatedAt()));
                            topics.add(labels[i]);
                        }
                    }
                    // Stamped with the page's read time, not the insert's: a vector written in between stays newer
                    // than its trace's row, so the trace stays stale, and its arrival row, if any, stays the latest.
                    var written = TopicRunRows.traceTopics(workspace.id(), projectId, saved.runId(), traces,
                            topics.toArray(UUID[]::new)).stream()
                            .map(row -> row.toBuilder().lastUpdatedAt(rows.getFirst().readAt()).build())
                            .toList();
                    long other = written.stream().filter(row -> TraceTopic.OTHER.equals(row.topicId())).count();
                    var totals = new Totals(null, rows.size(), written.size(), other, skipped, capped, false);
                    return store.insertTraceTopics(workspace, written).thenReturn(totals);
                });
    }
}
