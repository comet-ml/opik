package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Fits a canonical state, together with the trace's earlier turns when a task uses them, into one byte budget, in
 * steps that stop as soon as the two fit:
 * <ol>
 *   <li>{@code full}: nothing removed.</li>
 *   <li>{@code arrays_capped:<N>}: every array in the trace's input and output over N elements keeps its first 2 and
 *       its last N - 2 around a {@code …[K items truncated]…} element; N is the largest that fits, never below 3. Long
 *       lists are the cheapest content to lose (embeddings are already gone, see {@link AiLabelRenderer}), and the
 *       tail is kept because the newest entries of a list (a chat's last turns) carry the most intent. Only when that
 *       alone fits: otherwise the bulk is text, and the arrays are left whole until it's cut.</li>
 *   <li>{@code spans_pruned:<d>}: the spans at depth {@code d} and below are gone (top-level spans are depth 1), and
 *       {@code spans_summary} counts the whole tree; {@code spans_summarized} once every span is gone.</li>
 *   <li>{@code history_outputs_dropped:<n>}: the outputs of the {@code n} oldest earlier turns are gone.</li>
 *   <li>{@code history_turns:<n>}: only the {@code n} newest earlier turns are left.</li>
 *   <li>{@code output_truncated:<L>}: every string in the output keeps its first L code points, followed by a
 *       {@code …[N chars truncated]…} marker; L is the largest that fits, never below 64.</li>
 *   <li>{@code input_truncated:<L>}: the output at 64, and the input's strings cut the same way.</li>
 *   <li>{@code truncated_arrays_capped:<N>}: both at 64, and the arrays capped as in step 2.</li>
 *   <li>{@code output_as_text} or {@code input_as_text}: the larger of the two fields becomes its canonical JSON as one
 *       string, cut in the middle to fit; {@code fields_as_text} when the smaller one must become text too.</li>
 * </ol>
 * Without history, steps 4 and 5 are skipped. A step with nothing to remove is passed through. A state still over
 * budget after the last step (its name alone too big) is {@link OverBudget}.
 *
 * <p>Every step is deterministic: the same state always fits to the same bytes. The size is the state's canonical
 * UTF-8 bytes plus the history's. It is computed rather than serialized, and the span levels are measured in one
 * pass, so a deep span chain costs one walk, not one per level. Caps count code points, so a cut never splits a
 * surrogate pair or a UTF-8 sequence. Arrays inside spans aren't capped: spans are pruned whole instead. The state and
 * history are changed in place.
 */
final class AiLabelStateFitter {

    static final int MIN_CAP = 64;
    static final int MIN_ITEMS = 3;
    static final String MARKER_OPEN = "…[";
    static final String MARKER_CLOSE = " chars truncated]…";
    static final String ITEMS_MARKER_CLOSE = " items truncated]…";

    private static final String SPANS = "spans";
    private static final String SPANS_SUMMARY = "spans_summary";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
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

        var uncapped = new LinkedHashMap<String, JsonNode>();
        FIELDS.stream().filter(state::has).forEach(key -> uncapped.put(key, state.get(key)));
        var capped = capArrays(state, stateBytes, budget - historyBytes);
        if (capped.fits()) {
            return new Fitted(state, history, capped.stateBytes() + historyBytes, "arrays_capped:" + capped.cap());
        }
        // Arrays alone aren't the bulk, so the text goes first, and they're capped only once it's cut.
        state.setAll(uncapped);

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
        var output = truncate(state, OUTPUT, stateBytes, room);
        if (output.fits()) {
            return new Fitted(state, history, output.stateBytes() + historyBytes, "output_truncated:" + output.cap());
        }
        var input = truncate(state, INPUT, output.stateBytes(), room);
        if (input.fits()) {
            return new Fitted(state, history, input.stateBytes() + historyBytes, "input_truncated:" + input.cap());
        }
        var both = capArrays(state, input.stateBytes(), room);
        if (both.fits()) {
            return new Fitted(state, history, both.stateBytes() + historyBytes,
                    "truncated_arrays_capped:" + both.cap());
        }

        // The larger field goes first, so a short answer isn't lost to a huge input, or the other way round.
        stateBytes = both.stateBytes();
        var bySize = sizeOf(state.get(INPUT)) > sizeOf(state.get(OUTPUT)) ? List.of(INPUT, OUTPUT) : FIELDS;
        var larger = asText(state, bySize.getFirst(), stateBytes, room);
        if (larger.fits()) {
            return new Fitted(state, history, larger.stateBytes() + historyBytes, bySize.getFirst() + "_as_text");
        }
        var smaller = asText(state, bySize.getLast(), larger.stateBytes(), room);
        if (smaller.fits()) {
            return new Fitted(state, history, smaller.stateBytes() + historyBytes, "fields_as_text");
        }
        return new OverBudget(smaller.stateBytes() + historyBytes);
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

    /** Caps every array in the input and output at the largest element count that fits, or at {@link #MIN_ITEMS}. */
    private static Cut capArrays(ObjectNode state, long stateBytes, long budget) {
        int longest = FIELDS.stream().mapToInt(key -> longestArray(state.get(key))).max().orElse(0);
        return search(state, FIELDS, stateBytes, budget, MIN_ITEMS, longest - 1,
                (field, items) -> cut(field, items, Integer.MAX_VALUE));
    }

    /** Cuts every string under {@code key} at the largest cap that fits {@code budget}, or at {@link #MIN_CAP}. */
    private static Cut truncate(ObjectNode state, String key, long stateBytes, long budget) {
        return search(state, List.of(key), stateBytes, budget, MIN_CAP, longestString(state.get(key)) - 1,
                (field, cap) -> cut(field, Integer.MAX_VALUE, cap));
    }

    /**
     * Sets each of {@code keys} to {@code shrink} of it at the largest parameter in {@code [low, high]} that fits
     * {@code budget}, or at {@code low} when none does. The size grows with the parameter, so a binary search finds it;
     * where it only nearly does (nested arrays, the cut JSON text), the search may stop short of the largest, but what
     * it keeps fits. A key the state doesn't have stays absent.
     */
    private static Cut search(ObjectNode state, List<String> keys, long stateBytes, long budget, int low, int high,
            BiFunction<JsonNode, Integer, JsonNode> shrink) {
        var originals = new LinkedHashMap<String, JsonNode>();
        keys.stream().filter(state::has).forEach(key -> originals.put(key, state.get(key)));
        if (originals.isEmpty()) {
            return new Cut(false, low, stateBytes);
        }
        long others = stateBytes - originals.values().stream().mapToLong(AiLabelJson::sizeOf).sum();
        IntFunction<Map<String, JsonNode>> shrinkAll = param -> originals.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> shrink.apply(entry.getValue(), param)));
        var best = shrinkAll.apply(low);
        long bestBytes = others + best.values().stream().mapToLong(AiLabelJson::sizeOf).sum();
        state.setAll(best);
        if (bestBytes > budget) {
            return new Cut(false, low, bestBytes);
        }
        while (low < high) {
            int mid = (int) ((low + (long) high + 1) / 2);
            var candidate = shrinkAll.apply(mid);
            long bytes = others + candidate.values().stream().mapToLong(AiLabelJson::sizeOf).sum();
            if (bytes <= budget) {
                low = mid;
                best = candidate;
                bestBytes = bytes;
            } else {
                high = mid - 1;
            }
        }
        state.setAll(best);
        return new Cut(true, low, bestBytes);
    }

    /**
     * The last resort: {@code key} as one string, its canonical JSON cut in the middle like an outsized history field.
     * Unlike a skeleton of keys, it fits any budget past the marker's, and it keeps real values from the start and the
     * end rather than only names, which a wide object of short values (the one shape the steps before can't shrink)
     * would still overflow.
     */
    private static Cut asText(ObjectNode state, String key, long stateBytes, long budget) {
        var field = state.get(key);
        if (field == null) {
            return new Cut(false, 0, stateBytes);
        }
        String json = AiLabelJson.toCanonicalString(field);
        // A UTF-16 char is at most 3 UTF-8 bytes, so this bounds the text's size without encoding it.
        return search(state, List.of(key), stateBytes, budget, 0, json.length() * 3,
                (ignored, maxBytes) -> AiLabelJson.NODE_FACTORY.textNode(AiLabelRenderer.capMiddle(json, maxBytes)));
    }

    private static long sizeOf(JsonNode field) {
        return field == null ? 0 : AiLabelJson.sizeOf(field);
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
                // Only if the dropped elements, as cut themselves and with their commas, take more than the marker.
                long markerBytes = AiLabelJson.sizeOf(marker) + 1;
                long dropped = 0;
                for (int i = HEAD_ITEMS; i < tailStart && dropped <= markerBytes; i++) {
                    dropped += AiLabelJson.sizeOf(cut(array.get(i), maxItems, cap)) + 1;
                }
                if (dropped > markerBytes) {
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
}
