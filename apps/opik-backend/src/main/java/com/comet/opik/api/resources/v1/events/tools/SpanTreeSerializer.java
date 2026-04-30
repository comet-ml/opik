package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@UtilityClass
class SpanTreeSerializer {

    private static final int OVERVIEW_TRUNCATION_LENGTH = 500;

    String serializeOverview(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return "[]";
        }

        Map<UUID, List<Span>> childrenByParent = new LinkedHashMap<>();
        Set<UUID> spanIds = new HashSet<>();
        List<Span> roots = new ArrayList<>();

        for (var span : spans) {
            spanIds.add(span.id());
        }

        for (var span : spans) {
            if (span.parentSpanId() == null || !spanIds.contains(span.parentSpanId())) {
                roots.add(span);
            } else {
                childrenByParent.computeIfAbsent(span.parentSpanId(), k -> new ArrayList<>()).add(span);
            }
        }

        var arrayNode = JsonUtils.getMapper().createArrayNode();
        for (var root : roots) {
            arrayNode.add(buildTreeNode(root, childrenByParent));
        }
        return arrayNode.toString();
    }

    private static ObjectNode buildTreeNode(Span span, Map<UUID, List<Span>> childrenByParent) {
        var node = buildSpanNode(span);
        var children = childrenByParent.get(span.id());
        if (children != null && !children.isEmpty()) {
            var childrenArray = JsonUtils.getMapper().createArrayNode();
            for (var child : children) {
                childrenArray.add(buildTreeNode(child, childrenByParent));
            }
            node.set("children", childrenArray);
        }
        return node;
    }

    private static ObjectNode buildSpanNode(Span span) {
        var node = JsonUtils.getMapper().createObjectNode();
        node.put("id", span.id().toString());
        node.put("name", span.name());
        node.put("type", span.type() != null ? span.type().toString() : null);
        node.put("input", truncate(jsonNodeToString(span.input())));
        node.put("output", truncate(jsonNodeToString(span.output())));
        node.put("model", span.model());
        node.put("provider", span.provider());

        if (span.errorInfo() != null) {
            node.put("error_info", span.errorInfo().message());
        }
        if (span.duration() != null) {
            node.put("duration_ms", span.duration());
        }
        return node;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= OVERVIEW_TRUNCATION_LENGTH) {
            return value;
        }
        int dropped = value.length() - OVERVIEW_TRUNCATION_LENGTH;
        return value.substring(0, OVERVIEW_TRUNCATION_LENGTH)
                + "[TRUNCATED %,d chars]".formatted(dropped);
    }

    private static String jsonNodeToString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }
}