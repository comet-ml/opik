package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a canonical state, together with the trace's earlier turns when a task uses them, into one byte budget, in
 * steps that stop as soon as the two fit:
 * <ol>
 *   <li>{@code full}: nothing removed.</li>
 *   <li>{@code spans_pruned:<d>}: the spans at depth {@code d} and below are gone (top-level spans are depth 1), and
 *       {@code spans_summary} counts the whole tree; {@code spans_summarized} once every span is gone.</li>
 *   <li>{@code history_outputs_dropped:<n>}: the outputs of the {@code n} oldest earlier turns are gone.</li>
 *   <li>{@code history_turns:<n>}: only the {@code n} newest earlier turns are left.</li>
 *   <li>{@code output_truncated:<L>}: every string in the trace's output keeps its first L code points, followed by a
 *       {@code …[N chars truncated]…} marker; L is the largest that fits, never below 64.</li>
 *   <li>{@code input_truncated:<L>}: the output at 64, and the input's strings cut the same way.</li>
 * </ol>
 * Without history, steps 2 and 3 are skipped. A state still over budget with both at 64 is {@link OverBudget}.
 *
 * <p>The size is the state's canonical UTF-8 bytes plus the history's. It is computed rather than serialized, and
 * the span levels are measured in one pass, so a deep span chain costs one walk, not one per level. Caps count code
 * points, so a cut never splits a surrogate pair or a UTF-8 sequence. The state and history are changed in place.
 */
final class AiLabelStateFitter {

    static final int MIN_CAP = 64;
    static final String MARKER_OPEN = "…[";
    static final String MARKER_CLOSE = " chars truncated]…";

    private static final String SPANS = "spans";
    private static final String SPANS_SUMMARY = "spans_summary";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    /** {@code "spans":[]}: the key, its colon and the brackets. */
    private static final long SPANS_FRAMING = AiLabelJson.stringBytes(SPANS) + 3;

    sealed interface Fit permits Fitted, OverBudget {
    }

    /** {@code history} is null when none was asked for. */
    record Fitted(@NonNull ObjectNode state, ArrayNode history, long bytes, @NonNull String compactionLevel)
            implements
                Fit {
    }

    record OverBudget(long bytes) implements Fit {
    }

    private AiLabelStateFitter() {
    }

    /** {@code history} is oldest first, or null for a task that doesn't use it. */
    static Fit fit(@NonNull ObjectNode state, ArrayNode history, @NonNull ObjectNode spansSummary, long budget) {
        long historyBytes = history == null ? 0 : AiLabelJson.sizeOf(history);
        long stateBytes = AiLabelJson.sizeOf(state);
        if (stateBytes + historyBytes <= budget) {
            return new Fitted(state, history, stateBytes + historyBytes, "full");
        }

        if (state.has(SPANS)) {
            state.set(SPANS_SUMMARY, spansSummary);
            var levels = new ArrayList<long[]>();
            measureLevels(state, 0, levels);
            for (int depth = levels.size() - 1; depth >= 1; depth--) {
                stateBytes = bytesKeepingAbove(levels, depth);
                if (stateBytes + historyBytes <= budget) {
                    removeSpansFrom(state, 0, depth);
                    return new Fitted(state, history, stateBytes + historyBytes,
                            depth == 1 ? "spans_summarized" : "spans_pruned:" + depth);
                }
            }
            removeSpansFrom(state, 0, 1);
            stateBytes = bytesKeepingAbove(levels, 1);
        }

        if (history != null) {
            int dropped = 0;
            for (var turn : history) {
                if (((ObjectNode) turn).remove(OUTPUT) == null) {
                    continue;
                }
                dropped++;
                historyBytes = AiLabelJson.sizeOf(history);
                if (stateBytes + historyBytes <= budget) {
                    return new Fitted(state, history, stateBytes + historyBytes,
                            "history_outputs_dropped:" + dropped);
                }
            }
            while (!history.isEmpty()) {
                history.remove(0);
                historyBytes = AiLabelJson.sizeOf(history);
                if (stateBytes + historyBytes <= budget) {
                    return new Fitted(state, history, stateBytes + historyBytes, "history_turns:" + history.size());
                }
            }
        }

        var output = truncate(state, OUTPUT, stateBytes, budget - historyBytes);
        if (output.fits()) {
            return new Fitted(state, history, output.stateBytes() + historyBytes, "output_truncated:" + output.cap());
        }
        var input = truncate(state, INPUT, output.stateBytes(), budget - historyBytes);
        if (input.fits()) {
            return new Fitted(state, history, input.stateBytes() + historyBytes, "input_truncated:" + input.cap());
        }
        return new OverBudget(input.stateBytes() + historyBytes);
    }

