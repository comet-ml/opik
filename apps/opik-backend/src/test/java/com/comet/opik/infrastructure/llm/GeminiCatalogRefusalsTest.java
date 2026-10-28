package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.domain.llm.MessageContentNormalizer;
import com.comet.opik.domain.llm.ParamResolver;
import com.comet.opik.domain.llm.ParamResolver.ParamChange;
import com.comet.opik.domain.llm.ParamResolver.ParamChange.Reason;
import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmModule;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a Gemini or Vertex AI model receives once the shipped catalog has shaped the request. The ids sit in a
 * registry of their own, so a sync that adds or retires a model leaves this test alone. Neither provider's client
 * can be pointed at a stub server (Gemini has no base URL, Vertex AI is a gRPC client), so the provider is replaced
 * right where {@link ChatCompletionService} hands it the request; both clients forward what they are given.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeminiCatalogRefusalsTest {

    private static final List<ParamChange> REFUSALS = List.of(
            new ParamChange("temperature", Reason.REFUSED_BY_MODEL),
            new ParamChange("top_p", Reason.REFUSED_BY_MODEL),
            new ParamChange("frequency_penalty", Reason.REFUSED_BY_MODEL),
            new ParamChange("presence_penalty", Reason.REFUSED_BY_MODEL));

    private final List<ChatCompletionRequest> sentRequests = new ArrayList<>();
    private final List<LlmAsJudgeModelParameters> judgeParameters = new ArrayList<>();
    private ParamResolver paramResolver;
    private ChatCompletionService chatCompletionService;

    @BeforeAll
    void setUpAll() {
        var config = TestConfigUtils.loadConfigTest();
        EncryptionUtils.setConfig(config);

        var llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        when(llmProviderApiKeyService.findByProviders(anyString(), anySet())).thenAnswer(invocation -> invocation
                .<Set<LlmProvider>>getArgument(1).stream()
                .map(provider -> ProviderApiKey.builder()
                        .provider(provider)
                        .apiKey(EncryptionUtils.encrypt("test-key"))
                        .build())
                .toList());
        var freeModelConfig = new FreeModelConfig();
        freeModelConfig.setEnabled(false);
        var opikConfiguration = mock(OpikConfiguration.class);
        when(opikConfiguration.getFreeModel()).thenReturn(freeModelConfig);

        var registryConfig = new LlmModelRegistryConfig();
        registryConfig.setDefaultResource("llm-models-test-gemini.yaml");
        var registryService = new LlmModelRegistryService(registryConfig);
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, opikConfiguration,
                registryService, new CustomLlmModule().providerBaseUrlGuard(config.getLlmProviderClient()));
        var recordingProvider = new RecordingProvider();
        llmProviderFactory.register(LlmProvider.GEMINI, recordingProvider);
        llmProviderFactory.register(LlmProvider.VERTEX_AI, recordingProvider);

        paramResolver = new ParamResolver(llmProviderFactory, registryService);
        chatCompletionService = new ChatCompletionService(config.getLlmProviderClient(), llmProviderFactory,
                paramResolver, new ProviderErrorMessageValidator(config.getLlmProviderClient()));
    }

    @BeforeEach
    void setUp() {
        sentRequests.clear();
        judgeParameters.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void chatRequestsLoseWhatTheModelRefuses(String model, boolean takesSampling) {
        var request = request(model);

        chatCompletionService.create(request, "workspace-id");

        var normalized = MessageContentNormalizer.normalizeRequest(request);
        var expected = takesSampling
                ? normalized
                : ChatCompletionRequest.builder().from(normalized)
                        .temperature(null)
                        .topP(null)
                        .frequencyPenalty(null)
                        .presencePenalty(null)
                        .build();
        assertThat(sentRequests).containsExactly(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void judgesLoseTheTemperatureTheModelRefuses(String model, boolean takesSampling) {
        var parameters = LlmAsJudgeModelParameters.builder().name(model).temperature(0.3).build();

        chatCompletionService.scoreTrace(ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from("score"))
                .build(), parameters, "workspace-id");

        assertThat(judgeParameters).containsExactly(takesSampling
                ? parameters
                : parameters.toBuilder().temperature(null).build());
    }

    @Test
    void theChangeListNamesEachRefusedSettingForAModelWithoutACuratedRow() {
        assertThat(paramResolver.resolve(request("gemini-4-argon"), null).changes()).isEqualTo(REFUSALS);
        assertThat(paramResolver.resolve(request("vertex_ai/gemini-3.8-flash-cyber"), null).changes())
                .isEqualTo(REFUSALS);
    }

    private static Stream<Arguments> models() {
        return Stream.of(
                arguments("gemini-1.5-pro-latest", true),
                arguments("gemini-2.0-flash", true),
                arguments("gemini-2.5-pro", true),
                arguments("gemini-2.5-flash-preview-09-2025", true),
                arguments("gemini-pro-vision", true),
                arguments("gemma-4-31b-it", true),
                arguments("gemma-5-9b-it", true),
                arguments("gemini-3.1-pro-preview", false),
                arguments("gemini-3.8-flash", false),
                arguments("gemini-4-argon", false),
                arguments("gemini-5-pro", false),
                arguments("vertex_ai/gemini-2.0-flash-001", true),
                arguments("vertex_ai/gemini-2.5-pro", true),
                arguments("vertex_ai/gemini-2.5-flash-preview-09-2025", true),
                arguments("vertex_ai/gemini-3.8-flash", false),
                arguments("vertex_ai/gemini-3.8-flash-cyber", false),
                arguments("vertex_ai/gemini-5-pro", false));
    }

    private static ChatCompletionRequest request(String model) {
        return ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(UserMessage.from("hi")))
                .temperature(0.5)
                .topP(0.9)
                .frequencyPenalty(0.1)
                .presencePenalty(0.2)
                .seed(7)
                .stop(List.of("END"))
                .maxCompletionTokens(100)
                .build();
    }

    private class RecordingProvider implements LlmServiceProvider {

        @Override
        public LlmProviderService getService(LlmProviderClientApiConfig config) {
            return new LlmProviderService() {
                @Override
                public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request,
                        @NonNull String workspaceId) {
                    sentRequests.add(request);
                    return ChatCompletionResponse.builder().build();
                }

                @Override
                public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
                        @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
                        @NonNull Consumer<Throwable> handleError) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void validateRequest(@NonNull ChatCompletionRequest request) {
                }

                @Override
                public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
                    return Optional.empty();
                }
            };
        }

        @Override
        public ChatModel getLanguageModel(LlmProviderClientApiConfig config,
                LlmAsJudgeModelParameters modelParameters) {
            judgeParameters.add(modelParameters);
            var chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
            return chatModel;
        }
    }
}
