package com.comet.opik.infrastructure.redaction;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Field Masker")
class FieldMaskerTest {

    private static final String MASK = "[REDACTED]";

    private static FieldMasker masker(String... fields) {
        return new FieldMasker(Set.of(fields), MASK);
    }

    private static JsonNode mask(FieldMasker masker, String json) {
        return masker.mask(JsonUtils.getJsonNodeFromString(json));
    }

    static Stream<Arguments> masksTheConfiguredNameAtAnyDepth() {
        return Stream.of(
                Arguments.of("flat", """
                        {"content":"secret","role":"user"}""",
                        """
                                {"content":"[REDACTED]","role":"user"}"""),
                // The shape a chat payload takes.
                Arguments.of("inside an array", """
                        {"messages":[{"role":"user","content":"secret"}]}""",
                        """
                                {"messages":[{"role":"user","content":"[REDACTED]"}]}"""),
                // The shape LangChain writes: the same field, three levels deeper. A path-based config would miss it.
                Arguments.of("deeply nested", """
                        {"messages":[{"kwargs":{"message":{"content":"secret"}}}]}""",
                        """
                                {"messages":[{"kwargs":{"message":{"content":"[REDACTED]"}}}]}"""),
                Arguments.of("repeated across branches", """
                        {"a":{"content":"one"},"b":{"c":{"content":"two"}}}""",
                        """
                                {"a":{"content":"[REDACTED]"},"b":{"c":{"content":"[REDACTED]"}}}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void masksTheConfiguredNameAtAnyDepth(String label, String input, String expected) {
        assertThat(mask(masker("content"), input)).isEqualTo(JsonUtils.getJsonNodeFromString(expected));
    }

    @Test
    @DisplayName("field names are never rewritten, so a map keyed by dates keeps its keys")
    void fieldNamesAreNeverRewritten() {
        // The failure this guards against is a text-level rewrite turning both keys into the same token, which
        // produces valid JSON with duplicate keys and silently loses one of the two data points.
        var masked = mask(masker("2024-03-15", "content"), """
                {"usage_by_day":{"2024-03-15":120,"2024-03-16":98}}""");

        assertThat(masked).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"usage_by_day":{"2024-03-15":120,"2024-03-16":98}}"""));
    }

    @Test
    @DisplayName("a configured name masks everything beneath it, not only a scalar")
    void aConfiguredNameMasksEverythingBeneathIt() {
        var masked = mask(masker("content"), """
                {"content":[{"type":"text","text":"secret"},{"nested":{"deep":"also secret"}}],"role":"user"}""");

        assertThat(masked).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"content":[{"type":"[REDACTED]","text":"[REDACTED]"},\
                {"nested":{"deep":"[REDACTED]"}}],"role":"user"}"""));
    }

    @Test
    @DisplayName("numbers and booleans under a configured name are left as stored")
    void nonTextualValuesAreLeftAsStored() {
        var masked = mask(masker("content"), """
                {"content":{"score":0.42,"ok":true,"missing":null,"text":"secret"}}""");

        assertThat(masked).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"content":{"score":0.42,"ok":true,"missing":null,"text":"[REDACTED]"}}"""));
    }

    @Test
    @DisplayName("an unconfigured name is returned as stored")
    void anUnconfiguredNameIsReturnedAsStored() {
        var stored = """
                {"model":"gpt-4o-2024-08-06","thread_id":"user-42","role":"assistant"}""";

        assertThat(mask(masker("content"), stored)).isEqualTo(JsonUtils.getJsonNodeFromString(stored));
    }

    @Test
    @DisplayName("no configured fields is a pass-through, so an enabled deployment with no config changes nothing")
    void noConfiguredFieldsIsAPassThrough() {
        var stored = JsonUtils.getJsonNodeFromString("""
                {"content":"secret"}""");

        assertThat(FieldMasker.noOp().mask(stored)).isSameAs(stored);
        assertThat(FieldMasker.noOp().isNoOp()).isTrue();
    }

    @Test
    @DisplayName("null is tolerated, since a content column can be absent")
    void nullIsTolerated() {
        assertThat(masker("content").mask(null)).isNull();
    }

    static Stream<Arguments> maskNamedTreatsAMapKeyAsAFieldName() {
        return Stream.of(
                Arguments.of("configured column masks wholesale", "content", """
                        {"a":"secret"}""", """
                        {"a":"[REDACTED]"}"""),
                Arguments.of("unconfigured column still masks configured names within", "question", """
                        {"content":"secret","other":"kept"}""", """
                        {"content":"[REDACTED]","other":"kept"}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void maskNamedTreatsAMapKeyAsAFieldName(String label, String columnName, String value, String expected) {
        // Dataset items carry a Map<String, JsonNode> whose keys are columns the caller named, so a column called
        // after a configured field has to mask the same way that field would.
        var masked = masker("content").maskNamed(columnName, JsonUtils.getJsonNodeFromString(value));

        assertThat(masked).isEqualTo(JsonUtils.getJsonNodeFromString(expected));
    }

    @Test
    @DisplayName("maskEveryString ignores field names, so a caller-chosen key cannot evade it")
    void maskEveryStringIgnoresFieldNames() {
        // Agent Insights free-form SQL lets the caller build the result, keys included, so name-matching keys
        // off a name they pick. map('x', input) would walk straight past a configured set.
        var masked = masker("content").maskEveryString(JsonUtils.getJsonNodeFromString("""
                {"x":"secret","nested":{"y":["also secret","and this"]}}"""));

        assertThat(masked).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"x":"[REDACTED]","nested":{"y":["[REDACTED]","[REDACTED]"]}}"""));
    }

    @Test
    @DisplayName("maskEveryString leaves numbers, so aggregates still work without the permission")
    void maskEveryStringLeavesNumbers() {
        // This is why the endpoint masks rather than refuses: counts and averages survive, content does not.
        var masked = masker("content").maskEveryString(JsonUtils.getJsonNodeFromString("""
                {"total":1284,"avg_duration":9.75,"ok":true,"sample":"john.doe@example.com"}"""));

        assertThat(masked).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"total":1284,"avg_duration":9.75,"ok":true,"sample":"[REDACTED]"}"""));
    }

    @Test
    @DisplayName("maskEveryString on a no-op masker is a pass-through")
    void maskEveryStringOnNoOpIsPassThrough() {
        var stored = JsonUtils.getJsonNodeFromString("""
                {"x":"secret"}""");

        assertThat(FieldMasker.noOp().maskEveryString(stored)).isSameAs(stored);
    }
}
