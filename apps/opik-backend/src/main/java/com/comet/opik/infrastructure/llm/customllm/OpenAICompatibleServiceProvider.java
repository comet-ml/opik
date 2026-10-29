package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.antropic.AnthropicClientGenerator;
import com.comet.opik.infrastructure.llm.antropic.LlmProviderAnthropic;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Named;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service provider for OpenAI-compatible LLM providers.
 * This includes both Custom LLM and Bedrock providers, which share the same
 * OpenAI-compatible API format and client implementation. A Bedrock provider set to
 * {@link BedrockApi#ANTHROPIC_MESSAGES} goes through the Anthropic client instead.
 */
class OpenAICompatibleServiceProvider implements LlmServiceProvider {

    private static final String ANTHROPIC_API_KEY_HEADER = "x-api-key";

    // Never sent: in token mode the fetched token replaces the x-api-key header that carries it.
    private static final String TOKEN_MODE_API_KEY = "replaced-by-fetched-token";

    private final CustomLlmClientGenerator clientGenerator;
    private final AnthropicClientGenerator anthropicClientGenerator;
    private final LlmModelRegistryService registryService;

    OpenAICompatibleServiceProvider(
            @Named("customLlmGenerator") CustomLlmClientGenerator clientGenerator,
            @NonNull AnthropicClientGenerator anthropicClientGenerator,
            @NonNull LlmModelRegistryService registryService,
            LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        this.anthropicClientGenerator = anthropicClientGenerator;
        this.registryService = registryService;
        factory.register(LlmProvider.CUSTOM_LLM, this);
        factory.register(LlmProvider.BEDROCK, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig config) {
        if (BedrockApi.of(config) == BedrockApi.ANTHROPIC_MESSAGES) {
            var messagesConfig = toAnthropicMessagesConfig(config);
            var client = anthropicClientGenerator.newClient(messagesConfig,
                    clientGenerator.newHttpClientBuilder(messagesConfig));
            return new BedrockAnthropicProvider(new LlmProviderAnthropic(client, registryService),
                    providerName(config));
        }
        // Ollama keys land here as well, not in OllamaServiceProvider: all three store their models under the
        // custom-llm/ prefix, so the factory routes them as CUSTOM_LLM and only the key's own type tells them apart.
        return new CustomLlmProvider(
                clientGenerator.newCustomLlmClient(config), config.configuration(), config.provider());
    }

    @Override
    public ChatModel getLanguageModel(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        if (BedrockApi.of(config) == BedrockApi.ANTHROPIC_MESSAGES) {
            var messagesConfig = toAnthropicMessagesConfig(config);
            var bedrockModelParameters = CustomLlmModelNameChecker.isCustomLlmModel(modelParameters.name())
                    ? modelParameters.toBuilder()
                            .name(CustomLlmModelNameChecker.extractModelName(modelParameters.name(),
                                    providerName(config)))
                            .build()
                    : modelParameters;
            return anthropicClientGenerator.newChatModel(messagesConfig, bedrockModelParameters,
                    clientGenerator.newHttpClientBuilder(messagesConfig));
        }
        return clientGenerator.generateChat(config, modelParameters);
    }

    private static LlmProviderClientApiConfig toAnthropicMessagesConfig(LlmProviderClientApiConfig config) {
        if (StringUtils.isBlank(config.baseUrl())) {
            throw new IllegalArgumentException("custom provider client not configured properly, missing url");
        }
        var builder = config.toBuilder().baseUrl(BedrockApi.messagesBaseUrl(config.baseUrl()));
        if (config.authConfig() == null) {
            return builder.build();
        }
        var configuration = new HashMap<>(Optional.ofNullable(config.configuration()).orElse(Map.of()));
        if (StringUtils.isBlank(configuration.get(InterceptingHttpClient.AUTH_HEADER_NAME_CONFIG_KEY))) {
            configuration.put(InterceptingHttpClient.AUTH_HEADER_NAME_CONFIG_KEY, ANTHROPIC_API_KEY_HEADER);
        }
        return builder.configuration(configuration).apiKey(TOKEN_MODE_API_KEY).build();
    }

    private static String providerName(LlmProviderClientApiConfig config) {
        return Optional.ofNullable(config.configuration())
                .map(configuration -> configuration.get("provider_name"))
                .orElse(null);
    }
}
