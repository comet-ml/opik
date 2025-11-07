package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.EncryptionUtils;
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

        // SUT
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService);

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

        // SUT
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService);

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

        // SUT
        var llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService);

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
}
