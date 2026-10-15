package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fits a canonical state into a byte budget, in steps that stop as soon as it fits:
 * <ol>
 *   <li>{@code full}: nothing removed.</li>
 *   <li>{@code spans_io_pruned:<d>}: span {@code input}, {@code output} and {@code metadata} dropped from the deepest
 *       level up, {@code d} being the shallowest level dropped.</li>
 *   <li>Every span string longer than a cap L is cut in the middle, keeping 70% of L at the start and 30% at the end
 *       around a {@code …[N chars truncated]…} marker; L is the largest that fits, never below 64. Trace-level strings
 *       are cut last: {@code truncated_spans:<L>} when cutting span strings is enough, otherwise the span strings stay
 *       at 64 and the trace-level ones are cut at L too, {@code truncated:<L>}.</li>
 * </ol>
 * A state still over budget with every string at 64 is {@link OverBudget}.
 *
 * <p>Sizes are computed once, in one pass over the state; the steps then adjust them arithmetically and change the
 * state only once a step is known to fit. Lengths and caps count code points, so a cut never splits a surrogate pair
 * or a UTF-8 sequence. The state is changed in place.
 */
final class AiLabelStateFitter {

    private static final int MIN_CAP = 64;

    private static final String FULL = "full";
    private static final String SPANS_IO_PRUNED = "spans_io_pruned:";
    private static final String TRUNCATED_SPANS = "truncated_spans:";
    private static final String TRUNCATED = "truncated:";

    private static final String SPANS = "spans";
    private static final Set<String> SPAN_IO = Set.of("input", "output", "metadata");

    private static final String MARKER_OPEN = "…[";
    private static final String MARKER_CLOSE = " chars truncated]…";
    private static final long MARKER_BYTES = AiLabelJson.stringBytes(MARKER_OPEN) - 2
            + AiLabelJson.stringBytes(MARKER_CLOSE) - 2;

    sealed interface Fit permits Fitted, OverBudget {
    }

    record Fitted(@NonNull ObjectNode state, long bytes, @NonNull String compactionLevel) implements Fit {
    }

    record OverBudget(long bytes) implements Fit {
    }

    private final ObjectNode state;
    /** Index d: the bytes dropping span I/O at depth d saves, and the spans at d that carry any. */
    private final List<Long> spanIoBytesByDepth = new ArrayList<>();
    private final List<List<ObjectNode>> spansWithIoByDepth = new ArrayList<>();
    /** Strings longer than {@link #MIN_CAP}, the only ones step 2 can cut. Span I/O is gone by then. */
    private final List<StringSlot> spanStrings = new ArrayList<>();
    private final List<StringSlot> traceStrings = new ArrayList<>();

    private AiLabelStateFitter(ObjectNode state) {
        this.state = state;
    }

    static Fit fit(@NonNull ObjectNode state, long budgetBytes) {
        return new AiLabelStateFitter(state).fitWithin(budgetBytes);
    }

    /** The serialized size of a state, by the same arithmetic the steps use. */
    static long sizeOf(@NonNull ObjectNode state) {
        return new AiLabelStateFitter(state).annotateTrace();
    }

    private Fit fitWithin(long budget) {
        long bytes = annotateTrace();
        if (bytes <= budget) {
            return new Fitted(state, bytes, FULL);
        }

        for (int depth = spanIoBytesByDepth.size() - 1; depth >= 1; depth--) {
            bytes -= spanIoBytesByDepth.get(depth);
            if (bytes <= budget) {
                pruneSpanIo(depth);
                return new Fitted(state, bytes, SPANS_IO_PRUNED + depth);
            }
        }
        pruneSpanIo(1);

        int spanCap = largestFittingCap(spanStrings, bytes, budget);
        if (spanCap > 0) {
            return new Fitted(state, bytes + truncate(spanStrings, spanCap), TRUNCATED_SPANS + spanCap);
        }

        bytes += delta(spanStrings, MIN_CAP);
        int traceCap = largestFittingCap(traceStrings, bytes, budget);
        if (traceCap > 0) {
            truncate(spanStrings, MIN_CAP);
            return new Fitted(state, bytes + truncate(traceStrings, traceCap), TRUNCATED + traceCap);
        }
        return new OverBudget(bytes + delta(traceStrings, MIN_CAP));
    }

