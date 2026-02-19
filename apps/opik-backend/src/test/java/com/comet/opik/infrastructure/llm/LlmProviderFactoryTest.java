package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.antropic.AnthropicClientGenerator;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModule;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmClientGenerator;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmModule;
import com.comet.opik.infrastructure.llm.gemini.GeminiClientGenerator;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModule;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.comet.opik.infrastructure.llm.openai.OpenAIModule;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModule;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIClientGenerator;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.configuration.ConfigurationException;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import jakarta.validation.Validator;
import jakarta.ws.rs.BadRequestException;
import lombok.SneakyThrows;
import org.apache.commons.lang3.EnumUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmProviderFactoryTest {
    private LlmProviderClientConfig llmProviderClientConfig;
    private OpikConfiguration opikConfiguration;

    private static final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private static final Validator validator = Validators.newValidator();
    private static final YamlConfigurationFactory<OpikConfiguration> factory = new YamlConfigurationFactory<>(
            OpikConfiguration.class, validator, objectMapper, "dw");

    @BeforeAll
    void setUpAll() throws ConfigurationException, IOException {
        final OpikConfiguration config = factory.build(new FileConfigurationSourceProvider(),
                "src/test/resources/config-test.yml");
        EncryptionUtils.setConfig(config);
        llmProviderClientConfig = config.getLlmProviderClient();
        opikConfiguration = config;
    }

    private OpikConfiguration createMockConfigWithFreeModel(boolean enabled, String actualModel,
            String spanProvider) {
        OpikConfiguration mockConfig = mock(OpikConfiguration.class);
        FreeModelConfig freeModelConfig = new FreeModelConfig();
        freeModelConfig.setEnabled(enabled);
        freeModelConfig.setActualModel(actualModel);
        freeModelConfig.setSpanProvider(spanProvider);
        freeModelConfig.setBaseUrl("https://test-endpoint.example.com");
        freeModelConfig.setApiKey("test-api-key");
        when(mockConfig.getFreeModel()).thenReturn(freeModelConfig);
        return mockConfig;
    }

    @SneakyThrows
    @ParameterizedTest
    @MethodSource
    void testGetService(String model, LlmProvider llmProvider, String providerClass) {
        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        String workspaceId = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();

        when(llmProviderApiKeyService.find(workspaceId)).thenReturn(ProviderApiKey.ProviderApiKeyPage.builder()
                .content(List.of(ProviderApiKey.builder()
                        .provider(llmProvider)
                        .apiKey(EncryptionUtils.encrypt(apiKey))
                        .build()))
                .total(1)
                .page(1)
                .size(1)
                .build());

        // SUT - use config with disabled free model to not interfere with other tests
        var mockConfig = createMockConfigWithFreeModel(false, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        AnthropicModule anthropicModule = new AnthropicModule();
        GeminiModule geminiModule = new GeminiModule();
        OpenAIModule openAIModule = new OpenAIModule();
        OpenRouterModule openRouterModule = new OpenRouterModule();
        VertexAIModule vertexAIModule = new VertexAIModule();

        AnthropicClientGenerator anthropicClientGenerator = anthropicModule.clientGenerator(llmProviderClientConfig);
        anthropicModule.llmServiceProvider(llmProviderFactory, anthropicClientGenerator);

        GeminiClientGenerator geminiClientGenerator = geminiModule.clientGenerator(llmProviderClientConfig);
        geminiModule.llmServiceProvider(llmProviderFactory, geminiClientGenerator);

        OpenAIClientGenerator openAIClientGenerator = openAIModule.clientGenerator(llmProviderClientConfig);
        openAIModule.llmServiceProvider(llmProviderFactory, openAIClientGenerator);

        OpenAIClientGenerator openRouterClientGenerator = openRouterModule.clientGenerator(llmProviderClientConfig);
        openRouterModule.llmServiceProvider(llmProviderFactory, openRouterClientGenerator);

        VertexAIClientGenerator vertexAIClientGenerator = vertexAIModule.clientGenerator(llmProviderClientConfig);
        vertexAIModule.llmServiceProvider(llmProviderFactory, vertexAIClientGenerator);

        LlmProviderService actual = llmProviderFactory.getService(workspaceId, model);

        // assertions
        assertThat(actual.getClass().getSimpleName()).isEqualTo(providerClass);
    }

    private static Stream<Arguments> testGetService() {
        var openAiModels = EnumUtils.getEnumList(OpenaiModelName.class).stream()
                .map(model -> arguments(model.toString(), LlmProvider.OPEN_AI, "LlmProviderOpenAi"));
        var anthropicModels = EnumUtils.getEnumList(AnthropicModelName.class).stream()
                .map(model -> arguments(model.toString(), LlmProvider.ANTHROPIC, "LlmProviderAnthropic"));
        var geminiModels = EnumUtils.getEnumList(GeminiModelName.class).stream()
                .map(model -> arguments(model.toString(), LlmProvider.GEMINI, "LlmProviderGemini"));
        var openRouterModels = EnumUtils.getEnumList(OpenRouterModelName.class).stream()
                .map(model -> arguments(model.toString(), LlmProvider.OPEN_ROUTER, "LlmProviderOpenAi"));
        var vertexAiModels = EnumUtils.getEnumList(VertexAIModelName.class).stream()
                .map(model -> arguments(model.qualifiedName(), LlmProvider.VERTEX_AI, "LlmProviderVertexAI"));

        return Stream.of(openAiModels, anthropicModels, geminiModels, openRouterModels, vertexAiModels)
                .flatMap(Function.identity());
    }

    /**
     * Comprehensive test for custom LLM provider model matching.
     * Tests both the legacy format (provider_name = null) and new format (provider_name set).
     */
    @SneakyThrows
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void testCustomLlmProviderMatching_shouldMatch(
            String testName,
            String modelToRequest,
            String providerName,
            String configuredModels) {

        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        String workspaceId = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();

        when(llmProviderApiKeyService.find(workspaceId)).thenReturn(ProviderApiKey.ProviderApiKeyPage.builder()
                .content(List.of(ProviderApiKey.builder()
                        .provider(LlmProvider.CUSTOM_LLM)
                        .providerName(providerName)
                        .apiKey(EncryptionUtils.encrypt(apiKey))
                        .baseUrl("http://localhost:11434/v1")
                        .configuration(Map.of("models", configuredModels))
                        .build()))
                .total(1)
                .page(1)
                .size(1)
                .build());

        // SUT - use config with disabled free model
        var mockConfig = createMockConfigWithFreeModel(false, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // Register custom LLM service (required for getService to work)
        CustomLlmModule customLlmModule = new CustomLlmModule();
        CustomLlmClientGenerator customLlmClientGenerator = customLlmModule.clientGenerator(llmProviderClientConfig);
        customLlmModule.llmServiceProvider(llmProviderFactory, customLlmClientGenerator);

        // When & Then - Should successfully get the service
        LlmProviderService actual = llmProviderFactory.getService(workspaceId, modelToRequest);
        assertThat(actual).isNotNull();
    }

    @SneakyThrows
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void testCustomLlmProviderMatching_shouldNotMatch(
            String testName,
            String modelToRequest,
            String providerName,
            String configuredModels) {

        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        String workspaceId = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();

        when(llmProviderApiKeyService.find(workspaceId)).thenReturn(ProviderApiKey.ProviderApiKeyPage.builder()
                .content(List.of(ProviderApiKey.builder()
                        .provider(LlmProvider.CUSTOM_LLM)
                        .providerName(providerName)
                        .apiKey(EncryptionUtils.encrypt(apiKey))
                        .baseUrl("http://localhost:11434/v1")
                        .configuration(Map.of("models", configuredModels))
                        .build()))
                .total(1)
                .page(1)
                .size(1)
                .build());

        // SUT - use config with disabled free model
        var mockConfig = createMockConfigWithFreeModel(false, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // Register custom LLM service (required for getService to work)
        CustomLlmModule customLlmModule = new CustomLlmModule();
        CustomLlmClientGenerator customLlmClientGenerator = customLlmModule.clientGenerator(llmProviderClientConfig);
        customLlmModule.llmServiceProvider(llmProviderFactory, customLlmClientGenerator);

        // When & Then - Should throw BadRequestException
        assertThatThrownBy(() -> llmProviderFactory.getService(workspaceId, modelToRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("API key not configured for LLM");
    }

    private static Stream<Arguments> testCustomLlmProviderMatching_shouldMatch() {
        return Stream.of(
                // Legacy format tests (provider_name = null)
                // Configured models include "custom-llm/" prefix (as stored in DB)
                arguments(
                        "Legacy: simple model name",
                        "custom-llm/llama-3.2",
                        null,
                        "custom-llm/llama-3.2"),
                arguments(
                        "Legacy: model with slash (e.g., Hugging Face format)",
                        "custom-llm/mistralai/Mistral-7B-Instruct-v0.3",
                        null,
                        "custom-llm/mistralai/Mistral-7B-Instruct-v0.3"),
                arguments(
                        "Legacy: model with multiple slashes",
                        "custom-llm/org/repo/model-v1",
                        null,
                        "custom-llm/org/repo/model-v1"),
                arguments(
                        "Legacy: multiple configured models (first match)",
                        "custom-llm/llama-3.2",
                        null,
                        "custom-llm/llama-3.2, custom-llm/mistralai/Mistral-7B-Instruct-v0.3, custom-llm/gpt-4"),
                arguments(
                        "Legacy: multiple configured models (middle match)",
                        "custom-llm/mistralai/Mistral-7B-Instruct-v0.3",
                        null,
                        "custom-llm/llama-3.2, custom-llm/mistralai/Mistral-7B-Instruct-v0.3, custom-llm/gpt-4"),
                arguments(
                        "Legacy: whitespace in configured models",
                        "custom-llm/llama-3.2",
                        null,
                        "  custom-llm/llama-3.2  ,  custom-llm/gpt-4  "),

                // New format tests (provider_name set)
                // Configured models must match the full model string including provider name
                arguments(
                        "New: simple model name",
                        "custom-llm/ollama/llama-3.2",
                        "ollama",
                        "custom-llm/ollama/llama-3.2"),
                arguments(
                        "New: model with slash",
                        "custom-llm/vllm/mistralai/Mistral-7B-Instruct-v0.3",
                        "vllm",
                        "custom-llm/vllm/mistralai/Mistral-7B-Instruct-v0.3"),
                arguments(
                        "New: model with multiple slashes",
                        "custom-llm/vllm/org/repo/model-v1",
                        "vllm",
                        "custom-llm/vllm/org/repo/model-v1"),
                arguments(
                        "New: multiple configured models",
                        "custom-llm/ollama/llama-3.2",
                        "ollama",
                        "custom-llm/ollama/llama-3.2, custom-llm/ollama/mistralai/Mistral-7B-Instruct-v0.3, custom-llm/ollama/gpt-4"));
    }

    private static Stream<Arguments> testCustomLlmProviderMatching_shouldNotMatch() {
        return Stream.of(
                // Legacy format - no match scenarios
                arguments(
                        "Legacy: model not in configured list",
                        "custom-llm/unknown-model",
                        null,
                        "custom-llm/llama-3.2, custom-llm/mistralai/Mistral-7B-Instruct-v0.3"),
                arguments(
                        "Legacy: empty configured models",
                        "custom-llm/llama-3.2",
                        null,
                        ""),

                // New format - no match scenarios
                arguments(
                        "New: model not in configured list",
                        "custom-llm/ollama/unknown-model",
                        "ollama",
                        "custom-llm/llama-3.2, custom-llm/mistralai/Mistral-7B-Instruct-v0.3"),
                arguments(
                        "New: wrong provider name",
                        "custom-llm/ollama/llama-3.2",
                        "vllm",
                        "custom-llm/llama-3.2"),
                arguments(
                        "New: empty configured models",
                        "custom-llm/ollama/llama-3.2",
                        "ollama",
                        ""));
    }

    // ========== OPIK_DEFAULT Provider Tests ==========

    @SneakyThrows
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getLlmProvider returns OPIK_FREE when model matches and provider is enabled")
    void testGetLlmProvider_returnsOpikFree_whenModelMatchesAndEnabled() {
        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);

        // SUT - config with enabled free model
        var mockConfig = createMockConfigWithFreeModel(true, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // When - use the hardcoded model constant
        LlmProvider result = llmProviderFactory.getLlmProvider(FreeModelConfig.FREE_MODEL);

        // Then
        assertThat(result).isEqualTo(LlmProvider.OPIK_FREE);
    }

    @SneakyThrows
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getLlmProvider throws exception when model matches but provider is disabled")
    void testGetLlmProvider_throwsException_whenModelMatchesButDisabled() {
        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);

        // SUT - config with disabled built-in provider
        var mockConfig = createMockConfigWithFreeModel(false, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // When & Then - Should throw because model is not recognized when disabled
        assertThatThrownBy(() -> llmProviderFactory.getLlmProvider(FreeModelConfig.FREE_MODEL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not supported");
    }

    @SneakyThrows
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getResolvedModelInfo returns actual model and provider for OPIK_FREE")
    void testGetResolvedModelInfo_returnsActualModelAndProvider_forOpikFree() {
        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        String actualModel = "gpt-4o-mini";
        String spanProvider = "openai";

        // SUT - config with enabled free model
        var mockConfig = createMockConfigWithFreeModel(true, actualModel, spanProvider);
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // When - use the hardcoded model constant
        LlmProviderFactory.ResolvedModelInfo result = llmProviderFactory
                .getResolvedModelInfo(FreeModelConfig.FREE_MODEL);

        // Then
        assertThat(result.actualModel()).isEqualTo(actualModel);
        assertThat(result.provider()).isEqualTo(spanProvider);
    }

    @SneakyThrows
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getResolvedModelInfo returns original model and provider for non-OPIK_FREE")
    void testGetResolvedModelInfo_returnsOriginalModelAndProvider_forOtherProviders() {
        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        String openaiModel = "gpt-4o";

        // SUT - config with enabled free model (should not interfere with OpenAI models)
        var mockConfig = createMockConfigWithFreeModel(true, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // When
        LlmProviderFactory.ResolvedModelInfo result = llmProviderFactory.getResolvedModelInfo(openaiModel);

        // Then - should return original model and provider type
        assertThat(result.actualModel()).isEqualTo(openaiModel);
        assertThat(result.provider()).isEqualTo(LlmProvider.OPEN_AI.getValue());
    }

    @SneakyThrows
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getLlmProvider returns OPEN_ROUTER for openrouter route slugs")
    void testGetLlmProvider_returnsOpenRouter_forOpenRouterRouteSlug() {
        // setup
        LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        var mockConfig = createMockConfigWithFreeModel(false, "gpt-4o-mini", "openai");
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, mockConfig);

        // When
        LlmProvider result = llmProviderFactory.getLlmProvider("openrouter/some-future-router");

        // Then
        assertThat(result).isEqualTo(LlmProvider.OPEN_ROUTER);
    }
}
