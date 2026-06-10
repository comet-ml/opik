package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathAwareTruncatorTest {

    @Test
    void leavesShortStringsAlone() {
        var input = JsonUtils.getJsonNodeFromString("{\"a\":\"short\",\"b\":42}");

        var out = PathAwareTruncator.truncate(input, 10);

        assertThat(out.get("a").asText()).isEqualTo("short");
        assertThat(out.get("b").asInt()).isEqualTo(42);
    }

    @Test
    void truncatesTopLevelStringWithJqPath() {
        var input = JsonUtils.getJsonNodeFromString("{\"input\":\"%s\"}".formatted("x".repeat(50)));

        var out = PathAwareTruncator.truncate(input, 10);

        var truncated = out.get("input").asText();
        assertThat(truncated).startsWith("xxxxxxxxxx");
        assertThat(truncated).contains("[TRUNCATED 40 chars — use jq('.input') to see full]");
    }

    @Test
    void truncatesNestedFieldWithCorrectPath() {
        var input = JsonUtils.getJsonNodeFromString(
                "{\"meta\":{\"description\":\"%s\"}}".formatted("y".repeat(20)));

        var out = PathAwareTruncator.truncate(input, 5);

        var truncated = out.get("meta").get("description").asText();
        assertThat(truncated).startsWith("yyyyy");
        assertThat(truncated).contains("[TRUNCATED 15 chars — use jq('.meta.description') to see full]");
    }

    @Test
    void truncatesArrayElementWithIndexPath() {
        var input = JsonUtils.getJsonNodeFromString(
                "{\"spans\":[{\"input\":\"a\"},{\"input\":\"%s\"}]}".formatted("z".repeat(30)));

        var out = PathAwareTruncator.truncate(input, 5);

        var truncated = out.get("spans").get(1).get("input").asText();
        assertThat(truncated).startsWith("zzzzz");
        assertThat(truncated).contains("[TRUNCATED 25 chars — use jq('.spans[1].input') to see full]");
        // First span untouched
        assertThat(out.get("spans").get(0).get("input").asText()).isEqualTo("a");
    }

    @Test
    void escapesNonIdentifierKeys() {
        var input = JsonUtils.getJsonNodeFromString(
                "{\"weird key\":\"%s\"}".formatted("v".repeat(20)));

        var out = PathAwareTruncator.truncate(input, 5);

        var truncated = out.get("weird key").asText();
        assertThat(truncated).contains("use jq('.[\"weird key\"]') to see full");
    }

    @Test
    void doesNotMutateInput() {
        var original = JsonUtils.getJsonNodeFromString(
                "{\"input\":\"%s\"}".formatted("q".repeat(50)));
        String before = original.toString();

        JsonNode out = PathAwareTruncator.truncate(original, 10);

        assertThat(original.toString()).isEqualTo(before);
        assertThat(out).isNotSameAs(original);
    }

    @Test
    void thousandsSeparatorInDroppedCount() {
        var input = JsonUtils.getJsonNodeFromString(
                "{\"input\":\"%s\"}".formatted("a".repeat(4_200)));

        var out = PathAwareTruncator.truncate(input, 200);

        var truncated = out.get("input").asText();
        assertThat(truncated).contains("[TRUNCATED 4,000 chars — use jq('.input') to see full]");
    }

    @Test
    void bareSuffixStyleOmitsJqHint() {
        var input = JsonUtils.getJsonNodeFromString(
                "{\"input\":\"%s\"}".formatted("a".repeat(4_200)));

        var out = PathAwareTruncator.truncate(input, 200, PathAwareTruncator.SuffixStyle.BARE);

        var truncated = out.get("input").asText();
        assertThat(truncated)
                .startsWith("a".repeat(200))
                .endsWith("[TRUNCATED 4,000 chars]")
                .doesNotContain("use jq")
                .doesNotContain("to see full");
    }

    @Test
    void noArgOverloadDefaultsToWithJqHint() {
        var input = JsonUtils.getJsonNodeFromString(
                "{\"input\":\"%s\"}".formatted("a".repeat(50)));

        var out = PathAwareTruncator.truncate(input, 10);

        assertThat(out.get("input").asText()).contains("use jq('.input')");
    }
}