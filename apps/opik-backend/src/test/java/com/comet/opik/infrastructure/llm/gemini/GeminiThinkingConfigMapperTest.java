package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static com.comet.opik.infrastructure.llm.GeminiThinkingParams.Level;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Gemini thinking config mapper")
class GeminiThinkingConfigMapperTest {

    private static final String GEMINI_3 = "gemini-3-flash-preview";
    private static final String GEMINI_2_5 = "gemini-2.5-flash";

    @ParameterizedTest
    @CsvSource({"MINIMAL,minimal", "LOW,low", "MEDIUM,medium", "HIGH,high"})
    @DisplayName("a level is sent as a level, since AI Studio accepts one directly")
    void forwardsLevelAsLevel(Level level, String expectedWireValue) {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3, new GeminiThinkingParams(level, null, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingLevel()).isEqualTo(expectedWireValue);
        assertThat(config.get().thinkingBudget()).isNull();
    }

    @Test
    @DisplayName("level off sends nothing on Gemini 3+, which cannot disable thinking or take a budget")
    void ignoresLevelOffOnGemini3() {
        assertThat(GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3,
                new GeminiThinkingParams(Level.OFF, null, null))).isEmpty();
    }

    @Test
    @DisplayName("a model name with an absurd version does not blow up the version check")
    void toleratesAbsurdModelVersion() {
        assertThat(GeminiThinkingParams.modelAcceptsLevel("gemini-99999999999-flash")).isFalse();
    }

    @Test
    @DisplayName("an explicit budget wins over level off, matching how Vertex resolves the same input")
    void explicitBudgetWinsOverLevelOff() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_2_5,
                new GeminiThinkingParams(Level.OFF, 4096, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingBudget()).isEqualTo(4096);
    }

    @Test
    void forwardsExplicitBudgetAndIncludeThoughts() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3, new GeminiThinkingParams(null, 4096, true));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingBudget()).isEqualTo(4096);
        assertThat(config.get().includeThoughts()).isTrue();
    }

    @Test
    @DisplayName("level and budget are never sent together: Google rejects that pairing with a 400")
    void sendsOnlyTheLevelWhenBothAreGiven() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3,
                new GeminiThinkingParams(Level.LOW, 1024, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingLevel()).isEqualTo("low");
        assertThat(config.get().thinkingBudget()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"MINIMAL,512", "LOW,2048", "MEDIUM,8192", "HIGH,24576"})
    @DisplayName("a Gemini 2.5 level becomes a budget: thinking_level is Gemini 3+ only and 2.5 rejects it")
    void translatesLevelToBudgetForGemini25(Level level, int expectedBudget) {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_2_5,
                new GeminiThinkingParams(level, null, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingLevel()).isNull();
        assertThat(config.get().thinkingBudget()).isEqualTo(expectedBudget);
    }

    @Test
    @DisplayName("level off is a zero budget on 2.5 as well")
    void mapsLevelOffToZeroBudgetForGemini25() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_2_5,
                new GeminiThinkingParams(Level.OFF, null, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingBudget()).isZero();
        assertThat(config.get().thinkingLevel()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "gemini-3-flash-preview,true",
            "gemini-3.7-flash,true",
            "gemini-3.1-pro-preview,true",
            "vertex_ai/gemini-3.5-flash,true",
            "gemini-2.5-flash,false",
            "gemini-2.5-pro,false",
            "vertex_ai/gemini-2.5-flash-lite-preview-06-17,false",
            "gemini-2.0-flash,false"})
    @DisplayName("only Gemini 3 and later take a level on the wire")
    void recognisesWhichModelsAcceptALevel(String model, boolean acceptsLevel) {
        assertThat(GeminiThinkingParams.modelAcceptsLevel(model)).isEqualTo(acceptsLevel);
    }

    @Test
    @DisplayName("an unknown model falls back to a budget rather than risking a rejected level")
    void unknownModelFallsBackToBudget() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(null,
                new GeminiThinkingParams(Level.HIGH, null, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingLevel()).isNull();
        assertThat(config.get().thinkingBudget()).isEqualTo(24576);
    }

    @Test
    @DisplayName("an auto level sends no thinking config: absence is how the model's own default is asked for")
    void autoLevelSendsNoThinkingConfig() {
        assertThat(GeminiThinkingConfigMapper.fromCustomParameters(GEMINI_2_5,
                Map.of("thinking", Map.of("level", "auto")))).isNull();
    }

    @Test
    void producesNoConfigWhenThinkingIsAbsent() {
        assertThat(GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3, GeminiThinkingParams.ABSENT)).isEmpty();
    }

    @Test
    @DisplayName("the playground entry point decodes straight from custom parameters")
    void decodesFromPlaygroundCustomParameters() {
        var config = GeminiThinkingConfigMapper.fromCustomParameters(GEMINI_3,
                Map.of("thinking", Map.of("level", "high")));

        assertThat(config).isNotNull();
        assertThat(config.thinkingLevel()).isEqualTo("high");
    }

    @Test
    void returnsNullForPlaygroundRequestsWithoutThinking() {
        assertThat(GeminiThinkingConfigMapper.fromCustomParameters(GEMINI_3, Map.of())).isNull();
        assertThat(GeminiThinkingConfigMapper.fromCustomParameters(GEMINI_3, null)).isNull();
    }

    @Test
    @DisplayName("the judge shape decodes to the same config as the playground shape")
    void judgeAndPlaygroundShapesAgree() {
        var fromJson = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3, GeminiThinkingParams.from(
                JsonUtils.getJsonNodeFromString("{\"thinking\": {\"level\": \"medium\", \"budget_tokens\": 777}}")));
        var fromMap = GeminiThinkingConfigMapper.toThinkingConfig(GEMINI_3, GeminiThinkingParams.from(
                Map.of("thinking", Map.of("level", "medium", "budget_tokens", 777))));

        assertThat(fromJson).isPresent().isEqualTo(fromMap);
    }
}
