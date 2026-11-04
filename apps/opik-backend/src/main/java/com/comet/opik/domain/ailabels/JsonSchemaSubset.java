package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A port of opik-inference's {@code core/schema_subset.py} {@code validate_schema}: the JSON Schema subset its
 * constrained decoder enforces. The service rejects a whole job ({@code invalid_schema}) for one unsupported keyword,
 * so a generate task's schema is checked here when it loads.
 * <p>
 * Differences from the Python original, all on the strict side: the metaschema check covers only the shape of the
 * allowed keywords, {@code pattern} is compiled as a Java regex, and nesting is capped at {@link #MAX_DEPTH} levels
 * instead of Python's recursion limit.
 */
@UtilityClass
public class JsonSchemaSubset {

    public static final Set<String> ALLOWED_KEYWORDS = Set.of(
            "$schema", "$ref", "$defs", "definitions", "title", "description", "default", "type", "enum", "const",
            "anyOf", "oneOf", "properties", "required", "additionalProperties", "items", "prefixItems", "minItems",
            "maxItems", "minLength", "maxLength", "pattern", "format", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum");

    static final int MAX_DEPTH = 32;

    private static final Set<String> TYPES = Set.of(
            "array", "boolean", "integer", "null", "number", "object", "string");
    private static final List<String> SUBSCHEMA_MAPS = List.of("properties", "$defs", "definitions");
    private static final List<String> SUBSCHEMA_LISTS = List.of("anyOf", "oneOf", "prefixItems");
    private static final List<String> NON_NEGATIVE_INTEGERS = List.of("minItems", "maxItems", "minLength",
            "maxLength");
    private static final List<String> NUMBERS = List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");
    private static final List<String> STRINGS = List.of("$schema", "$ref", "title", "description", "pattern",
            "format");

    /** @throws IllegalArgumentException when the service would answer {@code invalid_schema} */
    public static void validate(@NonNull JsonNode schema) {
        walk(schema, "", schema, 0);
        checkRefCycles(schema);
    }

    private static void walk(JsonNode node, String path, JsonNode root, int depth) {
        if (node.isBoolean()) {
            return;
        }
        var where = path.isEmpty() ? "/" : path;
        if (!node.isObject()) {
            throw invalid("%s: a schema must be an object or boolean".formatted(where));
        }
        if (depth > MAX_DEPTH) {
            throw invalid("schema is too deeply nested or recursive");
        }
        var unsupported = new TreeSet<String>();
        node.fieldNames().forEachRemaining(keyword -> {
            if (!ALLOWED_KEYWORDS.contains(keyword)) {
                unsupported.add(keyword);
            }
        });
        if (!unsupported.isEmpty()) {
            throw invalid("%s: unsupported keywords %s".formatted(where, unsupported));
        }
        checkShape(node, where);

        if (node.has("pattern")) {
            try {
                Pattern.compile(node.get("pattern").textValue());
            } catch (PatternSyntaxException exception) {
                throw invalid("%s: invalid regex pattern: %s".formatted(where, exception.getDescription()));
            }
        }
        if (node.has("$ref")) {
            var ref = node.get("$ref").textValue();
            if (pointerTarget(root, ref).isEmpty()) {
                throw invalid("%s: $ref '%s' must be a local #/ pointer".formatted(where, ref));
            }
        }

        for (var keyword : SUBSCHEMA_MAPS) {
            var map = node.path(keyword);
            map.fields().forEachRemaining(entry -> walk(entry.getValue(),
                    "%s/%s/%s".formatted(path, keyword, entry.getKey()), root, depth + 1));
        }
        for (var keyword : SUBSCHEMA_LISTS) {
            var list = node.path(keyword);
            for (int index = 0; index < list.size(); index++) {
                walk(list.get(index), "%s/%s/%d".formatted(path, keyword, index), root, depth + 1);
            }
        }
        if (node.has("items")) {
            walk(node.get("items"), path + "/items", root, depth + 1);
        }
        if (node.path("additionalProperties").isObject()) {
            walk(node.get("additionalProperties"), path + "/additionalProperties", root, depth + 1);
        }
    }

    /** The part of the 2020-12 metaschema that applies to the allowed keywords. */
    private static void checkShape(JsonNode node, String where) {
        for (var keyword : STRINGS) {
            if (node.has(keyword) && !node.get(keyword).isTextual()) {
                throw invalid("%s: '%s' must be a string".formatted(where, keyword));
            }
        }
        for (var keyword : NON_NEGATIVE_INTEGERS) {
            if (node.has(keyword) && !(node.get(keyword).isIntegralNumber() && node.get(keyword).asLong() >= 0)) {
                throw invalid("%s: '%s' must be a non-negative integer".formatted(where, keyword));
            }
        }
        for (var keyword : NUMBERS) {
            if (node.has(keyword) && !node.get(keyword).isNumber()) {
                throw invalid("%s: '%s' must be a number".formatted(where, keyword));
            }
        }
        for (var keyword : SUBSCHEMA_MAPS) {
            if (node.has(keyword) && !node.get(keyword).isObject()) {
                throw invalid("%s: '%s' must be an object".formatted(where, keyword));
            }
        }
        for (var keyword : SUBSCHEMA_LISTS) {
            if (node.has(keyword) && !(node.get(keyword).isArray() && !node.get(keyword).isEmpty())) {
                throw invalid("%s: '%s' must be a non-empty array".formatted(where, keyword));
            }
        }
        if (node.has("items") && !isSchema(node.get("items"))) {
            throw invalid("%s: 'items' must be a schema".formatted(where));
        }
        if (node.has("additionalProperties") && !isSchema(node.get("additionalProperties"))) {
            throw invalid("%s: 'additionalProperties' must be a schema".formatted(where));
        }
        if (node.has("enum") && !node.get("enum").isArray()) {
            throw invalid("%s: 'enum' must be an array".formatted(where));
        }
        if (node.has("required") && !isUniqueStrings(node.get("required"))) {
            throw invalid("%s: 'required' must be an array of unique strings".formatted(where));
        }
        if (node.has("type") && !isType(node.get("type"))) {
            throw invalid("%s: 'type' must be a JSON Schema type or an array of them".formatted(where));
        }
    }

    private static boolean isSchema(JsonNode node) {
        return node.isObject() || node.isBoolean();
    }

    private static boolean isUniqueStrings(JsonNode node) {
        if (!node.isArray()) {
            return false;
        }
        var seen = new HashSet<String>();
        for (var element : node) {
            if (!element.isTextual() || !seen.add(element.textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isType(JsonNode node) {
        if (node.isTextual()) {
            return TYPES.contains(node.textValue());
        }
        if (!node.isArray() || node.isEmpty() || !isUniqueStrings(node)) {
            return false;
        }
        for (var element : node) {
            if (!TYPES.contains(element.textValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The node a local {@code #/a/b} pointer names. Percent-decoded, then {@code ~1} is {@code /} and {@code ~0} is
     * {@code ~}; list indexes must be canonical (no {@code -1}, no {@code 01}), per RFC 6901.
     */
    static Optional<JsonNode> pointerTarget(JsonNode root, String ref) {
        if (ref == null || !ref.startsWith("#/")) {
            return Optional.empty();
        }
        var pointer = ref.substring(2);
        if (pointer.isEmpty()) {
            return Optional.of(root);
        }
        var node = root;
        for (var raw : pointer.split("/", -1)) {
            String segment;
            try {
                segment = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
                        .replace("~1", "/").replace("~0", "~");
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
            if (node.isObject() && node.has(segment)) {
                node = node.get(segment);
            } else if (node.isArray() && isCanonicalIndex(segment)) {
                var index = Integer.parseInt(segment);
                if (index >= node.size()) {
                    return Optional.empty();
                }
                node = node.get(index);
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(node);
    }

    private static boolean isCanonicalIndex(String segment) {
        return segment.matches("0|[1-9][0-9]{0,8}");
    }

    private static List<String> refs(JsonNode node) {
        var refs = new ArrayList<String>();
        collectRefs(node, refs);
        return refs;
    }

    private static void collectRefs(JsonNode node, List<String> refs) {
        if (node.isObject()) {
            var ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                refs.add(ref.textValue());
            }
            node.elements().forEachRemaining(value -> collectRefs(value, refs));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(value -> collectRefs(value, refs));
        }
    }

    /** Decoding and validation would recurse forever on a {@code $ref} that can reach itself. */
    private static void checkRefCycles(JsonNode root) {
        var done = new HashSet<String>();
        for (var ref : refs(root)) {
            visit(root, ref, new HashSet<>(), done);
        }
    }

    private static void visit(JsonNode root, String ref, Set<String> stack, Set<String> done) {
        if (stack.contains(ref)) {
            throw invalid("recursive $ref is not supported");
        }
        if (done.contains(ref)) {
            return;
        }
        var target = pointerTarget(root, ref);
        if (target.isPresent()) {
            stack.add(ref);
            for (var inner : refs(target.get())) {
                visit(root, inner, stack, done);
            }
            stack.remove(ref);
        }
        done.add(ref);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("invalid_schema: " + message);
    }
}
