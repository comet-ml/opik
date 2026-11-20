package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reads a run's window: all of it, or an even sample of {@code topics.build.maxVectors} traces when it is larger. Rows
 * with no text after stripping, or a vector of the wrong size, are skipped. Any failure is {@code window_read}.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicWindowReader {

    /**
     * A run's ClickHouse read stops after this, so one slow read does not hold the server for the whole run; so does
     * each attempt of its {@code trace_topics} insert.
     */
    static final long READ_SECONDS = 120;
    /**
     * ClickHouse {@code max_execution_time} of one window count: the run's own, the labelling pass's, and the
     * first-build job's (a workspace's projects, or one project's by hand).
     */
    static final long COUNT_SECONDS = 60;

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicWindowDAO windowDAO;

    /** The window as the build reads it: per trace, in time order, its text and vector. */
    record Window(String[] texts, float[] vectors, List<WindowTrace> traces, TreeMap<Integer, Integer> versions) {

        TopicWindow topicWindow() {
            return TopicWindow.builder().texts(texts).vectors(vectors).build();
        }
    }

    record WindowTrace(UUID traceId, Instant traceCreatedAt) {
    }

    static long readSeconds(@NonNull TopicsConfig.BuildConfig build) {
        return Math.min(READ_SECONDS, build.getProjectTimeout().toSeconds());
    }

    /**
     * The window, or its sample. The stats get the size of both: the window's is the exact count the first-build job
     * takes.
     */
    Mono<Window> read(@NonNull TopicRun run) {
        long readSeconds = readSeconds(config.getBuild());
        var count = windowDAO.countByProject(run.workspaceId(), Set.of(run.projectId()), run.from(), run.to(),
                COUNT_SECONDS)
                .filter(projectCount -> projectCount.projectId().equals(run.projectId()))
                .map(TopicWindowDAO.ProjectCount::traces)
                .reduce(0L, Long::sum)
                .timeout(Duration.ofSeconds(COUNT_SECONDS));
        var rows = windowDAO.readWindow(run.workspaceId(), run.projectId(), run.from(), run.to(),
                config.getBuild().getMaxVectors(), readSeconds)
                .collectList()
                .timeout(Duration.ofSeconds(readSeconds));
        return count.flatMap(windowTraces -> rows.map(read -> kept(run, windowTraces, read)))
                .onErrorMap(error -> !(error instanceof TopicsRunException),
                        error -> new TopicsRunException(RunError.WINDOW_READ, "the window read failed", error))
                .map(TopicWindowReader::flatten);
    }

    private static List<TopicWindowDAO.WindowRow> kept(TopicRun run, long windowTraces,
            List<TopicWindowDAO.WindowRow> rows) {
        var stats = run.stats();
        stats.put("window_traces", windowTraces);
        stats.put("sample_traces", rows.size());
        var kept = new ArrayList<TopicWindowDAO.WindowRow>(rows.size());
        int skipped = 0;
        for (var row : rows) {
            var text = TopicText.pythonStrip(row.text());
            if (text.isEmpty() || row.vector().length != TopicEmbedding.DIMENSIONS) {
                skipped++;
                continue;
            }
            kept.add(row.toBuilder().text(text).build());
        }
        // Time order, earliest first: the merge keeps each text's first trace.
        kept.sort(Comparator.comparing(TopicWindowDAO.WindowRow::traceCreatedAt)
                .thenComparing(TopicWindowDAO.WindowRow::traceId));
        stats.put("window_rows", rows.size());
        stats.put("skipped_rows", skipped);
        if (kept.isEmpty()) {
            throw new TopicsRunException(RunError.WINDOW_READ, "the window has no usable vector");
        }
        return kept;
    }

    /** The window as flat arrays, in the rows' order. */
    private static Window flatten(List<TopicWindowDAO.WindowRow> rows) {
        int n = rows.size();
        int d = TopicEmbedding.DIMENSIONS;
        var texts = new String[n];
        var vectors = new float[n * d];
        var traces = new ArrayList<WindowTrace>(n);
        var versions = new TreeMap<Integer, Integer>();
        for (int i = 0; i < n; i++) {
            var row = rows.get(i);
            texts[i] = row.text();
            System.arraycopy(row.vector(), 0, vectors, i * d, d);
            versions.merge(row.taskVersion(), 1, Integer::sum);
            traces.add(new WindowTrace(row.traceId(), row.traceCreatedAt()));
        }
        return new Window(texts, vectors, traces, versions);
    }
}
