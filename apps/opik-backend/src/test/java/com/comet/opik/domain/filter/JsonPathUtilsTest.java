package com.comet.opik.domain.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.comet.opik.domain.filter.JsonPathUtils.toAnalyticsDbJsonPath;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonPathUtils")
class JsonPathUtilsTest {

    @Nested
    @DisplayName("Keys expressible in dot notation")
    class DotNotationPreserved {

        static Stream<Arguments> plainKeysAreUnchanged() {
            return Stream.of(
                    Arguments.of("environment", "$.environment"),
                    Arguments.of("key_name", "$.key_name"),
                    Arguments.of("a.b.c", "$.a.b.c"),
                    Arguments.of("model2", "$.model2"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("keep producing the exact same path")
        void plainKeysAreUnchanged(String key, String expected) {
            assertThat(toAnalyticsDbJsonPath(key)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Keys already carrying JSONPath syntax")
    class PathExpressionsUntouched {

        static Stream<Arguments> pathExpressionsArePassedThrough() {
            return Stream.of(
                    Arguments.of("$.a.b", "$.a.b"),
                    Arguments.of("$[1].version", "$[1].version"),
                    Arguments.of("$['already-bracketed']", "$['already-bracketed']"),
                    Arguments.of(".version[1]", "$.version[1]"),
                    Arguments.of("[0].model", "$[0].model"),
                    Arguments.of("version[*]", "$.version[*]"),
                    Arguments.of("a.b[0].c", "$.a.b[0].c"),
                    Arguments.of("a[\"version\"].b", "$.a[\"version\"].b"),
                    Arguments.of("a['version'].b", "$.a['version'].b"),
                    Arguments.of("a[\"x]b\"].c", "$.a[\"x]b\"].c"),
                    Arguments.of("a['x]b'].c", "$.a['x]b'].c"),
                    Arguments.of("a[\"x[b\"].c", "$.a[\"x[b\"].c"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("are assembled exactly as before, never rewritten")
        void pathExpressionsArePassedThrough(String key, String expected) {
            assertThat(toAnalyticsDbJsonPath(key)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a wildcard index is preserved rather than quoted into a literal key")
        void wildcardIndexIsPreserved() {
            assertThat(toAnalyticsDbJsonPath("version[*]"))
                    .isEqualTo("$.version[*]")
                    .isNotEqualTo("$['version[*]']");
        }

        @Test
        @DisplayName("the bare root is still a path")
        void bareRootIsAPath() {
            assertThat(toAnalyticsDbJsonPath("$")).isEqualTo("$");
        }
    }

    @Nested
    @DisplayName("Keys dot notation cannot express")
    class BracketNotationFallback {

        static Stream<Arguments> fallBackToBracketNotation() {
            return Stream.of(
                    Arguments.of("a-b", "$['a-b']"),
                    Arguments.of("x-litellm-attempted-retries", "$['x-litellm-attempted-retries']"),
                    Arguments.of("hidden_params.additional_headers.x-litellm-attempted-retries",
                            "$['hidden_params']['additional_headers']['x-litellm-attempted-retries']"),
                    Arguments.of("key with space", "$['key with space']"),
                    Arguments.of("ключ", "$['ключ']"),
                    Arguments.of("a..b", "$['a']['']['b']"),
                    Arguments.of("trailing.", "$['trailing']['']"),
                    Arguments.of("feature[beta]", "$['feature[beta]']"),
                    Arguments.of("a]b", "$['a]b']"),
                    Arguments.of("$schema", "$['$schema']"),
                    Arguments.of("$ref", "$['$ref']"),
                    Arguments.of("$foo", "$['$foo']"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("fall back to bracket notation, splitting on dots as before")
        void fallBackToBracketNotation(String key, String expected) {
            assertThat(toAnalyticsDbJsonPath(key)).isEqualTo(expected);
        }

        @Test
        @DisplayName("single quotes in a key are escaped rather than terminating the segment")
        void singleQuotesAreEscaped() {
            assertThat(toAnalyticsDbJsonPath("e'f")).isEqualTo("$['e\\'f']");
        }

        @Test
        @DisplayName("backslashes in a key are escaped")
        void backslashesAreEscaped() {
            assertThat(toAnalyticsDbJsonPath("a\\b")).isEqualTo("$['a\\\\b']");
        }
    }

    @Nested
    @DisplayName("Authored expressions that cannot parse under any grammar")
    class DamagedExpressionsMatchNothing {

        static Stream<Arguments> areQuotedIntoALiteralKey() {
            return Stream.of(
                    Arguments.of("$['unterminated", "$['$[\\'unterminated']"),
                    Arguments.of("$[0", "$['$[0']"),
                    Arguments.of("$.a]", "$['$']['a]']"),
                    Arguments.of("$.", "$['$']['']"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("become a literal key, so the query runs and returns nothing instead of aborting")
        void areQuotedIntoALiteralKey(String key, String expected) {
            assertThat(toAnalyticsDbJsonPath(key)).isEqualTo(expected);
        }

        static Stream<Arguments> wellFormedExpressionsSurvive() {
            return Stream.of(
                    Arguments.of("version[*]", "$.version[*]"),
                    Arguments.of("$[1].version", "$[1].version"),
                    Arguments.of(".version[1]", "$.version[1]"),
                    Arguments.of("$.key[0]['another_key']", "$.key[0]['another_key']"),
                    Arguments.of("$.input['key1'][12]['key2']", "$.input['key1'][12]['key2']"),
                    Arguments.of("$['it\\'s quoted']", "$['it\\'s quoted']"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("well-formed expressions keep their exact text, including shapes no allowlist enumerates")
        void wellFormedExpressionsSurvive(String key, String expected) {
            assertThat(toAnalyticsDbJsonPath(key)).isEqualTo(expected);
        }
    }
}