    private long annotateTrace() {
        long bytes = 2;
        int entries = 0;
        for (Map.Entry<String, JsonNode> entry : state.properties()) {
            entries++;
            bytes += AiLabelJson.stringBytes(entry.getKey()) + 1;
            if (SPANS.equals(entry.getKey()) && entry.getValue() instanceof ArrayNode spans) {
                bytes += annotateSpans(spans, 1);
            } else {
                bytes += annotate(entry.getValue(), traceStrings, state, entry.getKey(), -1);
            }
        }
        return bytes + Math.max(entries - 1, 0);
    }

    private long annotateSpans(ArrayNode spans, int depth) {
        long bytes = 2 + Math.max(spans.size() - 1, 0);
        for (JsonNode span : spans) {
            bytes += annotateSpan((ObjectNode) span, depth);
        }
        return bytes;
    }

    private long annotateSpan(ObjectNode span, int depth) {
        long bytes = 2;
        int entries = 0;
        long ioBytes = 0;
        int ioEntries = 0;
        for (Map.Entry<String, JsonNode> entry : span.properties()) {
            entries++;
            String key = entry.getKey();
            long entryBytes = AiLabelJson.stringBytes(key) + 1;
            if (SPANS.equals(key) && entry.getValue() instanceof ArrayNode children) {
                entryBytes += annotateSpans(children, depth + 1);
            } else if (SPAN_IO.contains(key)) {
                entryBytes += annotate(entry.getValue(), null, span, key, -1);
                ioBytes += entryBytes;
                ioEntries++;
            } else {
                entryBytes += annotate(entry.getValue(), spanStrings, span, key, -1);
            }
            bytes += entryBytes;
        }
        if (ioEntries > 0) {
            // Dropping m of n entries drops m separators, or all n - 1 of them when none is left.
            addSpanIo(depth, span, ioBytes + Math.min(ioEntries, entries - 1));
        }
        return bytes + Math.max(entries - 1, 0);
    }

