package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.utils.JsonUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * A run's window as its rows arrive: the rows with no text after stripping, or a vector of the wrong size, are
 * skipped, and the kept ones copied in. The arrays are copied again only when the count was off: grown when more
 * rows came, cut when fewer were kept.
 */
final class TopicWindowBuilder {

    private static final int D = TopicEmbedding.DIMENSIONS;

    private String[] texts;
    private float[] vectors;
    private UUID[] traceIds;
    private Instant[] createdAt;
    private final TreeMap<Integer, Integer> versions = new TreeMap<>();
    private int size;
    private int read;
    private int skipped;

    TopicWindowBuilder(int capacity) {
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

    RunWindow build(long windowTraces, int provisionalBelow) {
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
        var versionShares = stats.putObject("task_version_shares");
        versions.forEach((version, count) -> versionShares.put(String.valueOf(version), (double) count / size));
        stats.put("vectors", size);
        stats.put("provisional", size < provisionalBelow);
        return new RunWindow(sortedTexts, vectors, traces, versions, stats);
    }

    private void resize(int capacity) {
        texts = Arrays.copyOf(texts, capacity);
        vectors = Arrays.copyOf(vectors, capacity * D);
        traceIds = Arrays.copyOf(traceIds, capacity);
        createdAt = Arrays.copyOf(createdAt, capacity);
    }

    /** Row i takes the old row {@code order[i]}, in place: each cycle of the permutation moves through one spare row. */
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
