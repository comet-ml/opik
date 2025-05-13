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
import lombok.SneakyThrows;
import org.apache.commons.lang3.EnumUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
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
}
