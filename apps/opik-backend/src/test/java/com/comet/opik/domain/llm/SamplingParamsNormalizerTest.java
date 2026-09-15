package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingParamsNormalizerTest {

    private static ChatCompletionRequest request(String model, Double temperature, Double topP) {
        return ChatCompletionRequest.builder()
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .build();
    }

    /**
     * The routing name differs per provider, and the rule is the model's, not the provider's:
     * Anthropic's own ids, Bedrock's decorated ids and OpenAI-compatible proxy names all reach
     * the same Claude model, which rejects both parameters together (OPIK-8386).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "anthropic/claude-sonnet-5",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
            "bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0",
            "CLAUDE-HAIKU-4-5-20251001"
    })
    void dropsTopPForClaudeWhateverServesIt(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isNull();
    }

    @Test
    void keepsTopPForClaudeWhenTemperatureIsAbsent() {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request("claude-opus-4-6", null, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void leavesModelsWithoutTheConstraintAlone() {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request("gpt-4o", 0.7, 0.9));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void returnsTheSameRequestWhenNothingNeedsDropping() {
        var original = request("claude-opus-4-6", 0.7, null);

        assertThat(SamplingParamsNormalizer.normalizeRequest(original)).isSameAs(original);
    }

    @Test
    void toleratesARequestWithNoModel() {
        var original = request(null, 0.7, 0.9);

        assertThat(SamplingParamsNormalizer.normalizeRequest(original)).isSameAs(original);
    }
}
