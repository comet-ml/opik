package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.ParamResolver;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * This generator serves CUSTOM_LLM and BEDROCK, the routes by which a Claude model that takes no sampling params
 * arrives without the Anthropic provider (OPIK-8386). The judge parameters go through {@link ParamResolver} first,
 * as {@code ChatCompletionService.scoreTrace} does, so this checks the temperature the judge ends up sending.
 */
@DisplayName("Custom LLM judge path — temperature gating")
class CustomLlmClientGeneratorTemperatureTest {

    private static final String PROVIDER_NAME = "gw";
    private static final ParamResolver RESOLVER = new ParamResolver(mock(LlmProviderFactory.class), bundledRegistry());

    private ChatRequestParameters judgeParametersFor(String modelName, Double temperature) {
        var generator = new CustomLlmClientGenerator(new LlmProviderClientConfig(), mock(ProviderBaseUrlGuard.class),
                mock(AuthTokenProvider.class), new OkHttpClient());
        var config = LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl("https://gateway.example.com/v1")
                .configuration(Map.of("provider_name", PROVIDER_NAME))
                .build();
        var parameters = LlmAsJudgeModelParameters.builder()
                .name("custom-llm/" + PROVIDER_NAME + "/" + modelName)
                .temperature(temperature)
                .build();

        return generator.generateChat(config, RESOLVER.resolve(parameters).request()).defaultRequestParameters();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-5", "claude-opus-4-7", "claude-opus-5", "claude-fable-5-1"})
    void doesNotForwardTemperatureForClaudeModelsThatTakeNone(String modelName) {
        assertThat(judgeParametersFor(modelName, 0.7).temperature()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5-20251001"})
    void forwardsTemperatureForClaudeModelsThatTakeIt(String modelName) {
        assertThat(judgeParametersFor(modelName, 0.7).temperature()).isEqualTo(0.7);
    }

    @Test
    void forwardsTemperatureForAModelItCannotPlace() {
        // Permissive for names we do not recognise: a proxy may be serving a capable model of its own.
        assertThat(judgeParametersFor("my-own-deployment", 0.7).temperature()).isEqualTo(0.7);
    }

    @Test
    void forwardsTemperatureForANonClaudeModel() {
        assertThat(judgeParametersFor("mistral-large-2411", 0.7).temperature()).isEqualTo(0.7);
    }
}
