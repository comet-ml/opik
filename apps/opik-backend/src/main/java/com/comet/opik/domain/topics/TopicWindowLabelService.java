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
 * The labelling pass: gives every trace of a saved run's window its {@code trace_topics} row. A run finds its topics on
 * a sample of the window when the window is larger than {@code topics.run.maxVectors}, and its own insert labels
 * only the sample; this pass labels the rest, by the same rule: the {@link TopicAlgorithm#labeller} of the saved
 * prototypes. A list with no topic labels every trace Other, as its run did. The rows carry
 * {@code source = 'run'} and the run's id, as the run's own rows do: they are the run's labels.
 *
 * <p>Resumable: what is left is read from ClickHouse each time, as the window's traces without a row of the run. A pass
 * cut by its deadline or its subscriber, or stopped by a failed read or insert, loses nothing: the list stays saved,
 * the rows written stay, and a later pass goes on from there. No cursor is kept: the project's {@code labels_complete}
 * is false from the save until a pass ends complete, and the discovery job calls the pass again while it is false.
 *
 * <p>Bounded: the window is cut at every {@code topics.run.labelSliceTraces}-th trace creation time, so each
 * slice holds that many traces however the traffic is spread, and a page's read costs about one slice. A slice is read
 * in pages of at most {@code topics.run.labelPageTraces} traces, each read, assigned and inserted before the next, so
 * the heap holds one page of vectors. The count, every read and every insert have their own timeout, and the whole
 * pass ends at the caller's deadline. A page's prototype assignment, the pass's CPU step, runs on {@code parallel()},
 * off the ClickHouse driver's thread that emitted the page.
 *
 * <p>A trace whose vector lands behind the cursor while the pass runs is not on any later page. So before a pass marks
 * the labels complete, it reads the whole window once more for a trace left: if there is one, the pass ends cut, and
 * the next pass labels it.
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
     * start; {@code to} is its window end or its save, whichever is later.
     */
    private record Saved(UUID runId, Instant from, Instant to, TopicLabeller labeller) {
    }

    /** One slice of the window, {@code [from, to)}. */
    record Slice(Instant from, Instant to) {
    }

    /** One page a slice read: {@code number} counts from 1, {@code after} is the trace id it read past. */
    private record Page(Slice slice, int number, String after, List<TopicWindowDAO.WindowVector> rows) {

        String last() {
            return rows.getLast().traceId().toString();
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
     * What a pass did, folded over its stream. {@code windowTraces} is the window's count, once read. {@code skipped}
     * are the traces whose vector is not {@link TopicEmbedding#usable}, as the run leaves them out of its window: they
     * get no row, and every later pass reads and skips them again. {@code failed} when a page check, a read or an
     * insert stopped it; {@code complete} when the stream reached its end.
     */
    private record Totals(Long windowTraces, long labelled, long other, long skipped, boolean failed,
            boolean complete) {

        static final Totals ZERO = new Totals(null, 0, 0, 0, false, false);
        static final Totals END = new Totals(null, 0, 0, 0, false, true);
        static final Totals FAILED = new Totals(null, 0, 0, 0, true, false);

        static Totals window(long traces) {
            return new Totals(traces, 0, 0, 0, false, false);
        }

        Totals plus(Totals that) {
            return new Totals(windowTraces != null ? windowTraces : that.windowTraces, labelled + that.labelled,
                    other + that.other, skipped + that.skipped, failed || that.failed, complete || that.complete);
        }

        LabelPassOutcome outcome() {
            if (failed) {
                return LabelPassOutcome.FAILED;
            }
            return complete ? LabelPassOutcome.COMPLETE : LabelPassOutcome.CUT;
        }
    }

    /**
     * Labels the traces of the project's last saved window that have no {@code trace_topics} row of that run yet, until
     * none is left or {@code deadline}. A pass that ends complete marks the run's labels complete. Never errors: how it ended is the outcome. A project
     * with no saved list is complete. The caller keeps a run of the same project from saving meanwhile: that would make
     * this pass label with a list that is no longer the project's.
     */
    Mono<LabelPassOutcome> labelWindow(@NonNull TopicsMetrics.Workspace workspace, @NonNull UUID projectId,
            @NonNull Instant deadline, @NonNull LabelTrigger trigger) {
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
        var windowUntil = list.project().windowUntil();
        var lastSuccessAt = list.project().lastSuccessAt();
        var to = lastSuccessAt != null && lastSuccessAt.isAfter(windowUntil) ? lastSuccessAt : windowUntil;
        var from = windowUntil.minus(Duration.ofDays(config.getRun().getWindowDays()));
        return new Saved(list.project().listRunId(), from, to,
                algorithm.labeller(list.topics(), list.project().otherVectors()));
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
        return steps(workspace, projectId, saved)
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

    /**
     * The pass's outcome. One that reached the end checks the window for a trace left, and is cut when it finds one;
     * otherwise it marks the run's labels complete, and fails when the check or the mark fails.
     */
    private Mono<LabelPassOutcome> finish(String workspaceId, UUID projectId, Saved saved, Totals totals) {
        var outcome = totals.outcome();
        if (outcome == LabelPassOutcome.CUT) {
            log.info("Topics labelling pass cut by its deadline, run '{}', project '{}'", saved.runId(), projectId);
        }
        if (outcome != LabelPassOutcome.COMPLETE) {
            return Mono.just(outcome);
        }
        return anyLeft(workspaceId, projectId, saved)
                .flatMap(left -> {
                    if (left) {
                        log.info("Topics labelling pass found a trace left behind its pages, a later pass labels it, "
                                + "run '{}', project '{}'", saved.runId(), projectId);
                        return Mono.just(LabelPassOutcome.CUT);
                    }
                    return store.markLabelsComplete(workspaceId, projectId, saved.runId())
                            .doOnNext(marked -> {
                                if (!marked) {
                                    log.info("Topics labelling pass complete for a run that is no longer saved, "
                                            + "run '{}', project '{}'", saved.runId(), projectId);
                                }
                            })
                            .thenReturn(LabelPassOutcome.COMPLETE);
                })
                .onErrorResume(exception -> {
                    log.warn(
                            "Topics labelling pass could not check or mark its run complete, a later pass does, run '{}', "
                                    + "project '{}'",
                            saved.runId(), projectId, exception);
                    return Mono.just(LabelPassOutcome.FAILED);
                });
    }

    /**
     * Whether the window has a trace with a usable vector and no row of the run. Traces with an unusable vector never
     * get one, so a full page of them reads on to the next.
     */
    private Mono<Boolean> anyLeft(String workspaceId, UUID projectId, Saved saved) {
        int chunk = config.getRun().getLabelPageTraces();
        var window = new Slice(saved.from(), saved.to());
        return page(workspaceId, projectId, saved, window, 1, "", chunk)
                .expand(page -> page.hasNext(chunk)
                        ? page(workspaceId, projectId, saved, window, page.number() + 1, page.last(), chunk)
                        : Mono.empty())
                .any(page -> page.rows().stream().anyMatch(row -> TopicEmbedding.usable(row.vector())));
    }

    private void ended(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, LabelTrigger trigger,
            LabelPassOutcome outcome, Totals totals, long started) {
        var duration = Duration.ofNanos(System.nanoTime() - started);
        metrics.phase(workspace, "label", duration);
        metrics.labelPass(workspace, trigger, outcome);
        log.info("Topics labelling pass ended, run '{}', project '{}', trigger '{}', outcome '{}', window traces '{}', "
                + "labelled '{}', Other '{}', skipped '{}', seconds '{}'", saved.runId(), projectId, trigger.getValue(),
                outcome.getValue(), totals.windowTraces(), totals.labelled(), totals.other(), totals.skipped(),
                duration.toMillis() / 1000.0);
    }

    /** The window's count, then what each page added. */
    private Flux<Totals> steps(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved) {
        int chunk = config.getRun().getLabelPageTraces();
        int sliceTraces = config.getRun().getLabelSliceTraces();
        var countTimeout = config.getRun().getCountTimeout().toJavaDuration();
        // A full slice takes ceil(sliceTraces / chunk) pages and one empty page; the rest is margin for ties on a
        // bound and for traces that got their vector after the count.
        int maxPages = (sliceTraces + chunk - 1) / chunk + 2;
        return windowDAO.countAndBounds(workspace.id(), projectId, saved.from(), saved.to(), sliceTraces,
                countTimeout.toSeconds())
                .timeout(countTimeout)
                .flatMapMany(window -> Flux.fromIterable(slices(saved.from(), saved.to(), window.bounds()))
                        // The next page is read only once the step before it has inserted: the heap holds one page.
                        .concatMap(slice -> step(workspace, projectId, saved, slice, 1, "", chunk, maxPages)
                                .expand(step -> {
                                    var page = step.page();
                                    return page.hasNext(chunk) && page.number() < maxPages
                                            ? step(workspace, projectId, saved, slice, page.number() + 1, page.last(),
                                                    chunk, maxPages)
                                            : Mono.empty();
                                })
                                .map(Step::totals))
                        .startWith(Totals.window(window.traces())));
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

    private Mono<Step> step(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Slice slice, int number,
            String after, int chunk, int maxPages) {
        return page(workspace.id(), projectId, saved, slice, number, after, chunk)
                .flatMap(page -> labelPage(workspace, projectId, saved, page, chunk, maxPages)
                        .map(totals -> new Step(page, totals)));
    }

    private Mono<Page> page(String workspaceId, UUID projectId, Saved saved, Slice slice, int number, String after,
            int chunk) {
        var readTimeout = config.getRun().getReadTimeout().toJavaDuration();
        return windowDAO.readUnlabelled(workspaceId, projectId, saved.runId(), slice.from(), slice.to(), after, chunk,
                readTimeout.toSeconds())
                .collectList()
                .timeout(readTimeout)
                .map(rows -> new Page(slice, number, after, rows));
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
        var traces = page.rows().stream().map(row -> new WindowTrace(row.traceId(), row.traceCreatedAt())).toList();
        return Mono.fromCallable(() -> saved.labeller()
                .label(page.rows().stream().map(TopicWindowDAO.WindowVector::vector).toList()))
                .subscribeOn(Schedulers.parallel())
                .flatMap(labels -> {
                    var rows = TopicRunRows.traceTopics(workspace.id(), projectId, saved.runId(), traces, labels);
                    long other = rows.stream().filter(row -> TraceTopic.OTHER.equals(row.topicId())).count();
                    var totals = new Totals(null, rows.size(), other, labels.length - rows.size(), capped, false);
                    return store.insertTraceTopics(workspace, rows).thenReturn(totals);
                });
    }
}
