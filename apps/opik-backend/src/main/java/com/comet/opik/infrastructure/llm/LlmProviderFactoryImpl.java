package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmModelNameChecker;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.comet.opik.infrastructure.EncryptionUtils.decrypt;

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LlmProviderFactoryImpl implements LlmProviderFactory {

    private final @NonNull LlmProviderApiKeyService llmProviderApiKeyService;
    private final Map<LlmProvider, LlmServiceProvider> services = new EnumMap<>(LlmProvider.class);

    public void register(LlmProvider llmProvider, LlmServiceProvider service) {
        services.put(llmProvider, service);
    }

    public LlmProviderService getService(@NonNull String workspaceId, @NonNull String model) {
        var llmProvider = getLlmProvider(model);
        var providerConfig = getProviderApiKey(workspaceId, llmProvider, model);
        var config = buildConfig(providerConfig);

        return Optional.ofNullable(services.get(llmProvider))
                .map(provider -> provider.getService(config))
                .orElseThrow(() -> new LlmProviderUnsupportedException(
                        "LLM provider not supported: %s".formatted(llmProvider)));
    }

    private LlmProviderClientApiConfig buildConfig(ProviderApiKey providerConfig) {
        var configuration = Optional.ofNullable(providerConfig.configuration()).orElse(Map.of());

        // For custom LLM providers, add provider_name to configuration if present
        if (providerConfig.provider() == LlmProvider.CUSTOM_LLM
                && StringUtils.isNotBlank(providerConfig.providerName())) {
            configuration = new HashMap<>(configuration);
            configuration.put("provider_name", providerConfig.providerName());
        }

        return LlmProviderClientApiConfig.builder()
                .apiKey(providerConfig.apiKey() != null ? decrypt(providerConfig.apiKey()) : null)
                .headers(Optional.ofNullable(providerConfig.headers()).orElse(Map.of()))
                .baseUrl(providerConfig.baseUrl())
                .configuration(configuration)
                .build();
    }

    public ChatModel getLanguageModel(@NonNull String workspaceId,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var llmProvider = getLlmProvider(modelParameters.name());
        var providerConfig = getProviderApiKey(workspaceId, llmProvider, modelParameters.name());
        var config = buildConfig(providerConfig);

        return Optional.ofNullable(services.get(llmProvider))
                .map(provider -> provider.getLanguageModel(config, modelParameters))
                .orElseThrow(() -> new BadRequestException(
                        String.format(ERROR_MODEL_NOT_SUPPORTED, modelParameters.name())));
    }

    /**
     * The agreed requirement is to resolve the LLM provider and its API key based on the model.
     */
    public LlmProvider getLlmProvider(@NonNull String model) {
        if (isModelBelongToProvider(model, OpenaiModelName.class, OpenaiModelName::toString)) {
            return LlmProvider.OPEN_AI;
        }
        if (isModelBelongToProvider(model, AnthropicModelName.class, AnthropicModelName::toString)) {
            return LlmProvider.ANTHROPIC;
        }
        if (isModelBelongToProvider(model, GeminiModelName.class, GeminiModelName::toString)) {
            return LlmProvider.GEMINI;
        }
        if (isModelBelongToProvider(model, OpenRouterModelName.class, OpenRouterModelName::toString)) {
            return LlmProvider.OPEN_ROUTER;
        }

        if (isModelBelongToProvider(model, VertexAIModelName.class, VertexAIModelName::qualifiedName)) {
            return LlmProvider.VERTEX_AI;
        }

        if (CustomLlmModelNameChecker.isCustomLlmModel(model)) {
            return LlmProvider.CUSTOM_LLM;
        }

        throw new BadRequestException(ERROR_MODEL_NOT_SUPPORTED.formatted(model));
    }

    /**
     * Finding API keys isn't paginated at the moment.
     * Even in the future, the number of supported LLM providers per workspace is going to be very low.
     *
     * For custom LLM providers, this method matches the model against configured models to find the correct provider.
     * This is necessary because model names can contain slashes (e.g., "mistralai/Mistral-7B-Instruct-v0.3"),
     * making it impossible to reliably extract the provider name from the model string alone.
     */
    private ProviderApiKey getProviderApiKey(String workspaceId, LlmProvider llmProvider, String model) {
        return llmProviderApiKeyService.find(workspaceId).content().stream()
                .filter(providerApiKey -> {
                    // Match provider type
                    if (!llmProvider.equals(providerApiKey.provider())) {
                        return false;
                    }

                    // For custom LLMs, match the model against configured models
                    if (llmProvider == LlmProvider.CUSTOM_LLM) {
                        return isModelConfiguredForProvider(model, providerApiKey);
                    }

                    return true;
                })
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "API key not configured for LLM. provider='%s', model='%s'".formatted(
                                llmProvider.getValue(), model)));
    }

    /**
     * Checks if a model is configured for a specific custom LLM provider.
     * Uses direct string comparison between the requested model and configured models.
     *
     * The database stores models in the same format as they are requested:
     *   - Legacy format: "custom-llm/model-name" (e.g., "custom-llm/mistralai/Mistral-7B-Instruct-v0.3")
     *   - Named provider format: "custom-llm/provider-name/model-name" (e.g., "custom-llm/ollama/llama-3.2")
     *
     * @param model The full model identifier from the request
     * @param providerApiKey The provider configuration from the database
     * @return true if the model is in the provider's configured model list
     */
    private boolean isModelConfiguredForProvider(String model, ProviderApiKey providerApiKey) {
        var configuredModels = Optional.ofNullable(providerApiKey.configuration())
                .map(config -> config.get("models"))
                .map(Object::toString)
                .orElse("");

        if (configuredModels.isEmpty()) {
            return false;
        }

        if (!CustomLlmModelNameChecker.isCustomLlmModel(model)) {
            return false;
        }

        return Arrays.stream(configuredModels.split(","))
                .map(String::trim)
                .anyMatch(configuredModel -> model.equals(configuredModel));
    }

    private static <E extends Enum<E>> boolean isModelBelongToProvider(
            String model, Class<E> enumClass, Function<E, String> valueGetter) {
        return EnumUtils.getEnumList(enumClass).stream()
                .map(valueGetter)
                .anyMatch(model::equals);
    }
}
