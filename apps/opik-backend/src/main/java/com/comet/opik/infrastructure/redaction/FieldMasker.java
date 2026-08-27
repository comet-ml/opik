package com.comet.opik.infrastructure.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;

import java.util.Map;
import java.util.Set;

/**
 * Replaces the value of every leaf whose field name is configured as sensitive, at any depth.
 * <p>
 * Matching is by name rather than by path because the same field appears at different depths depending on which
 * integration wrote the trace: a chat payload carries {@code messages[].content}, LangChain nests it under
 * {@code messages[].kwargs.message.content}, and a tool call puts it in {@code function.arguments}. A path-based
 * config would have to enumerate every framework's shape and would silently miss the next one.
 * <p>
 * Only values are replaced; field names are never touched, so an object keyed by dates or identifiers keeps its
 * keys and cannot collapse into duplicates. A caller who happens to name a metadata key {@code content} gets that
 * value masked — over-masking, which is the safe direction for a control whose purpose is withholding content.
 * <p>
 * <b>Why the whole value, and not just the sensitive part of it.</b> Masking a substring requires knowing which
 * substring, and a field name does not say: it marks the field as carrying content, not which characters within it
 * are sensitive. Partial masking therefore needs detection, and detection has to happen somewhere:
 * <ul>
 * <li><i>at read</i>, by matching patterns against every value — which is what this replaced. Measured at roughly
 * 119 ms of CPU per MB of response, around 45x the cost of the read it wraps, with an unbounded worst case on
 * caller-supplied input;</li>
 * <li><i>at write</i>, by detecting once and storing where the matches were, then splicing at read. Measured far
 * cheaper than either — a span index costs about 6% of the column compressed and the read-time splice runs at
 * memcpy speed, some 600x faster than matching. It buys partial masking back, at the price of a write-time pass
 * and a backfill whenever the rules change;</li>
 * <li><i>not at all</i>, which is here. No detection, so no partial masking, and nothing to backfill.</li>
 * </ul>
 * The last is chosen because it is free and cannot be defeated by the input. If partial masking is ever required,
 * write-time detection is the route — not a return to matching on read.
 */
public record FieldMasker(@NonNull Set<String> maskedFields, @NonNull String replacement) {

    private static final FieldMasker NO_OP = new FieldMasker(Set.of(), "");

    /** Used wherever the caller may see stored content, so the read path is a straight pass-through. */
    public static FieldMasker noOp() {
        return NO_OP;
    }

    public boolean isNoOp() {
        return maskedFields.isEmpty();
    }

    /**
     * Masks in place and returns the same node.
     * <p>
     * Safe because every node reaching this is freshly parsed from the column value of a single row, so nothing
     * else holds a reference. Callers that do not own their node must copy before calling.
     */
    public JsonNode mask(JsonNode node) {
        if (node == null || isNoOp()) {
            return node;
        }

        return rewrite(node);
    }

    /**
     * Masks a value that is already associated with a name, for maps whose keys are field names chosen by the
     * caller — a dataset item's columns, for instance. Equivalent to how the same name would be treated as an
     * object field, so a dataset column called {@code content} masks like a {@code content} field does.
     */
    public JsonNode maskNamed(String fieldName, JsonNode value) {
        if (value == null || isNoOp()) {
            return value;
        }

        return maskedFields.contains(fieldName) ? maskWholeValue(value) : rewrite(value);
    }

    /**
     * Masks every string in the tree, whatever its field name.
     * <p>
     * For results the caller shaped themselves. Agent Insights free-form SQL returns one {@code result} column
     * built by the caller's own {@code toJSONString(...)}, so they choose the key names as well as the
     * projection: name-matching keys off a name they pick, and {@code map('x', input)} walks straight past a
     * configured set. Masking on shape rather than on name removes that, since no projection of content can
     * avoid arriving as a string.
     * <p>
     * Numbers and booleans survive, which is the point of masking rather than refusing: a caller without the
     * permission keeps counts, averages and grouping over non-content dimensions, and loses only the content
     * they were never allowed to read.
     */
    public JsonNode maskEveryString(JsonNode node) {
        if (node == null || isNoOp()) {
            return node;
        }

        return maskWholeValue(node);
    }

    private JsonNode rewrite(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // Names are collected first: replacing a value while iterating the live view would fail.
            for (String name : object.propertyStream().map(Map.Entry::getKey).toList()) {
                if (maskedFields.contains(name)) {
                    object.set(name, maskWholeValue(object.get(name)));
                } else {
                    object.set(name, rewrite(object.get(name)));
                }
            }
            return object;
        }

        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, rewrite(array.get(i)));
            }
            return array;
        }

        return node;
    }

    /**
     * A configured name masks whatever is under it. A scalar becomes the replacement; a nested object or array has
     * every string beneath it replaced, so structure survives for callers that navigate it while no content leaks
     * through a shape the config did not anticipate.
     */
    private JsonNode maskWholeValue(JsonNode node) {
        if (node.isTextual()) {
            return TextNode.valueOf(replacement);
        }

        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String name : object.propertyStream().map(Map.Entry::getKey).toList()) {
                object.set(name, maskWholeValue(object.get(name)));
            }
            return object;
        }

        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, maskWholeValue(array.get(i)));
            }
            return array;
        }

        // Numbers, booleans and nulls carry no free text and are left as stored.
        return node;
    }
}
