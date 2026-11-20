package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The labelling pass: gives every trace of a saved run's window its {@code trace_topics} row. A run finds its topics on
 * a sample of the window when the window is larger than {@code topics.build.maxVectors}, and its own insert labels
 * only the sample; this pass labels the rest, by the same rule: the {@link TopicAlgorithm#labeller} of the saved
 * prototypes. A list with no topic labels every trace Other, as its run did. The rows carry
 * {@code source = 'run'} and the run's id, as the run's own rows do: they are the run's labels.
 *
 * <p>Resumable: what is left is read from ClickHouse each time, as the window's traces without a row of the run. A pass
 * cut by its deadline or its subscriber, or stopped by a failed read or insert, loses nothing: the list stays saved,
 * the rows written stay, and a later pass goes on from there. No cursor is kept: the project's {@code labels_complete}
 * is false from the save until a pass ends complete, and the first-build job calls the pass again while it is false.
 *
 * <p>Bounded: the window is cut at every {@code topics.build.labelSliceTraces}-th trace creation time, so each
 * slice holds that many traces however the traffic is spread, and a page's read costs about one slice. A slice is read
 * in pages of at most {@code topics.build.labelPageTraces} traces, each read, assigned and inserted before the next, so
 * the heap holds one page of vectors. The count, every read and every insert have their own timeout, and the whole
 * pass ends at the caller's deadline.
 *
 * <p>The run's saved sizes ({@code topics.size}) and its logged Other share stay the sample's: they are written before
 * this pass, and the sample is an even one, so its shares are the window's.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWindowLabelService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull WorkspaceNameService workspaceNames;
    private final @NonNull TopicAlgorithm algorithm;
    private final Scheduler jdbi = Schedulers.boundedElastic();

    /** Who called a pass: the job right after a run's save, or the job going on with an unfinished pass. */
    @Getter
    @RequiredArgsConstructor
    enum Trigger {
        RUN("run"),
        RESUME("resume");

        private final String value;
    }

    /**
     * How a pass ended. Complete: every trace of the window with a usable vector has a row of the run, and the run's
     * labels are marked complete. Cut: the deadline came, or the subscriber cancelled. Failed: a read, an insert or a
     * page check stopped it. A pass that is not complete is finished by a later one.
     */
    @Getter
    @RequiredArgsConstructor
    enum PassOutcome {
        COMPLETE("complete"),
        CUT("cut"),
        FAILED("failed");

        private final String value;
    }

    /** The saved list a pass labels with: the traces created in {@code [from, to)}, the run's window. */
    private record Saved(UUID runId, Instant from, Instant to, TopicLabeller labeller) {
    }

    private record SavedRows(TopicProject project, List<Topic> topics) {
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
    }

    /** One page, labelled and inserted, with what it added to the pass. */
    private record Step(Page page, Totals totals) {
    }

    /**
     * What a pass did. {@code skipped} are the traces whose vector is not {@link TopicEmbedding#usable}, as the run
     * leaves them out of its window: they get no row, and every later pass reads and skips them again.
     * {@code stopped} when a page check ended a slice early.
     */
    private record Totals(long labelled, long other, long skipped, boolean stopped) {

        static final Totals ZERO = new Totals(0, 0, 0, false);

        Totals plus(Totals that) {
            return new Totals(labelled + that.labelled, other + that.other, skipped + that.skipped,
                    stopped || that.stopped);
        }
    }

    /**
     * Labels the traces of the project's last saved window that have no {@code trace_topics} row of that run yet, until
     * none is left or {@code deadline}. A pass that ends complete marks the run's labels complete. Never errors: how it
     * ended is the outcome. A project with no saved list is complete. The caller keeps a run of the same project from
     * saving meanwhile: that would make this pass label with a list that is no longer the project's.
     */
    Mono<PassOutcome> labelWindow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull Instant deadline,
            @NonNull Trigger trigger) {
        int chunk = config.getBuild().getLabelPageTraces();
        int sliceTraces = config.getBuild().getLabelSliceTraces();
        return jdbi(() -> workspace(workspaceId)).flatMap(workspace -> jdbi(() -> readSaved(workspaceId, projectId))
                .flatMap(saved -> saved
                        .map(found -> pass(workspace, projectId, found, deadline, trigger, chunk, sliceTraces))
                        .orElseGet(() -> Mono.just(PassOutcome.COMPLETE)))
                .onErrorResume(exception -> {
                    log.warn("Topics labelling pass could not read the saved list, project '{}'", projectId,
                            exception);
                    metrics.labelPass(workspace, trigger, PassOutcome.FAILED);
                    return Mono.just(PassOutcome.FAILED);
                }));
    }

    private Mono<PassOutcome> pass(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Instant deadline,
            Trigger trigger, int chunk, int sliceTraces) {
        long started = System.nanoTime();
        // What the pass did so far, for its log line when it is cut or fails.
        var done = new AtomicReference<>(Totals.ZERO);
        var windowTraces = new AtomicReference<Long>();
        var budget = Duration.between(Instant.now(), deadline);
        if (!budget.isPositive()) {
            log.info("Topics labelling pass cut by its deadline before it started, run '{}', project '{}'",
                    saved.runId(), projectId);
            ended(workspace, projectId, saved, trigger, PassOutcome.CUT, Totals.ZERO, null, started);
            return Mono.just(PassOutcome.CUT);
        }
        return label(workspace, projectId, saved, chunk, sliceTraces, done, windowTraces)
                .flatMap(totals -> totals.stopped()
                        ? Mono.just(PassOutcome.FAILED)
                        : markComplete(workspace.id(), projectId, saved.runId()).thenReturn(PassOutcome.COMPLETE))
                .onErrorResume(exception -> {
                    log.warn("Topics labelling pass failed, run '{}', project '{}'; a later pass labels the rest",
                            saved.runId(), projectId, exception);
                    return Mono.just(PassOutcome.FAILED);
                })
                .timeout(budget, Mono.fromSupplier(() -> {
                    log.info("Topics labelling pass cut by its deadline, run '{}', project '{}'", saved.runId(),
                            projectId);
                    return PassOutcome.CUT;
                }))
                .doOnNext(outcome -> ended(workspace, projectId, saved, trigger, outcome, done.get(),
                        windowTraces.get(), started))
                .doOnCancel(() -> {
                    log.info("Topics labelling pass cut by its caller, run '{}', project '{}'", saved.runId(),
                            projectId);
                    ended(workspace, projectId, saved, trigger, PassOutcome.CUT, done.get(), windowTraces.get(),
                            started);
                });
    }

    private void ended(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Trigger trigger,
            PassOutcome outcome, Totals totals, Long windowTraces, long started) {
        var duration = Duration.ofNanos(System.nanoTime() - started);
        metrics.phase(workspace, "label", duration);
        metrics.labelPass(workspace, trigger, outcome);
        log.info("Topics labelling pass ended, run '{}', project '{}', trigger '{}', outcome '{}', window traces '{}', "
                + "labelled '{}', Other '{}', skipped '{}', seconds '{}'", saved.runId(), projectId, trigger.getValue(),
                outcome.getValue(), windowTraces, totals.labelled(), totals.other(), totals.skipped(),
                duration.toMillis() / 1000.0);
    }

    private Mono<Totals> label(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, int chunk,
            int sliceTraces, AtomicReference<Totals> done, AtomicReference<Long> windowTraces) {
        var build = config.getBuild();
        var countTimeout = build.getCountTimeout().toJavaDuration();
        // A full slice takes ceil(sliceTraces / chunk) pages and one empty page; the rest is margin for ties on a
        // bound and for traces that got their vector after the count.
        int maxPages = (sliceTraces + chunk - 1) / chunk + 2;
        return windowDAO.countAndBounds(workspace.id(), projectId, saved.from(), saved.to(), sliceTraces,
                countTimeout.toSeconds())
                .timeout(countTimeout)
                .doOnNext(window -> windowTraces.set(window.traces()))
                .flatMapMany(window -> Flux.fromIterable(slices(saved.from(), saved.to(), window.bounds())))
                // The next page is read only once the step before it has inserted: the heap holds one page.
                .concatMap(slice -> step(workspace, projectId, saved, slice, 1, "", chunk, maxPages)
                        .expand(step -> {
                            var page = step.page();
                            return page.rows().size() == chunk && page.advanced() && page.number() < maxPages
                                    ? step(workspace, projectId, saved, slice, page.number() + 1, page.last(), chunk,
                                            maxPages)
                                    : Mono.empty();
                        })
                        .map(Step::totals))
                .doOnNext(totals -> done.updateAndGet(sum -> sum.plus(totals)))
                .reduce(Totals.ZERO, Totals::plus);
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
        var readTimeout = config.getBuild().getReadTimeout().toJavaDuration();
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
            log.warn("Topics labelling pass stopped a slice, run '{}', project '{}', slice '[{}, {})': a page did not "
                    + "move past trace '{}'", saved.runId(), projectId, page.slice().from(), page.slice().to(),
                    page.after());
            return Mono.just(new Totals(0, 0, 0, true));
        }
        boolean capped = page.number() == maxPages && page.rows().size() == chunk;
        if (capped) {
            log.warn("Topics labelling pass stopped a slice, run '{}', project '{}', slice '[{}, {})': more than '{}' "
                    + "pages", saved.runId(), projectId, page.slice().from(), page.slice().to(), maxPages);
        }
        var labels = saved.labeller().label(page.rows().stream().map(TopicWindowDAO.WindowVector::vector).toList());
        var rows = new ArrayList<TraceTopic>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != null) {
                var row = page.rows().get(i);
                rows.add(TraceTopic.builder()
                        .workspaceId(workspace.id())
                        .projectId(projectId)
                        .traceId(row.traceId())
                        .traceCreatedAt(row.traceCreatedAt())
                        .topicId(labels[i])
                        .source(TraceTopic.Source.RUN)
                        .runId(saved.runId())
                        .build());
            }
        }
        long other = rows.stream().filter(row -> TraceTopic.OTHER.equals(row.topicId())).count();
        var totals = new Totals(rows.size(), other, labels.length - rows.size(), capped);
        return insert(workspace, rows).thenReturn(totals);
    }

    /**
     * Inserts one page's rows, as the run inserts its own: a transient error is retried (a repeat writes the same
     * rows, one per trace), any other fails the pass. Counted ok or failed.
     */
    private Mono<Void> insert(TopicsMetrics.Workspace workspace, List<TraceTopic> rows) {
        if (rows.isEmpty()) {
            return Mono.empty();
        }
        var build = config.getBuild();
        var backoff = build.getInsertBackoff().toJavaDuration();
        return traceTopicsDAO.insert(rows)
                .timeout(build.getReadTimeout().toJavaDuration())
                .retryWhen(RetryUtils.handleClickHouseErrors(build.getInsertRetries(), backoff,
                        backoff.multipliedBy(1L << build.getInsertRetries())))
                .doOnSuccess(__ -> {
                    metrics.saved(workspace, "trace_topics", rows.size());
                    metrics.traceTopicsInsert(workspace, "ok");
                })
                .doOnError(__ -> metrics.traceTopicsInsert(workspace, "failed"))
                .then();
    }

    /** The metrics' workspace; its name falls back to the id, so the pass goes on. */
    private TopicsMetrics.Workspace workspace(String workspaceId) {
        try {
            return new TopicsMetrics.Workspace(workspaceId, workspaceNames.nameOrId(workspaceId));
        } catch (RuntimeException exception) {
            return new TopicsMetrics.Workspace(workspaceId, workspaceId);
        }
    }

    /**
     * Sets {@code labels_complete} for the run, only while the project still names it. A failed write leaves it unset:
     * the job calls the pass again, which finds nothing left and marks it.
     */
    private Mono<Void> markComplete(String workspaceId, UUID projectId, UUID runId) {
        return jdbi(() -> template.inTransaction(WRITE,
                handle -> handle.attach(TopicProjectDAO.class).markLabelsComplete(workspaceId, projectId, runId)))
                .doOnNext(updated -> {
                    if (updated == 0) {
                        log.info("Topics labelling pass complete, but run '{}' is no longer the saved run of project "
                                + "'{}'; its labels stay as the newer run left them", runId, projectId);
                    }
                })
                .onErrorResume(exception -> {
                    log.warn("Topics labelling pass could not mark run '{}' complete, project '{}', errors '{}'; the "
                            + "first-build job runs the pass again", runId, projectId,
                            TopicsErrors.formatError(exception));
                    return Mono.empty();
                })
                .then();
    }

    /**
     * The project's saved list, its window and the labeller of its prototypes. The project row and its topics are read
     * in one transaction, so they are of the same save; the rest is built after it.
     */
    private Optional<Saved> readSaved(String workspaceId, UUID projectId) {
        var rows = template.inTransaction(READ_ONLY, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            var project = handle.attach(TopicProjectDAO.class).findProjects(workspaceId, List.of(projectId)).stream()
                    .findFirst();
            if (project.isEmpty() || project.get().listRunId() == null || project.get().windowUntil() == null) {
                return Optional.<SavedRows>empty();
            }
            return Optional.of(new SavedRows(project.get(), topicDAO.findByProject(workspaceId, projectId)));
        });
        return rows.map(saved -> {
            var to = saved.project().windowUntil();
            var from = to.minus(Duration.ofDays(config.getBuild().getWindowDays()));
            return new Saved(saved.project().listRunId(), from, to,
                    algorithm.labeller(saved.topics(), saved.project().otherVectors()));
        });
    }

    private <T> Mono<T> jdbi(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(jdbi);
    }
}
