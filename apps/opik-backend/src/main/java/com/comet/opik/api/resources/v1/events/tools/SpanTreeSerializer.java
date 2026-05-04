package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
class SpanTreeSerializer {

    private static final int OVERVIEW_TRUNCATION_LENGTH = 500;

    String serializeOverview(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return "[]";
        }
        return SpanHierarchy.toTree(spans, SpanTreeSerializer::buildSpanNode).toString();
    }

    private static ObjectNode buildSpanNode(Span span) {
        var node = JsonUtils.getMapper().createObjectNode();
        node.put("id", span.id().toString());
        node.put("name", span.name());
        node.put("type", span.type() != null ? span.type().toString() : null);
        node.put("input",
                StringTruncator.truncate(jsonNodeToString(span.input()), OVERVIEW_TRUNCATION_LENGTH, null));
        node.put("output",
                StringTruncator.truncate(jsonNodeToString(span.output()), OVERVIEW_TRUNCATION_LENGTH, null));
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

    private static String jsonNodeToString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }
}