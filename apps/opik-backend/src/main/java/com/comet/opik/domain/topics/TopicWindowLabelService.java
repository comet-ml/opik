package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.JsonUtils;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * cut by its deadline, an interrupt or a failed read or insert loses nothing: the list stays saved, the rows written
 * stay, and a later call goes on from there. No cursor is kept: the project's {@code labels_complete} is false from the
 * save until a pass ends complete, and the first-build job calls the pass again for every project where it is false.
 *
 * <p>Bounded: the window is read in slices of trace creation time with about {@value #SLICE_TRACES} traces each, and a
 * slice in pages of at most {@value #CHUNK} traces, each read, assigned and inserted before the next, so the heap holds
 * one page of vectors. Every read and insert ends by the deadline, and no page starts with less than
 * {@link #MIN_PAGE_TIME} left.
 *
 * <p>The run's saved sizes ({@code topics.size}) and its logged Other share stay the sample's: they are written before
 * this pass, and the sample is an even one, so its shares are the window's.
 *
 * <p>Counted: the pass that ends complete counts the window's traces per topic and logs the counts with the run's id.
 * Later runs relabel the window, so this log line is the only record of the run's counts. It is written once per run,
 * when the pass marks the labels complete, and not for a list with no topic. A failed count read is logged and the
 * labels are still marked complete.
 *
 * <p>The counts cover the run's window exactly, {@code [window_until - windowDays, window_until)}, so two runs' counts
 * compare like with like. The Topics page counts a sliding window that includes today, so its counts can differ from a
 * run's by the traces of the days at the window's edges.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWindowLabelService {

    /** Traces per page: about 15 MiB of vectors. */
    static final int CHUNK = 10_000;
    /** The traces a slice of the window holds on average. A page's read costs about its slice's keys. */
    static final int SLICE_TRACES = 50_000;
    static final Duration MIN_PAGE_TIME = Duration.ofSeconds(10);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull TopicAlgorithm algorithm;

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
     * pass was cut, and a later call finishes it. {@code counts} are the run's traces per topic id and Other that this
     * pass logged, empty when it logged none.
     */
    @Builder(toBuilder = true)
    public record Pass(UUID runId, long windowTraces, long labelled, long other, int skipped, boolean complete,
            Map<UUID, Long> counts) {
    }

    /**
     * The saved list a pass labels with. The pass labels and counts the traces created in {@code [from, to)}, the run's
     * window. {@code topicIds} are its live topics. {@code logCounts} is true when
     * a complete pass logs the run's counts: the list has a topic and the run's labels are not complete yet.
     */
    private record Saved(UUID runId, Instant from, Instant to, TopicLabeller labeller,
            List<UUID> topicIds, boolean logCounts) {
    }

    /** Ends a pass early: the deadline is too close or the thread was interrupted. */
    private static final class CutException extends RuntimeException {
        CutException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Labels the traces of the project's last saved window that have no {@code trace_topics} row of that run yet, until
     * none is left or {@code deadline}. A pass that ends complete marks the run's labels complete. Never throws: a
     * failure ends the pass, which says so. The caller holds the project's lock, as for a run: a run that saves
     * meanwhile would make this pass label with a list that is no longer the project's.
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
            return Pass.builder().complete(false).counts(Map.of()).build();
        }
        if (saved.isEmpty()) {
            return Pass.builder().complete(true).counts(Map.of()).build();
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
        if (pass.complete && !markComplete(workspaceId, projectId, saved.get().runId(), pass.counts)) {
            pass.counts = Map.of();
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
     * Sets {@code labels_complete} for the run, only while the project still names it, then logs its counts. False when
     * nothing was marked. A failed write leaves it unset: the job's next run calls the pass again, which finds nothing
     * left, counts and logs again.
     */
    private boolean markComplete(String workspaceId, UUID projectId, UUID runId, Map<UUID, Long> counts) {
        int updated;
        try {
            updated = template.inTransaction(WRITE,
                    handle -> handle.attach(TopicDAO.class).markLabelsComplete(workspaceId, projectId, runId));
        } catch (RuntimeException exception) {
            log.warn("Topics labelling pass could not mark run '{}' complete, project '{}', errors '{}'; the "
                    + "first-build job runs the pass again", runId, projectId,
                    ExceptionUtils
                            .getThrowableList(exception).stream().map(TopicsRebuildService::formatError).toList());
            return false;
        }
        if (updated == 0) {
            log.info("Topics labelling pass complete, but run '{}' is no longer the saved run of project '{}'; its "
                    + "labels stay as the newer run left them", runId, projectId);
            return false;
        }
        if (!counts.isEmpty()) {
            var topicCounts = JsonUtils.createObjectNode();
            counts.forEach((topicId, traces) -> topicCounts.put(topicId.toString(), traces));
            log.info("Topics run '{}' counts, project '{}', topic_counts '{}'", runId, projectId, topicCounts);
        }
        return true;
    }

    /** The project's saved list, its window and the labeller of its prototypes, read in one transaction. */
    private Optional<Saved> readSaved(String workspaceId, UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            var project = topicDAO.findProjects(workspaceId, List.of(projectId)).stream().findFirst();
            if (project.isEmpty() || project.get().listRunId() == null || project.get().windowUntil() == null) {
                return Optional.empty();
            }
            var to = project.get().windowUntil();
            var from = to.minus(Duration.ofDays(config.getBuild().getWindowDays()));
            var topics = topicDAO.findByProject(workspaceId, projectId);
            var labeller = algorithm.labeller(topics, project.get().otherVectors());
            return Optional.of(new Saved(project.get().listRunId(), from, to,
                    labeller.orElse(TopicWindowLabelService::allOther), topics.stream().map(Topic::id).toList(),
                    labeller.isPresent() && !project.get().labelsComplete()));
        });
    }

    /** The labels of a list with no topic: every trace is Other, as its run labelled them. */
    private static UUID[] allOther(float[] vectors) {
        var out = new UUID[vectors.length / TopicsRebuildService.DIMENSIONS];
        Arrays.fill(out, TraceTopic.OTHER);
        return out;
    }

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
        /** The run's traces per topic id, read once every trace is labelled; empty when the pass saves none. */
        private Map<UUID, Long> counts = Map.of();

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
            if (saved.logCounts()) {
                counts = count();
            }
        }

        /**
         * The run's window's traces per live topic and Other, zero for one with none. Too little time left cuts the
         * pass, as a page does, so a later pass counts.
         */
        private Map<UUID, Long> count() {
            var left = timeLeft();
            try {
                var traces = new LinkedHashMap<UUID, Long>();
                saved.topicIds().forEach(topicId -> traces.put(topicId, 0L));
                traces.put(TraceTopic.OTHER, 0L);
                traceTopicsDAO.countByTopic(workspaceId, projectId, saved.from(), saved.to(),
                        Math.min(TopicsRebuildService.READ_SECONDS, left.toSeconds()))
                        .collectList()
                        .block(left)
                        .forEach(count -> traces.merge(count.topicId(), count.traces(), Long::sum));
                return traces;
            } catch (RuntimeException exception) {
                if (ExceptionUtils.indexOfType(exception, InterruptedException.class) >= 0) {
                    Thread.currentThread().interrupt();
                    throw new CutException("interrupted");
                }
                log.warn("Topics labelling pass could not count run '{}', project '{}', errors '{}'; the run's "
                        + "counts are not logged", saved.runId(), projectId,
                        ExceptionUtils.getThrowableList(exception).stream().map(TopicsRebuildService::formatError)
                                .toList());
                return Map.of();
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
            var labels = saved.labeller().label(vectors);
            var rows = new ArrayList<TraceTopic>(kept.size());
            for (int i = 0; i < kept.size(); i++) {
                rows.add(TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(kept.get(i).traceId())
                        .traceCreatedAt(kept.get(i).traceCreatedAt())
                        .topicId(labels[i])
                        .source(TraceTopic.Source.RUN)
                        .runId(saved.runId())
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
                    .counts(counts)
                    .build();
        }
    }
}
