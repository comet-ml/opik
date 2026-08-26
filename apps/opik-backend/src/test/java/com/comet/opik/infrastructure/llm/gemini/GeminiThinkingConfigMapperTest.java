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

    @ParameterizedTest
    @CsvSource({"MINIMAL,minimal", "LOW,low", "MEDIUM,medium", "HIGH,high"})
    @DisplayName("a level is sent as a level, since AI Studio accepts one directly")
    void forwardsLevelAsLevel(Level level, String expectedWireValue) {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(new GeminiThinkingParams(level, null, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingLevel()).isEqualTo(expectedWireValue);
        assertThat(config.get().thinkingBudget()).isNull();
    }

    @Test
    @DisplayName("level off becomes a zero budget, as the API has no off level")
    void mapsLevelOffToZeroBudget() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(new GeminiThinkingParams(Level.OFF, null, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingBudget()).isZero();
        assertThat(config.get().thinkingLevel()).isNull();
    }

    @Test
    void forwardsExplicitBudgetAndIncludeThoughts() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(new GeminiThinkingParams(null, 4096, true));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingBudget()).isEqualTo(4096);
        assertThat(config.get().includeThoughts()).isTrue();
    }

    @Test
    @DisplayName("level and budget can be sent together, unlike on Vertex where only the budget exists")
    void forwardsLevelAndBudgetTogether() {
        var config = GeminiThinkingConfigMapper.toThinkingConfig(new GeminiThinkingParams(Level.LOW, 1024, null));

        assertThat(config).isPresent();
        assertThat(config.get().thinkingLevel()).isEqualTo("low");
        assertThat(config.get().thinkingBudget()).isEqualTo(1024);
    }

    @Test
    void producesNoConfigWhenThinkingIsAbsent() {
        assertThat(GeminiThinkingConfigMapper.toThinkingConfig(GeminiThinkingParams.ABSENT)).isEmpty();
    }

    @Test
    @DisplayName("the playground entry point decodes straight from custom parameters")
    void decodesFromPlaygroundCustomParameters() {
        var config = GeminiThinkingConfigMapper.fromCustomParameters(
                Map.of("thinking", Map.of("level", "high")));

        assertThat(config).isNotNull();
        assertThat(config.thinkingLevel()).isEqualTo("high");
    }

    @Test
    void returnsNullForPlaygroundRequestsWithoutThinking() {
        assertThat(GeminiThinkingConfigMapper.fromCustomParameters(Map.of())).isNull();
        assertThat(GeminiThinkingConfigMapper.fromCustomParameters(null)).isNull();
    }

    @Test
    @DisplayName("the judge shape decodes to the same config as the playground shape")
    void judgeAndPlaygroundShapesAgree() {
        var fromJson = GeminiThinkingConfigMapper.toThinkingConfig(GeminiThinkingParams.from(
                JsonUtils.getJsonNodeFromString("{\"thinking\": {\"level\": \"medium\", \"budget_tokens\": 777}}")));
        var fromMap = GeminiThinkingConfigMapper.toThinkingConfig(GeminiThinkingParams.from(
                Map.of("thinking", Map.of("level", "medium", "budget_tokens", 777))));

        assertThat(fromJson).isPresent().isEqualTo(fromMap);
    }
}
