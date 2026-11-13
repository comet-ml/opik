package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.ModelParams;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

class OpenAIOnlyRouteTest {

    private static final String MODEL = "gpt-5.5-pro";
    private static final String REFUSAL = "Model 'gpt-5.5-pro' works only with OpenAI's %s, and this workspace's"
            + " OpenAI provider uses the %s. Set its OpenAI API to %s in the AI provider settings to use this model.";

    private final OpenAILlmServiceProvider onOpenAi = provider(null);
    private final OpenAILlmServiceProvider behindServerWideUrl = provider("https://gateway.example.com/v1");

    private static Stream<Arguments> aModelOnAnotherRouteThanTheKeysIsRefusedBeforeAnyCall() {
        return Stream.of(
                arguments(null, ModelParams.RESPONSES_API_ROUTE, "Responses API", "Chat Completions API"),
                arguments("chat_completions_api", ModelParams.RESPONSES_API_ROUTE, "Responses API",
                        "Chat Completions API"),
                arguments("CHAT_COMPLETIONS_API", ModelParams.RESPONSES_API_ROUTE, "Responses API",
                        "Chat Completions API"),
                arguments("unknown_api", ModelParams.RESPONSES_API_ROUTE, "Responses API", "Chat Completions API"),
                arguments("responses_api", ModelParams.CHAT_COMPLETIONS_API_ROUTE, "Chat Completions API",
                        "Responses API"));
    }

    @ParameterizedTest
    @MethodSource
    void aModelOnAnotherRouteThanTheKeysIsRefusedBeforeAnyCall(String keyMode, String onlyRoute,
            String modelApi, String keyApi) {
        assertThatThrownBy(() -> onOpenAi.requireOnlyRoute(key(keyMode, null), MODEL, onlyRoute))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(REFUSAL.formatted(modelApi, keyApi, modelApi));
    }

    @ParameterizedTest
    @ValueSource(strings = {"responses_api", "Responses_API"})
    void aKeyOnTheModelsRouteIsLetThrough(String keyMode) {
        assertThatCode(() -> onOpenAi.requireOnlyRoute(key(keyMode, null), MODEL, ModelParams.RESPONSES_API_ROUTE))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"responses_api"})
    void aModelWithNoOnlyRouteIsLetThroughOnEitherRoute(String keyMode) {
        assertThatCode(() -> onOpenAi.requireOnlyRoute(key(keyMode, null), "gpt-5.5", null))
                .doesNotThrowAnyException();
    }

    @Test
    void aKeyWithItsOwnBaseUrlIsLetThroughOnItsOwnRoute() {
        assertThatCode(() -> onOpenAi.requireOnlyRoute(key(null, "https://gateway.example.com/v1"), MODEL,
                ModelParams.RESPONSES_API_ROUTE))
                .doesNotThrowAnyException();
    }

    @Test
    void aServerWideBaseUrlIsLetThroughLikeAKeysOwn() {
        assertThatCode(() -> behindServerWideUrl.requireOnlyRoute(key(null, null), MODEL,
                ModelParams.RESPONSES_API_ROUTE))
                .doesNotThrowAnyException();
    }

    private static OpenAILlmServiceProvider provider(String serverWideUrl) {
        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setOpenAiClient(new OpenAiClientConfig(serverWideUrl));
        return new OpenAILlmServiceProvider(new OpenAIClientGenerator(clientConfig,
                LlmTestClients.RESPONSES_API_HTTP_CLIENT, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
                mock(LlmProviderFactory.class));
    }

    private static LlmProviderClientApiConfig key(String mode, String baseUrl) {
        return LlmProviderClientApiConfig.builder()
                .apiKey(UUID.randomUUID().toString())
                .baseUrl(baseUrl)
                .configuration(mode == null ? Map.of() : Map.of("openai_pipeline_mode", mode))
                .build();
    }
}
