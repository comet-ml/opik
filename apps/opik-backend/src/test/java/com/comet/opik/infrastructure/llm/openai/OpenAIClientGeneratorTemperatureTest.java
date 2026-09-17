package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This generator serves OPEN_AI and, through {@code OpenRouterLlmServiceProvider}, OPEN_ROUTER —
 * whose catalog carries the Claude models that take no sampling params, including the floating
 * {@code claude-*-latest} aliases. The judge path builds its model straight from
 * {@link LlmAsJudgeModelParameters} and never passes through ChatCompletionService, so the gate has
 * to hold here independently or an evaluator rule fails every scoring run with a 400 (OPIK-8386).
 */
@DisplayName("OpenAI/OpenRouter judge path — temperature gating")
class OpenAIClientGeneratorTemperatureTest {

    private ChatRequestParameters judgeParametersFor(String modelName) {
        var generator = new OpenAIClientGenerator(new LlmProviderClientConfig());
        var config = LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl("https://openrouter.ai/api/v1")
                .build();
        var parameters = LlmAsJudgeModelParameters.builder().name(modelName).temperature(0.7).build();

        return generator.newCompletionsApiChatModel(config, parameters).defaultRequestParameters();
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic/claude-opus-4.7", "anthropic/claude-sonnet-5",
            "anthropic/claude-fable-5.1", "~anthropic/claude-opus-latest"})
    void doesNotForwardTemperatureForClaudeModelsThatTakeNone(String modelName) {
        assertThat(judgeParametersFor(modelName).temperature()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic/claude-opus-4.6", "anthropic/claude-sonnet-4.5",
            "anthropic/claude-3.5-sonnet", "gpt-4o"})
    void forwardsTemperatureForModelsThatTakeIt(String modelName) {
        assertThat(judgeParametersFor(modelName).temperature()).isEqualTo(0.7);
    }
}
