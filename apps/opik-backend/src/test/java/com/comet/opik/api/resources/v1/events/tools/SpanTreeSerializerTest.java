package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpanTreeSerializerTest {

    @Nested
    class SerializeOverview {

        @Test
        void emptyList() {
            assertThat(SpanTreeSerializer.serializeOverview(List.of())).isEqualTo("[]");
        }

        @Test
        void nullList() {
            assertThat(SpanTreeSerializer.serializeOverview(null)).isEqualTo("[]");
        }

        @Test
        void flatSpans() {
            var span1 = buildSpan(UUID.randomUUID(), null, "llm_call", SpanType.llm, "gpt-4");
            var span2 = buildSpan(UUID.randomUUID(), null, "tool_call", SpanType.tool, null);

            var result = SpanTreeSerializer.serializeOverview(List.of(span1, span2));
            var node = parseJson(result);

            assertThat(node.isArray()).isTrue();
            assertThat(node.size()).isEqualTo(2);
            assertThat(node.get(0).get("name").asText()).isEqualTo("llm_call");
            assertThat(node.get(0).get("type").asText()).isEqualTo("llm");
            assertThat(node.get(0).get("model").asText()).isEqualTo("gpt-4");
            assertThat(node.get(1).get("name").asText()).isEqualTo("tool_call");
            assertThat(node.get(1).get("type").asText()).isEqualTo("tool");
        }

        @Test
        void nestedHierarchy() {
            var parentId = UUID.randomUUID();
            var childId = UUID.randomUUID();
            var grandchildId = UUID.randomUUID();

            var parent = buildSpan(parentId, null, "root", SpanType.general, null);
            var child = buildSpan(childId, parentId, "child_llm", SpanType.llm, "claude-3");
            var grandchild = buildSpan(grandchildId, childId, "tool_search", SpanType.tool, null);

            var result = SpanTreeSerializer.serializeOverview(List.of(parent, child, grandchild));
            var node = parseJson(result);

            assertThat(node.size()).isEqualTo(1);
            var rootNode = node.get(0);
            assertThat(rootNode.get("name").asText()).isEqualTo("root");
            assertThat(rootNode.has("children")).isTrue();
            assertThat(rootNode.get("children").size()).isEqualTo(1);

            var childNode = rootNode.get("children").get(0);
            assertThat(childNode.get("name").asText()).isEqualTo("child_llm");
            assertThat(childNode.has("children")).isTrue();

            var grandchildNode = childNode.get("children").get(0);
            assertThat(grandchildNode.get("name").asText()).isEqualTo("tool_search");
        }

        @Test
        void truncatesLargeInput() {
            var longInput = "x".repeat(1000);
            var span = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .name("big_span")
                    .type(SpanType.general)
                    .startTime(java.time.Instant.now())
                    .input(JsonUtils.getMapper().valueToTree(Map.of("data", longInput)))
                    .build();

            var result = SpanTreeSerializer.serializeOverview(List.of(span));
            var node = parseJson(result);

            var inputText = node.get(0).get("input").asText();
            assertThat(inputText).contains("[TRUNCATED");
            assertThat(inputText).contains("chars]");
            assertThat(inputText.length()).isLessThan(longInput.length());
        }

        @Test
        void includesSpanId() {
            var spanId = UUID.randomUUID();
            var span = buildSpan(spanId, null, "test", SpanType.general, null);

            var result = SpanTreeSerializer.serializeOverview(List.of(span));
            var node = parseJson(result);

            assertThat(node.get(0).get("id").asText()).isEqualTo(spanId.toString());
        }

        @Test
        void includesErrorInfo() {
            var span = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .name("failed_span")
                    .type(SpanType.general)
                    .startTime(java.time.Instant.now())
                    .errorInfo(ErrorInfo.builder()
                            .exceptionType("ValueError")
                            .message("something went wrong")
                            .traceback("traceback...")
                            .build())
                    .duration(42.5)
                    .build();

            var result = SpanTreeSerializer.serializeOverview(List.of(span));
            var node = parseJson(result);

            assertThat(node.get(0).get("error_info").asText()).isEqualTo("something went wrong");
            assertThat(node.get(0).get("duration_ms").asDouble()).isEqualTo(42.5);
        }
    }

    private static Span buildSpan(UUID id, UUID parentSpanId, String name, SpanType type, String model) {
        return Span.builder()
                .id(id)
                .traceId(UUID.randomUUID())
                .parentSpanId(parentSpanId)
                .name(name)
                .type(type)
                .model(model)
                .startTime(java.time.Instant.now())
                .build();
    }

    private static JsonNode parseJson(String json) {
        return JsonUtils.getJsonNodeFromString(json);
    }
}