package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.antropic.AnthropicClientGenerator;
import com.comet.opik.infrastructure.llm.customllm.AuthTokenProvider;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmClientGenerator;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.comet.opik.infrastructure.llm.freemodel.FreeModelServiceProvider;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * A judge's chat model retries only as configured: {@code ChatCompletionService.scoreTrace} retries each judge call and
 * the scoring stream redelivers it, so a client's own retries multiply the attempts against a provider that hangs.
 * Gemini takes no base URL from the provider configuration, so it can't be pointed at the mock; Vertex AI is covered in
 * {@code VertexAIClientGeneratorTest}, which has its credentials and endpoint set up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeClientRetriesTest {

    private static final LlmAsJudgeModelParameters JUDGE = LlmAsJudgeModelParameters.builder().name("judge-model")
            .build();

    private final WireMockServer provider = LlmTestClients.newLoopbackWireMock();

    @BeforeAll
    void startProvider() {
        provider.start();
    }

    @AfterAll
    void stopProvider() {
        provider.stop();
    }

    @BeforeEach
    void answerEveryCallWithAServerError() {
        provider.resetAll();
        provider.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"unavailable\",\"type\":\"server_error\"}}")));
    }

    Stream<Arguments> judgeChatModels() {
        return Stream.of(0, 1).flatMap(this::judgeChatModels);
    }

    private Stream<Arguments> judgeChatModels(int judgeMaxRetries) {
        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setJudgeMaxRetries(judgeMaxRetries);
        var openAi = new OpenAIClientGenerator(clientConfig, LlmTestClients.RESPONSES_API_HTTP_CLIENT,
                LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        var customLlm = new CustomLlmClientGenerator(clientConfig, mock(ProviderBaseUrlGuard.class),
                mock(AuthTokenProvider.class), LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        var anthropic = new AnthropicClientGenerator(clientConfig, bundledRegistry(),
                LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        var freeModelConfig = new FreeModelConfig();
        freeModelConfig.setEnabled(true);
        var freeModel = new FreeModelServiceProvider(openAi, mock(LlmProviderFactory.class), freeModelConfig,
                clientConfig, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        return Stream.of(
                Arguments.of("OpenAI chat completions", judgeMaxRetries, (Function<String, ChatModel>) baseUrl -> openAi
                        .generateChat(config(baseUrl + "/v1", Map.of()), JUDGE)),
                Arguments.of("OpenAI Responses API", judgeMaxRetries, (Function<String, ChatModel>) baseUrl -> openAi
                        .generateChat(config(baseUrl + "/v1", Map.of("openai_pipeline_mode", "responses_api")),
                                JUDGE)),
                Arguments.of("custom LLM", judgeMaxRetries, (Function<String, ChatModel>) baseUrl -> customLlm
                        .generateChat(config(baseUrl + "/v1", Map.of()),
                                LlmAsJudgeModelParameters.builder().name("custom-llm/judge-model").build())),
                Arguments.of("Anthropic", judgeMaxRetries, (Function<String, ChatModel>) baseUrl -> anthropic
                        .generateChat(config(baseUrl + "/v1/", Map.of()), JUDGE)),
                Arguments.of("Opik free model", judgeMaxRetries, (Function<String, ChatModel>) baseUrl -> freeModel
                        .getLanguageModel(config(baseUrl + "/v1", Map.of()),
                                LlmAsJudgeModelParameters.builder().name(FreeModelConfig.FREE_MODEL).build())));
    }

    @ParameterizedTest(name = "{0}, {1} retries")
    @MethodSource("judgeChatModels")
    void aJudgeCallReachesTheProviderOncePlusItsRetries(String providerName, int judgeMaxRetries,
            Function<String, ChatModel> judgeFor) {
        var judge = judgeFor.apply(LlmTestClients.baseUrlOf(provider));

        var failure = catchThrowable(() -> judge.chat(ChatRequest.builder()
                .messages(UserMessage.from("Is the answer correct?"))
                .build()));

        assertThat(failure).isNotNull();
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(1 + judgeMaxRetries);
    }

    private static LlmProviderClientApiConfig config(String baseUrl, Map<String, String> configuration) {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .configuration(configuration)
                .build();
    }
}
