package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JqToolTest {

    private final JqTool tool = new JqTool();

    @Test
    void specHasExpectedNameAndRequiredFields() {
        var spec = tool.spec();

        assertThat(spec.name()).isEqualTo(JqTool.NAME);
        assertThat(spec.parameters().required()).contains("type", "id", "expression");
    }

    @Test
    void missingExpressionReturnsError() {
        var ctx = newCtxWithCachedSpan(UUID.randomUUID(), "{\"name\":\"x\"}");

        var raw = tool.execute("{\"type\": \"span\", \"id\": \"abc\"}", ctx);
        var result = JsonUtils.getJsonNodeFromString(raw);

        assertThat(result.get("error").asText()).contains("Missing required argument: expression");
    }

    @Test
    void unknownTypeReturnsError() {
        var ctx = newCtxWithCachedSpan(UUID.randomUUID(), "{\"name\":\"x\"}");

        var raw = tool.execute(
                "{\"type\": \"unicorn\", \"id\": \"abc\", \"expression\": \".\"}", ctx);
        var result = JsonUtils.getJsonNodeFromString(raw);

        assertThat(result.get("error").asText()).contains("Unknown type");
    }

    @Test
    void cacheMissReturnsTextHint() {
        var ctx = newEmptyCtx();
        var id = UUID.randomUUID();

        var output = tool.execute(
                "{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".\"}".formatted(id), ctx);

        assertThat(output)
                .contains("not in cache")
                .contains("Call read first")
                .contains(id.toString())
                .contains("type=span");
    }

    @Test
    void simplePathExtractsScalarFromCachedSpan() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"name\":\"hello\",\"depth\":3}");

        var output = tool.execute(
                "{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".name\"}".formatted(spanId),
                ctx);

        assertThat(output).startsWith("[jq: span:" + spanId + " | expression='.name']");
        assertThat(output).contains("\"hello\"");
    }

    @Test
    void multiResultExpressionEmitsOnePerLine() {
        var traceId = UUID.randomUUID();
        var ctx = newCtxWithSeededActiveTrace(traceId);
        ctx.cache(new EntityRef(EntityType.TRACE, traceId.toString()),
                JsonUtils.getJsonNodeFromString(
                        "{\"spans\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}"));

        var output = tool.execute(
                "{\"type\": \"trace\", \"id\": \"%s\", \"expression\": \".spans[].name\"}"
                        .formatted(traceId),
                ctx);

        // Strip the header line.
        var body = output.substring(output.indexOf('\n') + 1);
        assertThat(body.lines()).containsExactly("\"a\"", "\"b\"", "\"c\"");
    }

    @Test
    void malformedExpressionReturnsErrorHeader() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"name\":\"hello\"}");

        var output = tool.execute(
                "{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".[\"}".formatted(spanId),
                ctx);

        assertThat(output).contains("ERROR");
        assertThat(output).startsWith("[jq: span:" + spanId);
    }

    @Test
    void runtimeErrorReturnsErrorHeader() {
        var spanId = UUID.randomUUID();
        // (.[]) on an object yields each value; calling .foo on a number throws.
        var ctx = newCtxWithCachedSpan(spanId, "{\"depth\":3}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".depth.foo\"}")
                        .formatted(spanId),
                ctx);

        assertThat(output).contains("ERROR");
    }

    @Test
    void outputCappedWhenExpressionYieldsLargeBody() {
        var spanId = UUID.randomUUID();
        // Build a span with a very long string field, then ask jq for it raw.
        String longValue = "x".repeat(JqTool.OUTPUT_CAP_CHARS + 500);
        var json = JsonUtils.getMapper().createObjectNode().put("blob", longValue);
        var ctx = newEmptyCtx();
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), json);

        var output = tool.execute(
                "{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".blob\"}".formatted(spanId),
                ctx);

        assertThat(output).contains("[TRUNCATED");
        assertThat(output).contains(JqTool.OUTPUT_TRUNCATION_HINT);
        // Body length (everything after the header line) must be header-line + capped body
        // + the trailing truncation marker, not the full original.
        assertThat(output.length()).isLessThan(JqTool.OUTPUT_CAP_CHARS * 2);
    }

    @Test
    void emptyResultProducesHeaderWithEmptyBody() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"items\":[]}");

        var output = tool.execute(
                "{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".items[]\"}".formatted(spanId),
                ctx);

        var lines = output.split("\n", -1);
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("[jq:");
        assertThat(lines[1]).isEmpty();
    }

    @Test
    void truncationHintInBodyReportsThousandsSeparator() {
        var spanId = UUID.randomUUID();
        String longValue = "y".repeat(JqTool.OUTPUT_CAP_CHARS + 4_500);
        var json = JsonUtils.getMapper().createObjectNode().put("blob", longValue);
        var ctx = newEmptyCtx();
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), json);

        var output = tool.execute(
                "{\"type\": \"span\", \"id\": \"%s\", \"expression\": \".blob\"}".formatted(spanId),
                ctx);

        // Dropped count includes the closing quote, so it's at least 4,500.
        assertThat(output).containsPattern("\\[TRUNCATED 4,5\\d{2} chars");
    }

    // --- helpers ---

    private static TraceToolContext newEmptyCtx() {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("active")
                .startTime(Instant.now())
                .build();
        return new TraceToolContext(trace, List.of(), "ws", "user");
    }

    private static TraceToolContext newCtxWithCachedSpan(UUID spanId, String spanBodyJson) {
        var ctx = newEmptyCtx();
        JsonNode node = JsonUtils.getJsonNodeFromString(spanBodyJson);
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), node);
        return ctx;
    }

    private static TraceToolContext newCtxWithSeededActiveTrace(UUID traceId) {
        var trace = Trace.builder()
                .id(traceId)
                .projectId(UUID.randomUUID())
                .name("active")
                .startTime(Instant.now())
                .build();
        return new TraceToolContext(trace, List.of(), "ws", "user");
    }
}