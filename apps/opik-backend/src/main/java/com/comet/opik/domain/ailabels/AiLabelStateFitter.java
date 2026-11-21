package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Fits a canonical state, together with the trace's earlier turns when a task uses them, into one byte budget, in
 * steps that stop as soon as the two fit:
 * <ol>
 *   <li>{@code full}: nothing removed.</li>
 *   <li>{@code spans_pruned:<d>}: the spans at depth {@code d} and below are gone (top-level spans are depth 1), and
 *       {@code spans_summary} counts the whole tree; {@code spans_summarized} once every span is gone.</li>
 *   <li>{@code history_outputs_dropped:<n>}: the outputs of the {@code n} oldest earlier turns are gone.</li>
 *   <li>{@code history_turns:<n>}: only the {@code n} newest earlier turns are left.</li>
 *   <li>{@code output_vectors_elided}, then {@code input_vectors_elided}: every array of more than 16 numbers in the
 *       trace's output, then its input, is a {@code …[N numbers omitted]…} string. An embedding means nothing to a
 *       summary, so it goes before any text is cut, and stays gone in the steps below.</li>
 *   <li>{@code output_truncated:<L>}: every string in the output keeps its first L code points, followed by a
 *       {@code …[N chars truncated]…} marker; L is the largest that fits, never below 64.</li>
 *   <li>{@code input_truncated:<L>}: the output at 64, and the input's strings cut the same way.</li>
 *   <li>{@code output_arrays_capped:<N>}: strings at 64 aren't enough, so the bulk is in arrays. Both fields start
 *       over uncut, and every array in the output over N elements keeps its first 2 and its last N - 2 around a
 *       {@code …[K items truncated]…} element; N is the largest that fits, never below 3. The tail is kept because
 *       the newest entries of a list (a chat's last turns) carry the most intent.</li>
 *   <li>{@code output_arrays_capped_truncated:<L>}: the output's arrays at 3, and its strings cut at L as above.</li>
 *   <li>{@code input_arrays_capped:<N>}, then {@code input_arrays_capped_truncated:<L>}: the output at 3 and 64, and
 *       the input cut the same two ways.</li>
 *   <li>{@code output_as_text}, then {@code input_as_text}: the field, as left by the step before, becomes its
 *       canonical JSON as one string, cut in the middle to fit; the input's step leaves the output only the marker.
 *       </li>
 * </ol>
 * Without history, steps 2 and 3 are skipped. A step with nothing to remove from its field is passed through. A state
 * still over budget after the last step (its name alone too big) is {@link OverBudget}.
 *
 * <p>Every step is deterministic: the same state always fits to the same bytes. The size is the state's canonical
 * UTF-8 bytes plus the history's. It is computed rather than serialized, and the span levels are measured in one
 * pass, so a deep span chain costs one walk, not one per level. Caps count code points, so a cut never splits a
 * surrogate pair or a UTF-8 sequence. The state and history are changed in place.
 */
final class AiLabelStateFitter {

    static final int MIN_CAP = 64;
    static final int MIN_ITEMS = 3;
    static final int MAX_VECTOR_LENGTH = 16;
    static final String MARKER_OPEN = "…[";
    static final String MARKER_CLOSE = " chars truncated]…";
    static final String ITEMS_MARKER_CLOSE = " items truncated]…";
    static final String NUMBERS_MARKER_CLOSE = " numbers omitted]…";

    private static final String SPANS = "spans";
    private static final String SPANS_SUMMARY = "spans_summary";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    /** The fields each step cuts, in order: the output says less about intent than the input. */
    private static final List<String> FIELDS = List.of(OUTPUT, INPUT);
    /** An array cut to N elements keeps this many from its start, and the rest from its end. */
    private static final int HEAD_ITEMS = 2;
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

        long room = budget - historyBytes;
        for (var key : FIELDS) {
            stateBytes = replace(state, key, elideVectors(state.get(key)), stateBytes);
            if (stateBytes <= room) {
                return new Fitted(state, history, stateBytes + historyBytes, key + "_vectors_elided");
            }
        }

        var uncutOutput = state.get(OUTPUT);
        var uncutInput = state.get(INPUT);
        var output = truncate(state, OUTPUT, stateBytes, room);
        if (output.fits()) {
            return new Fitted(state, history, output.stateBytes() + historyBytes, "output_truncated:" + output.cap());
        }
        var input = truncate(state, INPUT, output.stateBytes(), room);
        if (input.fits()) {
            return new Fitted(state, history, input.stateBytes() + historyBytes, "input_truncated:" + input.cap());
        }

        // Strings at 64 don't fit, so the bulk is in arrays: cap them with the text whole again.
        stateBytes = replace(state, OUTPUT, uncutOutput, input.stateBytes());
        stateBytes = replace(state, INPUT, uncutInput, stateBytes);
        for (var key : FIELDS) {
            var original = state.get(key);
            var capped = search(state, key, stateBytes, room, MIN_ITEMS, longestArray(original) - 1,
                    items -> cut(original, items, Integer.MAX_VALUE));
            if (capped.fits()) {
                return new Fitted(state, history, capped.stateBytes() + historyBytes,
                        key + "_arrays_capped:" + capped.cap());
            }
            var both = search(state, key, capped.stateBytes(), room, MIN_CAP, longestString(original) - 1,
                    cap -> cut(original, MIN_ITEMS, cap));
            if (both.fits()) {
                return new Fitted(state, history, both.stateBytes() + historyBytes,
                        key + "_arrays_capped_truncated:" + both.cap());
            }
            stateBytes = both.stateBytes();
        }

        for (var key : FIELDS) {
            var text = asText(state, key, stateBytes, room);
            if (text.fits()) {
                return new Fitted(state, history, text.stateBytes() + historyBytes, key + "_as_text");
            }
            stateBytes = text.stateBytes();
        }
        return new OverBudget(stateBytes + historyBytes);
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

    /** The state's new size once {@code key} holds {@code value}; a field that isn't there stays absent. */
    private static long replace(ObjectNode state, String key, JsonNode value, long stateBytes) {
        var previous = state.get(key);
        if (previous == null) {
            return stateBytes;
        }
        state.set(key, value);
        return stateBytes - AiLabelJson.sizeOf(previous) + AiLabelJson.sizeOf(value);
    }

    private record Cut(boolean fits, int cap, long stateBytes) {
    }

    /** Cuts every string under {@code key} at the largest cap that fits {@code budget}, or at {@link #MIN_CAP}. */
    private static Cut truncate(ObjectNode state, String key, long stateBytes, long budget) {
        var original = state.get(key);
        return search(state, key, stateBytes, budget, MIN_CAP, longestString(original) - 1,
                cap -> cut(original, Integer.MAX_VALUE, cap));
    }

    /**
     * Sets {@code key} to {@code shrink} of the largest parameter in {@code [low, high]} that fits {@code budget}, or
     * of {@code low} when none does. The size grows with the parameter, so a binary search finds it; where it only
     * nearly does (nested arrays, the cut JSON text), the search may stop short of the largest, but what it keeps fits.
     */
    private static Cut search(ObjectNode state, String key, long stateBytes, long budget, int low, int high,
            IntFunction<JsonNode> shrink) {
        var original = state.get(key);
        if (original == null) {
            return new Cut(false, low, stateBytes);
        }
        long others = stateBytes - AiLabelJson.sizeOf(original);
        var best = shrink.apply(low);
        long bestBytes = others + AiLabelJson.sizeOf(best);
        state.set(key, best);
        if (bestBytes > budget) {
            return new Cut(false, low, bestBytes);
        }
        while (low < high) {
            int mid = (int) ((low + (long) high + 1) / 2);
            var candidate = shrink.apply(mid);
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

    /**
     * The last resort: {@code key} as one string, its canonical JSON cut in the middle like an outsized history field.
     * Unlike a skeleton of keys, it fits any budget past the marker's, and it keeps real values from the start and the
     * end rather than only names, which a wide object of short values (the one shape the steps before can't shrink)
     * would still overflow.
     */
    private static Cut asText(ObjectNode state, String key, long stateBytes, long budget) {
        var original = state.get(key);
        if (original == null) {
            return new Cut(false, 0, stateBytes);
        }
        String json = AiLabelJson.toCanonicalString(original);
        return search(state, key, stateBytes, budget, 0, json.getBytes(StandardCharsets.UTF_8).length,
                maxBytes -> AiLabelJson.NODE_FACTORY.textNode(AiLabelRenderer.capMiddle(json, maxBytes)));
    }

    private static int longestString(JsonNode node) {
        if (node == null) {
            return 0;
        }
        if (node.isTextual()) {
            return node.textValue().codePointCount(0, node.textValue().length());
        }
        int longest = 0;
        for (JsonNode child : node) {
            longest = Math.max(longest, longestString(child));
        }
        return longest;
    }

    private static int longestArray(JsonNode node) {
        if (node == null) {
            return 0;
        }
        int longest = node.isArray() ? node.size() : 0;
        for (JsonNode child : node) {
            longest = Math.max(longest, longestArray(child));
        }
        return longest;
    }

    /** A copy of {@code node} with every array of more than {@link #MAX_VECTOR_LENGTH} numbers replaced by a marker. */
    private static JsonNode elideVectors(JsonNode node) {
        if (node instanceof ArrayNode array) {
            if (array.size() > MAX_VECTOR_LENGTH && isNumeric(array)) {
                return TextNode.valueOf(MARKER_OPEN + array.size() + NUMBERS_MARKER_CLOSE);
            }
            var copy = AiLabelJson.NODE_FACTORY.arrayNode();
            array.forEach(child -> copy.add(elideVectors(child)));
            return copy;
        }
        if (node instanceof ObjectNode object) {
            var copy = AiLabelJson.NODE_FACTORY.objectNode();
            object.properties().forEach(entry -> copy.set(entry.getKey(), elideVectors(entry.getValue())));
            return copy;
        }
        return node;
    }

    private static boolean isNumeric(ArrayNode array) {
        for (var element : array) {
            if (!element.isNumber()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A copy of {@code node} with every array over {@code maxItems} elements cut to its first {@link #HEAD_ITEMS} and
     * last {@code maxItems - HEAD_ITEMS}, around a marker, and every string over {@code cap} code points cut to its
     * first {@code cap}. A cut that wouldn't make a value shorter, which the marker can cause, is skipped, so the size
     * never grows as either limit shrinks.
     */
    private static JsonNode cut(JsonNode node, int maxItems, int cap) {
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
            object.properties().forEach(entry -> copy.set(entry.getKey(), cut(entry.getValue(), maxItems, cap)));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            var copy = AiLabelJson.NODE_FACTORY.arrayNode();
            int tailStart = array.size() - (maxItems - HEAD_ITEMS);
            if (array.size() > maxItems) {
                var marker = TextNode.valueOf(MARKER_OPEN + (array.size() - maxItems) + ITEMS_MARKER_CLOSE);
                if (droppedBytesExceed(array, tailStart, maxItems, cap, AiLabelJson.sizeOf(marker) + 1)) {
                    for (int i = 0; i < HEAD_ITEMS; i++) {
                        copy.add(cut(array.get(i), maxItems, cap));
                    }
                    copy.add(marker);
                    for (int i = tailStart; i < array.size(); i++) {
                        copy.add(cut(array.get(i), maxItems, cap));
                    }
                    return copy;
                }
            }
            array.forEach(child -> copy.add(cut(child, maxItems, cap)));
            return copy;
        }
        return node;
    }

    /** Whether the elements a cut drops, as cut themselves and with their commas, take more than {@code bytes}. */
    private static boolean droppedBytesExceed(ArrayNode array, int tailStart, int maxItems, int cap, long bytes) {
        long dropped = 0;
        for (int i = HEAD_ITEMS; i < tailStart && dropped <= bytes; i++) {
            dropped += AiLabelJson.sizeOf(cut(array.get(i), maxItems, cap)) + 1;
        }
        return dropped > bytes;
    }
}
