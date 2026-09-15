package com.comet.opik.infrastructure.llm;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.comet.opik.infrastructure.llm.GeminiThinkingParams.Level;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiThinkingParamsTest {

    private static GeminiThinkingParams fromJson(String json) {
        return GeminiThinkingParams.from(JsonUtils.getJsonNodeFromString(json));
    }

    @Nested
    @DisplayName("Decoding from JSON custom parameters (judge path)")
    class JsonDecoding {

        @ParameterizedTest
        @EnumSource(Level.class)
        void decodesEveryLevel(Level level) {
            var params = fromJson("{\"thinking\": {\"level\": \"%s\"}}".formatted(level.wireValue()));

            assertThat(params.level()).isEqualTo(level);
            assertThat(params.isAbsent()).isFalse();
        }

        @Test
        void decodesLevelCaseInsensitively() {
            assertThat(fromJson("{\"thinking\": {\"level\": \"HIGH\"}}").level()).isEqualTo(Level.HIGH);
        }

        @Test
        void decodesBudgetAndIncludeThoughts() {
            var params = fromJson(
                    "{\"thinking\": {\"budget_tokens\": 4096, \"include_thoughts\": true}}");

            assertThat(params.budgetTokens()).isEqualTo(4096);
            assertThat(params.includeThoughts()).isTrue();
        }

        @Test
        @DisplayName("a zero budget is kept: it is how thinking is disabled")
        void keepsZeroBudget() {
            assertThat(fromJson("{\"thinking\": {\"budget_tokens\": 0}}").budgetTokens()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"-1", "-4096", "\"1024\"", "1.5", "null"})
        void dropsBudgetsThatAreNotNonNegativeIntegers(String budget) {
            assertThat(fromJson("{\"thinking\": {\"budget_tokens\": %s}}".formatted(budget)).budgetTokens()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"\"", "\" \"", "\"aggressive\"", "null", "5"})
        void dropsUnrecognisedLevels(String level) {
            assertThat(fromJson("{\"thinking\": {\"level\": %s}}".formatted(level)).level()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{}",
                "{\"thinking\": null}",
                "{\"thinking\": {}}",
                "{\"thinking\": \"high\"}",
                "{\"thinking\": 3}",
                "{\"other\": {\"level\": \"high\"}}"})
        void treatsMissingOrMalformedThinkingAsAbsent(String json) {
            assertThat(fromJson(json).isAbsent()).isTrue();
        }

        @Test
        void treatsNullCustomParametersAsAbsent() {
            assertThat(GeminiThinkingParams.from((com.fasterxml.jackson.databind.JsonNode) null).isAbsent()).isTrue();
        }
    }

    @Nested
    @DisplayName("Decoding from map custom parameters (playground path)")
    class MapDecoding {

        @Test
        void decodesLevelBudgetAndIncludeThoughts() {
            var params = GeminiThinkingParams.from(Map.of(
                    "thinking", Map.of("level", "medium", "budget_tokens", 1024, "include_thoughts", false)));

            assertThat(params.level()).isEqualTo(Level.MEDIUM);
            assertThat(params.budgetTokens()).isEqualTo(1024);
            assertThat(params.includeThoughts()).isFalse();
        }

        @Test
        void dropsNegativeBudget() {
            assertThat(GeminiThinkingParams.from(Map.of("thinking", Map.of("budget_tokens", -1))).budgetTokens())
                    .isNull();
        }

        @Test
        void dropsFractionalBudgetRatherThanTruncatingIt() {
            assertThat(GeminiThinkingParams.from(Map.of("thinking", Map.of("budget_tokens", 1.5))).budgetTokens())
                    .isNull();
        }

        @Test
        void dropsBudgetAboveIntegerRange() {
            assertThat(GeminiThinkingParams.from(
                    Map.of("thinking", Map.of("budget_tokens", Integer.MAX_VALUE + 1L))).budgetTokens())
                    .isNull();
        }

        @ParameterizedTest
        @CsvSource({"0,0", "2147483647,2147483647"})
        @DisplayName("both accepted boundaries survive: 0 disables thinking, MAX_VALUE is the ceiling")
        void keepsBudgetsAtTheAcceptedBoundaries(long given, int expected) {
            assertThat(GeminiThinkingParams.from(Map.of("thinking", Map.of("budget_tokens", (int) given)))
                    .budgetTokens()).isEqualTo(expected);
        }

        @Test
        void ignoresWronglyTypedValues() {
            var params = GeminiThinkingParams.from(Map.of(
                    "thinking", Map.of("level", 7, "budget_tokens", "1024", "include_thoughts", "yes")));

            assertThat(params.isAbsent()).isTrue();
        }

        @Test
        void treatsNullAndNonMapThinkingAsAbsent() {
            assertThat(GeminiThinkingParams.from((Map<String, Object>) null).isAbsent()).isTrue();
            assertThat(GeminiThinkingParams.from(Map.of("thinking", "high")).isAbsent()).isTrue();
        }
    }

    @Nested
    @DisplayName("Which models take a level on the wire")
    class ModelVersionGate {

        @ParameterizedTest
        @CsvSource({
                "gemini-3-flash-preview,true",
                "gemini-3.7-flash,true",
                "gemini-3.1-pro-preview,true",
                "vertex_ai/gemini-3.5-flash,true",
                "gemini-2.5-flash,false",
                "gemini-2.5-pro,false",
                "vertex_ai/gemini-2.5-flash-lite-preview-06-17,false",
                "gemini-2.0-flash,false",
                "gemini-1.5-flash-latest,false"})
        void recognisesTheMajorVersion(String model, boolean acceptsLevel) {
            assertThat(GeminiThinkingParams.modelAcceptsLevel(model)).isEqualTo(acceptsLevel);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "gemini-omni-flash-preview",
                "gemini-omni-1.1-flash",
                "gemini-flash-latest",
                "gemini-99999999999-flash",
                "",
                "not-a-gemini-model"})
        @DisplayName("an id with no readable version falls back to a budget, the direction every generation accepts")
        void unversionedIdsDoNotClaimLevelSupport(String model) {
            assertThat(GeminiThinkingParams.modelAcceptsLevel(model)).isFalse();
        }
    }

    @Nested
    @DisplayName("Level to budget translation (Vertex has no level field)")
    class BudgetTranslation {

        @ParameterizedTest
        @CsvSource({"OFF,0", "MINIMAL,512", "LOW,2048", "MEDIUM,8192", "HIGH,24576"})
        void translatesLevelToBudget(Level level, int expectedBudget) {
            assertThat(new GeminiThinkingParams(level, null, null).budgetForLevel()).isEqualTo(expectedBudget);
        }

        @Test
        @DisplayName("budgets increase with the level, so the ordering stays meaningful")
        void budgetsIncreaseWithLevel() {
            assertThat(Level.values())
                    .extracting(level -> new GeminiThinkingParams(level, null, null).budgetForLevel())
                    .isSorted();
        }

        @Test
        @DisplayName("an explicit zero budget wins over the level, rather than reading as absent")
        void explicitZeroBudgetWinsOverLevel() {
            assertThat(new GeminiThinkingParams(Level.HIGH, 0, null).budgetForLevel()).isZero();
        }

        @Test
        void explicitBudgetWinsOverLevel() {
            assertThat(new GeminiThinkingParams(Level.HIGH, 1234, null).budgetForLevel()).isEqualTo(1234);
        }

        @Test
        void hasNoBudgetWithoutLevelOrExplicitBudget() {
            assertThat(new GeminiThinkingParams(null, null, true).budgetForLevel()).isNull();
        }
    }
}