    /**
     * Index k: the bytes of the nodes at depth k without their {@code spans}, and the bytes their {@code spans}
     * arrays add around the children (key, brackets and commas).
     */
    private static void measureLevels(ObjectNode node, int depth, List<long[]> levels) {
        if (levels.size() == depth) {
            levels.add(new long[2]);
        }
        var spans = node.get(SPANS);
        int otherKeys = node.size() - (spans == null ? 0 : 1);
        long own = 2 + Math.max(0, otherKeys - 1);
        for (var entry : node.properties()) {
            if (!SPANS.equals(entry.getKey())) {
                own += AiLabelJson.stringBytes(entry.getKey()) + 1 + AiLabelJson.sizeOf(entry.getValue());
            }
        }
        levels.get(depth)[0] += own;
        if (spans instanceof ArrayNode children) {
            levels.get(depth)[1] += SPANS_FRAMING + Math.max(0, children.size() - 1) + (otherKeys > 0 ? 1 : 0);
            children.forEach(child -> measureLevels((ObjectNode) child, depth + 1, levels));
        }
    }

    /** The state's size with only the levels above {@code depth} left. */
    private static long bytesKeepingAbove(List<long[]> levels, int depth) {
        long bytes = 0;
        for (int level = 0; level < depth; level++) {
            bytes += levels.get(level)[0];
            if (level < depth - 1) {
                bytes += levels.get(level)[1];
            }
        }
        return bytes;
    }

    private static void removeSpansFrom(ObjectNode node, int nodeDepth, int depth) {
        if (nodeDepth + 1 >= depth) {
            node.remove(SPANS);
            return;
        }
        if (node.get(SPANS) instanceof ArrayNode spans) {
            spans.forEach(span -> removeSpansFrom((ObjectNode) span, nodeDepth + 1, depth));
        }
    }

    private record Cut(boolean fits, int cap, long stateBytes) {
    }

    /**
     * Cuts every string under {@code key} at the largest cap that fits {@code budget}, or at {@link #MIN_CAP} when
     * none does. The size only grows with the cap, so a binary search finds it.
     */
    private static Cut truncate(ObjectNode state, String key, long stateBytes, long budget) {
        var original = state.get(key);
        if (original == null) {
            return new Cut(false, MIN_CAP, stateBytes);
        }
        long others = stateBytes - AiLabelJson.sizeOf(original);
        var best = cut(original, MIN_CAP);
        long bestBytes = others + AiLabelJson.sizeOf(best);
        state.set(key, best);
        if (bestBytes > budget) {
            return new Cut(false, MIN_CAP, bestBytes);
        }
        int low = MIN_CAP;
        int high = longestString(original) - 1;
        while (low < high) {
            int mid = (int) ((low + (long) high + 1) / 2);
            var candidate = cut(original, mid);
            long bytes = others + AiLabelJson.sizeOf(candidate);
            if (bytes <= budget) {
                low = mid;
                best = candidate;
                bestBytes = bytes;
            } else {
                high = mid - 1;
            }
        }
        state.set(key, best);
        return new Cut(true, low, bestBytes);
    }

    private static int longestString(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue().codePointCount(0, node.textValue().length());
        }
        int longest = 0;
        for (JsonNode child : node) {
            longest = Math.max(longest, longestString(child));
        }
        return longest;
    }

    /**
     * A copy of {@code node} with every string over {@code cap} code points cut to its first {@code cap}. A cut that
     * wouldn't make a string shorter, which the marker can cause just under its length, is skipped, so the size never
     * grows as the cap shrinks.
     */
    private static JsonNode cut(JsonNode node, int cap) {
        if (node.isTextual()) {
            String text = node.textValue();
            int codePoints = text.codePointCount(0, text.length());
            if (codePoints <= cap) {
                return node;
            }
            String cut = text.substring(0, text.offsetByCodePoints(0, cap))
                    + MARKER_OPEN + (codePoints - cap) + MARKER_CLOSE;
            return AiLabelJson.stringBytes(cut) < AiLabelJson.stringBytes(text) ? TextNode.valueOf(cut) : node;
        }
        if (node instanceof ObjectNode object) {
            var copy = AiLabelJson.NODE_FACTORY.objectNode();
            object.properties().forEach(entry -> copy.set(entry.getKey(), cut(entry.getValue(), cap)));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            var copy = AiLabelJson.NODE_FACTORY.arrayNode();
            array.forEach(child -> copy.add(cut(child, cap)));
            return copy;
        }
        return node;
    }
}
