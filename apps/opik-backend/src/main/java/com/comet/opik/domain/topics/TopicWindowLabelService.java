package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.util.retry.Retry;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The labelling pass: gives every trace of a saved run's window its {@code trace_topics} row. A run finds its topics on
 * a sample of the window when the window is larger than {@code topics.build.maxVectors}, and its own insert labels
 * only the sample; this pass labels the rest, by the same rule: {@link PrototypeAssigner} over the saved prototypes of the live topics
 * (the nearest at cosine 0.75 or more, else Other, with the known-Other prototypes and their gate). The rows carry
 * {@code source = 'run'} and the run's id, as the run's own rows do: they are the run's labels.
 *
 * <p>Resumable: what is left is read from ClickHouse each time, as the window's traces without a row of the run. A pass
 * cut by its deadline, an interrupt or a failed read or insert loses nothing: the list stays saved, the rows written
 * stay, and a later call goes on from there. No cursor is kept: the state's {@code labels_complete} is false from the
 * save until a pass ends complete, and the first-build job calls the pass again for every project where it is false.
 *
 * <p>Bounded: the window is read in slices of trace creation time with about {@value #SLICE_TRACES} traces each, and a
 * slice in pages of at most {@value #CHUNK} traces, each read, assigned and inserted before the next, so the heap holds
 * one page of vectors. Every read and insert ends by the deadline, and no page starts with less than
 * {@link #MIN_PAGE_TIME} left.
 *
 * <p>The run's saved counts ({@code topics.size}, {@code stats.other_share}) stay the sample's: they are written before
 * this pass, and the sample is an even one, so its shares are the window's.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWindowLabelService {

    /** Traces per page: about 15 MiB of vectors. */
    static final int CHUNK = 10_000;
    /** The traces a slice of the window holds on average. A page's read costs about its slice's keys. */
    static final int SLICE_TRACES = 50_000;
    /** No page starts with less time than this to the deadline. */
    static final Duration MIN_PAGE_TIME = Duration.ofSeconds(10);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;
    private final @NonNull TopicsMetrics metrics;

    /** Who called a pass: the run right after its save, or the first-build job going on with an unfinished one. */
    @Getter
    @RequiredArgsConstructor
    public enum Trigger {
        RUN("run"),
        RESUME("resume");

        private final String value;
    }

    /**
     * What a pass did. {@code runId} is null when the project has no saved list. {@code windowTraces} is the
     * window's exact count. {@code complete} is true when no trace of the window is left without a row of the run; false when the
     * pass was cut, and a later call finishes it.
     */
    @Builder
    public record Pass(UUID runId, long windowTraces, long labelled, long other, int skipped, boolean complete) {
    }

    /** The saved list a pass labels with. {@code topicIds} maps the assigner's topic index to the topic's id. */
    private record Saved(UUID runId, Instant from, Instant to, PrototypeAssigner assigner, List<UUID> topicIds) {
    }

    /** Ends a pass early: the deadline is too close or the thread was interrupted. */
    private static final class CutException extends RuntimeException {
        CutException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Labels the traces of the project's last saved window that have no {@code trace_topics} row of that run yet, until
     * none is left or {@code deadline}. A pass that ends complete marks the run's labels complete, only while the state
     * still names that run. Never throws: a failure ends the pass, which says so. The caller holds the project's lock,
     * as for a run: a run that saves meanwhile would make this pass label with a list that is no longer the project's.
     */
    public Pass labelWindow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull Instant deadline,
            @NonNull Trigger trigger) {
        return labelWindow(workspaceId, projectId, deadline, trigger, CHUNK, SLICE_TRACES);
    }

    Pass labelWindow(String workspaceId, UUID projectId, Instant deadline, Trigger trigger, int chunk,
            int sliceTraces) {
        long started = System.nanoTime();
        Optional<Saved> saved;
        try {
            saved = readSaved(workspaceId, projectId);
        } catch (RuntimeException exception) {
            log.warn("Topics labelling pass could not read the saved list, project '{}'", projectId, exception);
            metrics.labelPass(workspaceId, trigger, false);
            return Pass.builder().complete(false).build();
        }
        if (saved.isEmpty()) {
            return Pass.builder().complete(true).build();
        }
        var pass = new Labelling(workspaceId, projectId, saved.get(), deadline, chunk);
        try {
            pass.label(sliceTraces);
            pass.complete = true;
        } catch (CutException exception) {
            log.info("Topics labelling pass cut, run '{}', project '{}', reason '{}'", saved.get().runId(), projectId,
                    exception.getMessage());
        } catch (RuntimeException exception) {
            if (ExceptionUtils.indexOfType(exception, InterruptedException.class) >= 0) {
                Thread.currentThread().interrupt();
            }
            log.warn("Topics labelling pass failed, run '{}', project '{}'; a later pass labels the rest",
                    saved.get().runId(), projectId, exception);
        }
        if (pass.complete) {
            markComplete(workspaceId, projectId, saved.get().runId());
        }
        var duration = Duration.ofNanos(System.nanoTime() - started);
        metrics.phase(workspaceId, "label", duration);
        metrics.labelPass(workspaceId, trigger, pass.complete);
        var result = pass.result();
        log.info(
                "Topics labelling pass ended, run '{}', project '{}', trigger '{}', window traces '{}', labelled '{}', "
                        + "Other '{}', skipped '{}', complete '{}', seconds '{}'",
                result.runId(), projectId,
                trigger.getValue(), result.windowTraces(), result.labelled(), result.other(), result.skipped(),
                result.complete(), duration.toMillis() / 1000.0);
        return result;
    }

    /**
     * Sets {@code labels_complete} for the run. A failed write leaves it false: the job's next run calls the pass
     * again, which finds nothing left and writes it again.
     */
    private void markComplete(String workspaceId, UUID projectId, UUID runId) {
        try {
            int updated = template.inTransaction(WRITE,
                    handle -> handle.attach(TopicDAO.class).markLabelsComplete(workspaceId, projectId, runId));
            if (updated == 0) {
                log.info("Topics labelling pass complete, but run '{}' is no longer the last saved run of project "
                        + "'{}'; its labels stay as the newer run left them", runId, projectId);
            }
        } catch (RuntimeException exception) {
            // A Jdbi statement error quotes the bound arguments: the exception types only, and a SQL error's state
            // and code, as a failed run logs them.
            log.warn("Topics labelling pass could not mark run '{}' complete, project '{}', errors '{}'; the "
                    + "first-build job runs the pass again", runId, projectId,
                    ExceptionUtils
                            .getThrowableList(exception).stream().map(TopicsRebuildService::formatError).toList());
        }
    }

    /** The project's last saved run, its window and the assigner of its prototypes, read in one transaction. */
    private Optional<Saved> readSaved(String workspaceId, UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            var state = topicDAO.findStates(workspaceId, List.of(projectId)).stream().findFirst();
            if (state.isEmpty() || state.get().lastRunId() == null || state.get().windowUntil() == null) {
                return Optional.empty();
            }
            var to = state.get().windowUntil();
            var from = to.minus(Duration.ofDays(config.getBuild().getWindowDays()));
            // As the run built it: the topic prototypes in position order, then the known-Other ones. Only live topics
            // label: a prototype of a retired topic is left out.
            var live = topicDAO.findLive(workspaceId, projectId).stream().map(Topic::id).collect(Collectors.toSet());
            var topicIds = new ArrayList<UUID>();
            var topicPrototypes = new ArrayList<float[]>();
            var owners = new ArrayList<Integer>();
            var otherPrototypes = new ArrayList<float[]>();
            for (var prototype : topicDAO.findPrototypes(workspaceId, projectId)) {
                if (prototype.topicId() == null) {
                    otherPrototypes.add(prototype.values());
                    continue;
                }
                if (!live.contains(prototype.topicId())) {
                    continue;
                }
                int owner = topicIds.indexOf(prototype.topicId());
                if (owner < 0) {
                    topicIds.add(prototype.topicId());
                    owner = topicIds.size() - 1;
                }
                topicPrototypes.add(prototype.values());
                owners.add(owner);
            }
            var assigner = new PrototypeAssigner(topicPrototypes, owners, otherPrototypes,
                    TopicsRebuildService.DIMENSIONS);
            return Optional.of(new Saved(state.get().lastRunId(), from, to, assigner, List.copyOf(topicIds)));
        });
    }

    /** One pass's progress. */
    private final class Labelling {

        private final String workspaceId;
        private final UUID projectId;
        private final Saved saved;
        private final Instant deadline;
        private final int chunk;
        private long windowTraces;
        private long labelled;
        private long other;
        private int skipped;
        private boolean complete;

        Labelling(String workspaceId, UUID projectId, Saved saved, Instant deadline, int chunk) {
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            this.saved = saved;
            this.deadline = deadline;
            this.chunk = chunk;
        }

        void label(int sliceTraces) {
            var left = timeLeft();
            windowTraces = windowDAO.countByProject(workspaceId, List.of(projectId), saved.from(), saved.to(),
                    Math.min(TopicsRebuildService.COUNT_SECONDS, left.toSeconds()))
                    .filter(count -> count.projectId().equals(projectId))
                    .map(TopicWindowDAO.ProjectCount::traces)
                    .reduce(0L, Long::sum)
                    .block(left);
            long slices = Math.max(1, (windowTraces + sliceTraces - 1) / sliceTraces);
            var span = Duration.between(saved.from(), saved.to());
            for (long slice = 0; slice < slices; slice++) {
                labelSlice(saved.from().plus(span.multipliedBy(slice).dividedBy(slices)),
                        saved.from().plus(span.multipliedBy(slice + 1).dividedBy(slices)));
            }
        }

        private void labelSlice(Instant from, Instant to) {
            var after = "";
            while (true) {
                var left = timeLeft();
                var page = windowDAO.readUnlabelled(workspaceId, projectId, saved.runId(), from, to, after, chunk,
                        Math.min(TopicsRebuildService.READ_SECONDS, left.toSeconds()))
                        .collectList()
                        .block(left);
                if (page.isEmpty()) {
                    return;
                }
                insert(assign(page));
                after = page.getLast().traceId().toString();
                if (page.size() < chunk) {
                    return;
                }
            }
        }

        private List<TraceTopic> assign(List<TopicWindowDAO.WindowVector> page) {
            int dimensions = TopicsRebuildService.DIMENSIONS;
            var kept = new ArrayList<TopicWindowDAO.WindowVector>(page.size());
            for (var row : page) {
                if (row.vector().length == dimensions) {
                    kept.add(row);
                } else {
                    skipped++;
                }
            }
            var vectors = new float[kept.size() * dimensions];
            for (int i = 0; i < kept.size(); i++) {
                System.arraycopy(kept.get(i).vector(), 0, vectors, i * dimensions, dimensions);
            }
            var labels = saved.assigner().assign(vectors);
            var rows = new ArrayList<TraceTopic>(kept.size());
            for (int i = 0; i < kept.size(); i++) {
                int label = labels[i];
                rows.add(TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(kept.get(i).traceId())
                        .traceCreatedAt(kept.get(i).traceCreatedAt())
                        .topicId(label == PrototypeAssigner.OTHER ? TraceTopic.OTHER : saved.topicIds().get(label))
                        .source(TraceTopic.Source.RUN)
                        .runId(saved.runId().toString())
                        .build());
            }
            return rows;
        }

        /** Retried as the run's own insert is: a repeat writes the same rows, one per trace. */
        private void insert(List<TraceTopic> rows) {
            if (rows.isEmpty()) {
                return;
            }
            var left = timeLeft();
            traceTopicsDAO.insert(rows)
                    .timeout(Duration.ofSeconds(Math.min(TopicsRebuildService.READ_SECONDS, left.toSeconds())))
                    .retryWhen(Retry.backoff(TopicsRebuildService.INSERT_RETRIES, TopicsRebuildService.INSERT_BACKOFF))
                    .block(left);
            labelled += rows.size();
            other += rows.stream().filter(row -> TraceTopic.OTHER.equals(row.topicId())).count();
            metrics.saved(workspaceId, "trace_topics", rows.size());
        }

        /** The time to the deadline; throws when a page should not start. */
        private Duration timeLeft() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CutException("interrupted");
            }
            var left = Duration.between(Instant.now(), deadline);
            if (left.compareTo(MIN_PAGE_TIME) < 0) {
                throw new CutException("deadline");
            }
            return left;
        }

        Pass result() {
            return Pass.builder()
                    .runId(saved.runId())
                    .windowTraces(windowTraces)
                    .labelled(labelled)
                    .other(other)
                    .skipped(skipped)
                    .complete(complete)
                    .build();
        }
    }
}