    private long annotate(JsonNode node, List<StringSlot> slots, ContainerNode<?> parent, String key, int index) {
        if (node.isTextual()) {
            String text = node.textValue();
            long bytes = AiLabelJson.stringBytes(text);
            // A code point takes at least a byte, so a string within the cap in bytes is within it in code points.
            if (slots != null && bytes - 2 > MIN_CAP) {
                int codePoints = text.codePointCount(0, text.length());
                if (codePoints > MIN_CAP) {
                    slots.add(new StringSlot(parent, key, index, text, codePoints, bytes));
                }
            }
            return bytes;
        }
        if (node instanceof ObjectNode object) {
            long bytes = 2 + Math.max(object.size() - 1, 0);
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                bytes += AiLabelJson.stringBytes(entry.getKey()) + 1
                        + annotate(entry.getValue(), slots, object, entry.getKey(), -1);
            }
            return bytes;
        }
        if (node instanceof ArrayNode array) {
            long bytes = 2 + Math.max(array.size() - 1, 0);
            for (int i = 0; i < array.size(); i++) {
                bytes += annotate(array.get(i), slots, array, null, i);
            }
            return bytes;
        }
        return AiLabelJson.scalarBytes(node);
    }

    private void addSpanIo(int depth, ObjectNode span, long bytes) {
        while (spanIoBytesByDepth.size() <= depth) {
            spanIoBytesByDepth.add(0L);
            spansWithIoByDepth.add(new ArrayList<>());
        }
        spanIoBytesByDepth.set(depth, spanIoBytesByDepth.get(depth) + bytes);
        spansWithIoByDepth.get(depth).add(span);
    }

    private void pruneSpanIo(int shallowestDepth) {
        for (int depth = shallowestDepth; depth < spansWithIoByDepth.size(); depth++) {
            spansWithIoByDepth.get(depth).forEach(span -> span.remove(SPAN_IO));
        }
    }

    /**
     * The largest cap in [{@link #MIN_CAP}, longest - 1] that brings {@code bytes} within budget, or 0 if none does.
     * The size only grows with the cap, so a binary search finds it. No cap above the budget can fit: a string cut at
     * L keeps L code points.
     */
    private static int largestFittingCap(List<StringSlot> slots, long bytes, long budget) {
        if (slots.isEmpty()) {
            return 0;
        }
        int longest = slots.stream().mapToInt(StringSlot::codePoints).max().orElseThrow();
        int high = (int) Math.min(longest - 1L, budget);
        // Readied at least to the floor, which the next step and the over-budget size are measured at.
        slots.forEach(slot -> slot.prepare(Math.max(high, MIN_CAP)));
        if (high < MIN_CAP || bytes + delta(slots, MIN_CAP) > budget) {
            return 0;
        }
        int low = MIN_CAP;
        int up = high;
        while (low < up) {
            int mid = (int) ((low + (long) up + 1) / 2);
            if (bytes + delta(slots, mid) <= budget) {
                low = mid;
            } else {
                up = mid - 1;
            }
        }
        return low;
    }

    private static long delta(List<StringSlot> slots, int cap) {
        long delta = 0;
        for (StringSlot slot : slots) {
            delta += slot.delta(cap);
        }
        return delta;
    }

    private static long truncate(List<StringSlot> slots, int cap) {
        long delta = 0;
        for (StringSlot slot : slots) {
            delta += slot.truncate(cap);
        }
        return delta;
    }

    private static int headCodePoints(int cap) {
        return (int) (cap * 7L / 10);
    }

    private static final class StringSlot {

        private final ContainerNode<?> parent;
        private final String key;
        private final int index;
        private final String value;
        private final int codePoints;
        private final long bytes;
        /** The serialized bytes of the first i code points, and of the last i. */
        private int[] headBytes;
        private int[] tailBytes;

        private StringSlot(ContainerNode<?> parent, String key, int index, String value, int codePoints,
                long bytes) {
            this.parent = parent;
            this.key = key;
            this.index = index;
            this.value = value;
            this.codePoints = codePoints;
            this.bytes = bytes;
        }

        int codePoints() {
            return codePoints;
        }

        /** Readies {@link #delta} for every cap up to {@code maxCap}. */
        void prepare(int maxCap) {
            int heads = Math.min(headCodePoints(maxCap), codePoints);
            int tails = Math.min(maxCap - headCodePoints(maxCap), codePoints);
            if (headBytes != null && headBytes.length > heads && tailBytes.length > tails) {
                return;
            }
            headBytes = new int[heads + 1];
            for (int i = 0, offset = 0; i < heads; i++) {
                int codePoint = value.codePointAt(offset);
                headBytes[i + 1] = headBytes[i] + AiLabelJson.codePointBytes(codePoint);
                offset += Character.charCount(codePoint);
            }
            tailBytes = new int[tails + 1];
            for (int i = 0, end = value.length(); i < tails; i++) {
                int codePoint = value.codePointBefore(end);
                tailBytes[i + 1] = tailBytes[i] + AiLabelJson.codePointBytes(codePoint);
                end -= Character.charCount(codePoint);
            }
        }

        /**
         * The change in bytes from cutting this string at {@code cap}. A cut that would not make it shorter, which
         * the marker can cause just above the cap, is skipped, so the size never grows as the cap shrinks.
         */
        long delta(int cap) {
            if (codePoints <= cap) {
                return 0;
            }
            int head = headCodePoints(cap);
            long cut = 2 + headBytes[head] + tailBytes[cap - head] + MARKER_BYTES
                    + String.valueOf(codePoints - cap).length();
            return Math.min(0, cut - bytes);
        }

        long truncate(int cap) {
            long delta = delta(cap);
            if (delta == 0) {
                return 0;
            }
            int head = headCodePoints(cap);
            var cut = TextNode.valueOf(value.substring(0, value.offsetByCodePoints(0, head))
                    + MARKER_OPEN + (codePoints - cap) + MARKER_CLOSE
                    + value.substring(value.offsetByCodePoints(value.length(), head - cap)));
            if (parent instanceof ObjectNode object) {
                object.set(key, cut);
            } else {
                ((ArrayNode) parent).set(index, cut);
            }
            return delta;
        }
    }
}
