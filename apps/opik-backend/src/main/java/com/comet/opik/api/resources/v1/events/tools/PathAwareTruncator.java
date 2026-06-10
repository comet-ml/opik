package com.comet.opik.api.resources.v1.events.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * Walks a {@link JsonNode} tree tracking the jq path of every visited node and
 * replaces over-length string values with a truncation marker.
 *
 * <p>Two suffix styles are supported (per design §3.5):
 * <ul>
 *   <li>{@link SuffixStyle#WITH_JQ_HINT} — the default. Embeds the jq path so
 *       the agent can recover the full value:
 *       <pre>&lt;first maxLength chars&gt;[TRUNCATED N chars — use jq('&lt;jqPath&gt;') to see full]</pre>
 *       Use this when the truncated tree lives in a cache that still holds
 *       the full original under the same jq path.</li>
 *   <li>{@link SuffixStyle#BARE} — drops the jq promise. Use when the source of
 *       truth itself is being truncated (e.g. the 10 MB cache cap fallback) or
 *       when the agent is going to read this snapshot through a cache that no
 *       longer has the full value: pointing at jq would be a lie.
 *       <pre>&lt;first maxLength chars&gt;[TRUNCATED N chars]</pre></li>
 * </ul>
 *
 * <p>jq path conventions (used only by {@code WITH_JQ_HINT}):
 * <ul>
 *   <li>Top-level field {@code foo} → {@code .foo}</li>
 *   <li>Identifier-safe nested fields → {@code .foo.bar}</li>
 *   <li>Non-identifier keys → {@code .["key with spaces"]}</li>
 *   <li>Array indices → {@code [N]}</li>
 * </ul>
 */
@UtilityClass
final class PathAwareTruncator {

    /** Style of the truncation suffix appended to over-length strings. */
    enum SuffixStyle {
        /** Includes a {@code — use jq('<path>') to see full} pointer. */
        WITH_JQ_HINT,
        /** Bare {@code [TRUNCATED N chars]} — no jq pointer. */
        BARE
    }

    private static final String ROOT_PATH = ".";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /**
     * Convenience overload — returns a deep copy of {@code node} with strings
     * longer than {@code maxLength} replaced by {@link SuffixStyle#WITH_JQ_HINT}
     * markers. Use when the truncated output is presented alongside a
     * cache that still holds the full values.
     */
    static JsonNode truncate(@NonNull JsonNode node, int maxLength) {
        return truncate(node, maxLength, SuffixStyle.WITH_JQ_HINT);
    }

    /**
     * Returns a deep copy of {@code node} with strings longer than
     * {@code maxLength} replaced by truncation markers in the requested
     * {@link SuffixStyle}. The input tree is not mutated.
     */
    static JsonNode truncate(@NonNull JsonNode node, int maxLength, @NonNull SuffixStyle suffix) {
        JsonNode copy = node.deepCopy();
        truncateInPlace(copy, maxLength, ROOT_PATH, suffix);
        return copy;
    }

    private static void truncateInPlace(JsonNode node, int maxLength, String jqPath, SuffixStyle suffix) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // Collect keys first to avoid concurrent-modification issues if a child becomes a fresh node.
            for (var fieldNames = obj.fieldNames(); fieldNames.hasNext();) {
                String key = fieldNames.next();
                JsonNode child = obj.get(key);
                String childPath = appendField(jqPath, key);
                if (child.isTextual()) {
                    String text = child.asText();
                    if (text.length() > maxLength) {
                        obj.set(key,
                                new TextNode(StringTruncator.truncate(text, maxLength, hintFor(childPath, suffix))));
                    }
                } else if (child.isContainerNode()) {
                    truncateInPlace(child, maxLength, childPath, suffix);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                String childPath = jqPath + "[" + i + "]";
                if (child.isTextual()) {
                    String text = child.asText();
                    if (text.length() > maxLength) {
                        arr.set(i, new TextNode(StringTruncator.truncate(text, maxLength, hintFor(childPath, suffix))));
                    }
                } else if (child.isContainerNode()) {
                    truncateInPlace(child, maxLength, childPath, suffix);
                }
            }
        }
    }

    private static String appendField(String parentPath, String key) {
        if (IDENTIFIER.matcher(key).matches()) {
            return ROOT_PATH.equals(parentPath) ? "." + key : parentPath + "." + key;
        }
        // Non-identifier keys: jq bracket form .["key"] with embedded quotes escaped.
        String escaped = key.replace("\\", "\\\\").replace("\"", "\\\"");
        String bracket = ".[\"" + escaped + "\"]";
        return ROOT_PATH.equals(parentPath) ? bracket : parentPath + bracket;
    }

    private static String hintFor(String jqPath, SuffixStyle suffix) {
        return switch (suffix) {
            case WITH_JQ_HINT -> "use jq('%s') to see full".formatted(jqPath);
            case BARE -> null;
        };
    }
}
