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
 * replaces over-length string values with a truncation marker that points the
 * agent at the exact jq expression to recover the full content.
 *
 * <p>Output suffix format ("compressed entity output" variant from design §3.5):
 * <pre>
 *   &lt;first maxLength chars&gt;[TRUNCATED N chars — use jq('&lt;jqPath&gt;') to see full]
 * </pre>
 *
 * <p>jq path conventions:
 * <ul>
 *   <li>Top-level field {@code foo} → {@code .foo}</li>
 *   <li>Identifier-safe nested fields → {@code .foo.bar}</li>
 *   <li>Non-identifier keys → {@code .["key with spaces"]}</li>
 *   <li>Array indices → {@code [N]}</li>
 * </ul>
 */
@UtilityClass
final class PathAwareTruncator {

    private static final String ROOT_PATH = ".";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /**
     * Returns a deep copy of {@code node} with strings longer than
     * {@code maxLength} replaced by truncation markers. The input tree is not
     * mutated — callers can safely keep using the original.
     */
    static JsonNode truncate(@NonNull JsonNode node, int maxLength) {
        JsonNode copy = node.deepCopy();
        truncateInPlace(copy, maxLength, ROOT_PATH);
        return copy;
    }

    private static void truncateInPlace(JsonNode node, int maxLength, String jqPath) {
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
                        obj.set(key, new TextNode(truncatedString(text, maxLength, childPath)));
                    }
                } else if (child.isContainerNode()) {
                    truncateInPlace(child, maxLength, childPath);
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
                        arr.set(i, new TextNode(truncatedString(text, maxLength, childPath)));
                    }
                } else if (child.isContainerNode()) {
                    truncateInPlace(child, maxLength, childPath);
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

    private static String truncatedString(String original, int maxLength, String jqPath) {
        int dropped = original.length() - maxLength;
        return original.substring(0, maxLength)
                + "[TRUNCATED %,d chars — use jq('%s') to see full]".formatted(dropped, jqPath);
    }
}