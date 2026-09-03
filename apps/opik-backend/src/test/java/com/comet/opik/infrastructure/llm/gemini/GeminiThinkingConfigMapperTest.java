package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.comet.opik.infrastructure.llm.GeminiThinkingParams.Level;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Gemini thinking config mapper")
class GeminiThinkingConfigMapperTest {

    private static final String GEMINI_3 = "gemini-3-flash-preview";
    private static final String GEMINI_2_5 = "gemini-2.5-flash";

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
