package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchToolTest {

    private final SearchTool tool = new SearchTool();

    @Test
    void specHasExpectedNameAndRequiredFields() {
        var spec = tool.spec();

        assertThat(spec.name()).isEqualTo(SearchTool.NAME);
        assertThat(spec.parameters().required()).contains("type", "id", "pattern");
    }

    @Test
    void missingPatternReturnsError() {
        var ctx = newCtxWithCachedSpan(UUID.randomUUID(), "{}");

        var raw = tool.execute("{\"type\": \"span\", \"id\": \"abc\"}", ctx);
        var result = JsonUtils.getJsonNodeFromString(raw);

        assertThat(result.get("error").asText()).contains("Missing required argument: pattern");
    }

    @Test
    void unknownTypeReturnsError() {
        var ctx = newCtxWithCachedSpan(UUID.randomUUID(), "{}");

        var raw = tool.execute(
                "{\"type\": \"unicorn\", \"id\": \"abc\", \"pattern\": \"x\"}", ctx);
        var result = JsonUtils.getJsonNodeFromString(raw);

        assertThat(result.get("error").asText()).contains("Unknown type");
    }

    @Test
    void cacheMissReturnsCallReadHint() {
        var ctx = newEmptyCtx();
        var id = UUID.randomUUID();

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"x\"}").formatted(id),
                ctx);

        assertThat(output)
                .contains("not in cache")
                .contains("Call read first")
                .contains("type=span")
                .contains(id.toString());
    }

    @Test
    void noMatchesReturnsZeroHeaderAndHint() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"name\":\"hello world\"}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"timeout\"}").formatted(spanId),
                ctx);

        var lines = output.split("\n", -1);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines[0]).contains("0 matches");
        assertThat(output).contains("No matches found");
    }

    @Test
    void simpleMatchReturnsPathAndQuotedValue() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId,
                "{\"input\":\"please retry on timeout\",\"name\":\"calculator\"}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"timeout\"}").formatted(spanId),
                ctx);

        assertThat(output).startsWith("[search: span:" + spanId + " | pattern='timeout' | 1 matches]");
        assertThat(output).contains("input: \"please retry on timeout\"");
    }

    @Test
    void caseInsensitiveMatch() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"err\":\"Connection TIMEOUT\"}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"timeout\"}").formatted(spanId),
                ctx);

        assertThat(output).contains("err: \"Connection TIMEOUT\"");
        assertThat(output).contains("1 matches");
    }

    @Test
    void matchesNestedPathsWithBracketIndices() {
        var spanId = UUID.randomUUID();
        // spans[1].input contains the match.
        var ctx = newCtxWithCachedSpan(spanId,
                "{\"spans\":[{\"input\":\"ok\"},{\"input\":\"timeout exceeded\"}]}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"timeout\"}").formatted(spanId),
                ctx);

        assertThat(output).contains("spans[1].input: \"timeout exceeded\"");
    }

    @Test
    void valueTruncationAppliesAt200Chars() {
        var spanId = UUID.randomUUID();
        // Build a value that contains the match but is well over 200 chars total.
        String filler = "X".repeat(400);
        String value = filler + "timeout " + filler;
        var json = JsonUtils.getMapper().createObjectNode().put("err", value);
        var ctx = newEmptyCtx();
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), json);

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"timeout\"}").formatted(spanId),
                ctx);

        assertThat(output).contains("[TRUNCATED");
        // Total length minus first 200 chars dropped, with thousands separator.
        int totalLen = value.length();
        int dropped = totalLen - SearchTool.VALUE_TRUNCATION_LENGTH;
        assertThat(output).contains("[TRUNCATED %s chars]".formatted(String.format("%,d", dropped)));
    }

    @Test
    void capsMatchesAt50AndReportsTrueTotal() {
        var spanId = UUID.randomUUID();
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (int i = 0; i < 75; i++) {
            arr.add("hit-" + i + "-needle");
        }
        var ctx = newEmptyCtx();
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), root);

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"needle\"}").formatted(spanId),
                ctx);

        assertThat(output).startsWith(
                "[search: span:" + spanId + " | pattern='needle' | 75 matches (showing 50)]");
        // Body has exactly 50 rows; header is line 1, rows are lines 2..51.
        long rowLines = output.lines().filter(line -> line.startsWith("items[")).count();
        assertThat(rowLines).isEqualTo(50);
    }

    @Test
    void pathArgScopesSearchAndAppearsInHeader() {
        var spanId = UUID.randomUUID();
        // Two regions: only the .scope branch should be searched.
        var json = JsonUtils.getJsonNodeFromString(
                "{\"scope\":{\"err\":\"timeout here\"},\"other\":{\"err\":\"timeout there\"}}");
        var ctx = newEmptyCtx();
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), json);

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"timeout\", \"path\": \".scope\"}")
                        .formatted(spanId),
                ctx);

        assertThat(output).contains("path='.scope'");
        assertThat(output).contains("1 matches");
        assertThat(output).contains("scope.err: \"timeout here\"");
        assertThat(output).doesNotContain("other.err");
    }

    @Test
    void unsafePathIsRejectedAtParseTime() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"err\":\"hi\"}");

        // A pipe expression — a fully-formed jq program in the path slot. Must not reach jackson-jq.
        var raw = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"hi\", \"path\": \". | error(\\\"leak\\\")\"}")
                        .formatted(spanId),
                ctx);
        var result = JsonUtils.getJsonNodeFromString(raw);

        assertThat(result.get("error").asText())
                .contains("path must be a simple jq scope expression");
    }

    @Test
    void simplePathShapesAreAccepted() {
        var spanId = UUID.randomUUID();
        var json = JsonUtils.getJsonNodeFromString(
                "{\"spans\":[{\"input\":\"alpha\"},{\"input\":\"beta needle\"}]}");
        var ctx = newEmptyCtx();
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), json);

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"needle\", \"path\": \".spans[1].input\"}")
                        .formatted(spanId),
                ctx);

        assertThat(output).contains("1 matches");
        assertThat(output).doesNotContain("ERROR");
    }

    @Test
    void malformedRegexReturnsErrorHeader() {
        var spanId = UUID.randomUUID();
        var ctx = newCtxWithCachedSpan(spanId, "{\"err\":\"hi\"}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"[invalid\"}").formatted(spanId),
                ctx);

        assertThat(output).contains("ERROR");
        assertThat(output).contains("Regex failure");
    }

    @Test
    void onlyStringValuesAreSearched() {
        var spanId = UUID.randomUUID();
        // The number 42, the boolean true, the null, and the key "hit42" all contain "42",
        // but only the key (not searched) and the string value "hit-42" should match.
        var ctx = newCtxWithCachedSpan(spanId,
                "{\"hit42\":42,\"flag\":true,\"empty\":null,\"text\":\"hit-42-here\"}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"42\"}").formatted(spanId),
                ctx);

        // Only the .text string value contains "42" and should match.
        assertThat(output).contains("1 matches");
        assertThat(output).contains("text: \"hit-42-here\"");
    }

    @Test
    void patternIsJsonEscapedSoQuotesDontBreakTheQuery() {
        var spanId = UUID.randomUUID();
        // Pattern contains a double-quote and a backslash; without proper escaping this
        // would break the embedded jq string literal.
        var ctx = newCtxWithCachedSpan(spanId, "{\"err\":\"he said \\\"oops\\\" loudly\"}");

        var output = tool.execute(
                ("{\"type\": \"span\", \"id\": \"%s\", \"pattern\": \"\\\"oops\\\"\"}").formatted(spanId),
                ctx);

        // Should not crash (no ERROR), and should match the value containing the quoted word.
        assertThat(output).doesNotContain("ERROR");
        assertThat(output).contains("err:");
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
}