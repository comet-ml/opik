package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Reads a run's window: all of it, or an even sample of {@code topics.build.maxVectors} traces when it is larger. Rows
 * with no text after stripping, or a vector of the wrong size, are skipped. Any failure is {@code window_read}. The
 * caller counts the window: the first-build job counts it to pick the project.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicWindowReader {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicWindowDAO windowDAO;

    /** The window as the build reads it: per trace, in time order, its text and vector, plus the read's stats. */
    record Window(String[] texts, float[] vectors, List<WindowTrace> traces, TreeMap<Integer, Integer> versions,
            ObjectNode stats) {

        TopicWindow topicWindow() {
            return TopicWindow.builder().texts(texts).vectors(vectors).build();
        }
    }

    record WindowTrace(UUID traceId, Instant traceCreatedAt) {
    }

    /** {@code topics.build.readTimeout}, or the project timeout when shorter. */
    static Duration readTimeout(@NonNull TopicsConfig.BuildConfig build) {
        var read = build.getReadTimeout().toJavaDuration();
        var project = build.getProjectTimeout().toJavaDuration();
        return read.compareTo(project) < 0 ? read : project;
    }

    /**
     * The window, or its sample. {@code windowTraces} is the caller's count of the window: the stats keep it, and it
     * sizes the arrays the rows are copied into as they arrive, so the window is held once.
     */
    Mono<Window> read(@NonNull TopicRun run, long windowTraces) {
        int limit = config.getBuild().getMaxVectors();
        var readTimeout = readTimeout(config.getBuild());
        int capacity = (int) Math.max(1, Math.min(windowTraces, limit));
        return windowDAO.readWindow(run.workspaceId(), run.projectId(), run.from(), run.to(), limit,
                readTimeout.toSeconds())
                .collect(() -> new Builder(capacity), Builder::add)
                .map(builder -> builder.build(windowTraces))
                .timeout(readTimeout)
                .onErrorMap(error -> !(error instanceof TopicsRunException),
                        error -> new TopicsRunException(RunError.WINDOW_READ, "the window read failed", error));
    }

    /**
     * The kept rows, copied in as they arrive. The arrays are copied again only when the count was off: grown when more
     * rows came, cut when fewer were kept.
     */
    private static final class Builder {

        private static final int D = TopicEmbedding.DIMENSIONS;

        private String[] texts;
        private float[] vectors;
        private UUID[] traceIds;
        private Instant[] createdAt;
        private final TreeMap<Integer, Integer> versions = new TreeMap<>();
        private int size;
        private int read;
        private int skipped;

        Builder(int capacity) {
            texts = new String[capacity];
            vectors = new float[capacity * D];
            traceIds = new UUID[capacity];
            createdAt = new Instant[capacity];
        }

        void add(TopicWindowDAO.WindowRow row) {
            read++;
            var text = TopicText.pythonStrip(row.text());
            if (text.isEmpty() || row.vector().length != D) {
                skipped++;
                return;
            }
            if (size == texts.length) {
                resize(size + Math.max(1, size / 4));
            }
            texts[size] = text;
            System.arraycopy(row.vector(), 0, vectors, size * D, D);
            traceIds[size] = row.traceId();
            createdAt[size] = row.traceCreatedAt();
            versions.merge(row.taskVersion(), 1, Integer::sum);
            size++;
        }

        Window build(long windowTraces) {
            var stats = JsonUtils.createObjectNode();
            stats.put("window_traces", windowTraces);
            stats.put("sample_traces", read);
            stats.put("window_rows", read);
            stats.put("skipped_rows", skipped);
            if (size == 0) {
                throw new TopicsRunException(RunError.WINDOW_READ,
                        "the window has no usable vector, rows '%s', skipped '%s'".formatted(read, skipped));
            }
            if (size < texts.length) {
                resize(size);
            }
            // Time order, earliest first: the merge keeps each text's first trace.
            var order = IntStream.range(0, size).boxed()
                    .sorted(Comparator.<Integer, Instant>comparing(i -> createdAt[i]).thenComparing(i -> traceIds[i]))
                    .mapToInt(Integer::intValue)
                    .toArray();
            var sortedTexts = new String[size];
            var traces = new ArrayList<WindowTrace>(size);
            for (int i = 0; i < size; i++) {
                sortedTexts[i] = texts[order[i]];
                traces.add(new WindowTrace(traceIds[order[i]], createdAt[order[i]]));
            }
            permuteRows(vectors, order);
            return new Window(sortedTexts, vectors, traces, versions, stats);
        }

        private void resize(int capacity) {
            texts = Arrays.copyOf(texts, capacity);
            vectors = Arrays.copyOf(vectors, capacity * D);
            traceIds = Arrays.copyOf(traceIds, capacity);
            createdAt = Arrays.copyOf(createdAt, capacity);
        }

        /** Row i becomes row {@code order[i]}, in place: each cycle of the permutation moves through one spare row. */
        static void permuteRows(float[] vectors, int[] order) {
            var placed = new boolean[order.length];
            var spare = new float[D];
            for (int start = 0; start < order.length; start++) {
                if (placed[start] || order[start] == start) {
                    continue;
                }
                System.arraycopy(vectors, start * D, spare, 0, D);
                int at = start;
                while (order[at] != start) {
                    System.arraycopy(vectors, order[at] * D, vectors, at * D, D);
                    placed[at] = true;
                    at = order[at];
                }
                System.arraycopy(spare, 0, vectors, at * D, D);
                placed[at] = true;
            }
        }
    }
}
