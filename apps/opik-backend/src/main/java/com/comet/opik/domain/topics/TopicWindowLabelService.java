package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
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
import java.util.Arrays;
import java.util.List;
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
 * <p>Bounded: the window is cut at every {@value #SLICE_TRACES}-th trace creation time, so each slice holds that many
 * traces however the traffic is spread, and a page's read costs about one slice. A slice is read in pages of at most
 * {@value #CHUNK} traces, each read, assigned and inserted before the next, so the heap holds one page of vectors.
 * Every read and insert ends by the deadline, and no page starts with less than {@link #MIN_PAGE_TIME} left.
 *
 * <p>The run's saved sizes ({@code topics.size}) and its logged Other share stay the sample's: they are written before
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
    static final Duration MIN_PAGE_TIME = Duration.ofSeconds(10);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull WorkspaceNameService workspaceNames;
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
     * window's exact count. {@code complete} is true when every trace of the window with a usable vector has a row of
     * the run; false when the pass was cut or stopped, and a later call finishes it. {@code skipped} are the traces
     * whose vector is not {@link TopicsRebuildService#DIMENSIONS} long, as the run leaves them out of its window: they
     * get no row, and every later pass of a new run reads and skips them again.
     */
    @Builder(toBuilder = true)
    public record Pass(UUID runId, long windowTraces, long labelled, long other, int skipped, boolean complete) {
    }

    /** The saved list a pass labels with: the traces created in {@code [from, to)}, the run's window. */
    private record Saved(UUID runId, Instant from, Instant to, TopicLabeller labeller) {
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
     * failure ends the pass, which says so. The caller runs it as a claimed run of the project
     * ({@link TopicRunDAO#claim}), so no build of the project saves meanwhile: that would make this pass label with a
     * list that is no longer the project's.
     */
    public Pass labelWindow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull Instant deadline,
            @NonNull Trigger trigger) {
        return labelWindow(workspaceId, projectId, deadline, trigger, CHUNK, SLICE_TRACES);
    }

    Pass labelWindow(String workspaceId, UUID projectId, Instant deadline, Trigger trigger, int chunk,
            int sliceTraces) {
        long started = System.nanoTime();
        var workspace = workspace(workspaceId);
        Optional<Saved> saved;
        try {
            saved = readSaved(workspaceId, projectId);
        } catch (RuntimeException exception) {
            log.warn("Topics labelling pass could not read the saved list, project '{}'", projectId, exception);
            metrics.labelPass(workspace, trigger, false);
            return Pass.builder().complete(false).build();
        }
        if (saved.isEmpty()) {
            return Pass.builder().complete(true).build();
        }
        var pass = new Labelling(workspace, projectId, saved.get(), deadline, chunk);
        try {
            pass.complete = pass.label(sliceTraces);
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
        metrics.phase(workspace, "label", duration);
        metrics.labelPass(workspace, trigger, pass.complete);
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
     * Sets {@code labels_complete} for the run, only while the project still names it. A failed write leaves it unset:
     * the job's next run calls the pass again, which finds nothing left and marks it.
     */
    /** The metrics' workspace; its name falls back to the id, so the pass goes on. */
    private TopicsMetrics.Workspace workspace(String workspaceId) {
        try {
            return new TopicsMetrics.Workspace(workspaceId, workspaceNames.nameOrId(workspaceId));
        } catch (RuntimeException exception) {
            return new TopicsMetrics.Workspace(workspaceId, workspaceId);
        }
    }

    private void markComplete(String workspaceId, UUID projectId, UUID runId) {
        int updated;
        try {
            updated = template.inTransaction(WRITE,
                    handle -> handle.attach(TopicDAO.class).markLabelsComplete(workspaceId, projectId, runId));
        } catch (RuntimeException exception) {
            log.warn("Topics labelling pass could not mark run '{}' complete, project '{}', errors '{}'; the "
                    + "first-build job runs the pass again", runId, projectId,
                    ExceptionUtils
                            .getThrowableList(exception).stream().map(TopicsErrors::formatError).toList());
            return;
        }
        if (updated == 0) {
            log.info("Topics labelling pass complete, but run '{}' is no longer the saved run of project '{}'; its "
                    + "labels stay as the newer run left them", runId, projectId);
        }
    }

    /**
     * The project's saved list, its window and the labeller of its prototypes. The project row and its topics are read
     * in one transaction, so they are of the same save; the rest is built after it.
     */
    private Optional<Saved> readSaved(String workspaceId, UUID projectId) {
        var rows = template.inTransaction(READ_ONLY, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            var project = topicDAO.findProjects(workspaceId, List.of(projectId)).stream().findFirst();
            if (project.isEmpty() || project.get().listRunId() == null || project.get().windowUntil() == null) {
                return Optional.<SavedRows>empty();
            }
            return Optional.of(new SavedRows(project.get(), topicDAO.findByProject(workspaceId, projectId)));
        });
        return rows.map(saved -> {
            var to = saved.project().windowUntil();
            var from = to.minus(Duration.ofDays(config.getBuild().getWindowDays()));
            var labeller = algorithm.labeller(saved.topics(), saved.project().otherVectors());
            return new Saved(saved.project().listRunId(), from, to,
                    labeller.orElse(TopicWindowLabelService::allOther));
        });
    }

    private record SavedRows(TopicProject project, List<Topic> topics) {
    }

    /** The labels of a list with no topic: every trace is Other, as its run labelled them. */
    private static UUID[] allOther(float[] vectors) {
        var out = new UUID[vectors.length / TopicEmbedding.DIMENSIONS];
        Arrays.fill(out, TraceTopic.OTHER);
        return out;
    }

    private final class Labelling {

        private final TopicsMetrics.Workspace workspace;
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

        Labelling(TopicsMetrics.Workspace workspace, UUID projectId, Saved saved, Instant deadline, int chunk) {
            this.workspace = workspace;
            this.workspaceId = workspace.id();
            this.projectId = projectId;
            this.saved = saved;
            this.deadline = deadline;
            this.chunk = chunk;
        }

        /** True when every slice was read to its end; a slice stopped by a page check leaves the pass incomplete. */
        boolean label(int sliceTraces) {
            var left = timeLeft();
            var window = windowDAO.countAndBounds(workspaceId, projectId, saved.from(), saved.to(), sliceTraces,
                    seconds(config.getBuild().getCountTimeout().toJavaDuration(), left))
                    .block(left);
            windowTraces = window.traces();
            var bounds = new ArrayList<Instant>();
            bounds.add(saved.from());
            bounds.addAll(window.bounds());
            bounds.add(saved.to());
            // A full slice takes ceil(sliceTraces / chunk) pages and one empty page; the rest is margin for ties on a
            // bound and for traces that got their vector after the count.
            int maxPages = (sliceTraces + chunk - 1) / chunk + 2;
            boolean whole = true;
            for (int slice = 0; slice + 1 < bounds.size(); slice++) {
                if (bounds.get(slice).isBefore(bounds.get(slice + 1))) {
                    whole &= labelSlice(bounds.get(slice), bounds.get(slice + 1), maxPages);
                }
            }
            return whole;
        }

        /**
         * Labels the slice's unlabelled traces page by page. False when a page check stops it first: a page that does
         * not move past the last trace id, or more pages than the slice can hold.
         */
        private boolean labelSlice(Instant from, Instant to, int maxPages) {
            var after = "";
            for (int pages = 0; pages < maxPages; pages++) {
                var left = timeLeft();
                var page = windowDAO.readUnlabelled(workspaceId, projectId, saved.runId(), from, to, after, chunk,
                        seconds(config.getBuild().getReadTimeout().toJavaDuration(), left))
                        .collectList()
                        .block(left);
                if (page.isEmpty()) {
                    return true;
                }
                var last = page.getLast().traceId().toString();
                if (last.compareTo(after) <= 0) {
                    log.warn("Topics labelling pass stopped a slice, run '{}', project '{}', slice '[{}, {})': a page "
                            + "did not move past trace '{}'", saved.runId(), projectId, from, to, after);
                    return false;
                }
                insert(assign(page));
                after = last;
                if (page.size() < chunk) {
                    return true;
                }
            }
            log.warn("Topics labelling pass stopped a slice, run '{}', project '{}', slice '[{}, {})': more than '{}' "
                    + "pages", saved.runId(), projectId, from, to, maxPages);
            return false;
        }

        private List<TraceTopic> assign(List<TopicWindowDAO.WindowVector> page) {
            int dimensions = TopicEmbedding.DIMENSIONS;
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
            var build = config.getBuild();
            traceTopicsDAO.insert(rows)
                    .timeout(Duration.ofSeconds(seconds(build.getReadTimeout().toJavaDuration(), left)))
                    .retryWhen(Retry.backoff(build.getInsertRetries(), build.getInsertBackoff().toJavaDuration()))
                    .block(left);
            labelled += rows.size();
            other += rows.stream().filter(row -> TraceTopic.OTHER.equals(row.topicId())).count();
            metrics.saved(workspace, "trace_topics", rows.size());
        }

        private static long seconds(Duration timeout, Duration left) {
            return Math.min(timeout.toSeconds(), left.toSeconds());
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
