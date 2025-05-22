package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.EnumUtils;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

@RequiredArgsConstructor(onConstructor_ = @Inject)
class LlmProviderFactoryImpl implements LlmProviderFactory {

    private final @NonNull LlmProviderApiKeyService llmProviderApiKeyService;
    private final Map<LlmProvider, LlmServiceProvider> services = new EnumMap<>(LlmProvider.class);

    public void register(LlmProvider llmProvider, LlmServiceProvider service) {
        services.put(llmProvider, service);
    }

    public LlmProviderService getService(@NonNull String workspaceId, @NonNull String model) {
        var llmProvider = getLlmProvider(model);
        var providerConfig = getProviderApiKey(workspaceId, llmProvider);

        var config = buildConfig(providerConfig);

        return Optional.ofNullable(services.get(llmProvider))
                .map(provider -> provider.getService(config))
                .orElseThrow(() -> new LlmProviderUnsupportedException(
                        "LLM provider not supported: %s".formatted(llmProvider)));
    }

    private LlmProviderClientApiConfig buildConfig(ProviderApiKey providerConfig) {
        return LlmProviderClientApiConfig.builder()
                .apiKey(EncryptionUtils.decrypt(providerConfig.apiKey()))
                .headers(Optional.ofNullable(providerConfig.headers()).orElse(Map.of()))
                .baseUrl(providerConfig.baseUrl())
                .configuration(Optional.ofNullable(providerConfig.configuration()).orElse(Map.of()))
                .build();
    }

    public ChatModel getLanguageModel(@NonNull String workspaceId,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var llmProvider = getLlmProvider(modelParameters.name());
        var providerConfig = getProviderApiKey(workspaceId, llmProvider);

        var config = buildConfig(providerConfig);

        return Optional.ofNullable(services.get(llmProvider))
                .map(provider -> provider.getLanguageModel(config, modelParameters))
                .orElseThrow(() -> new BadRequestException(
                        String.format(ERROR_MODEL_NOT_SUPPORTED, modelParameters.name())));
    }

    /**
     * The agreed requirement is to resolve the LLM provider and its API key based on the model.
     */
    private LlmProvider getLlmProvider(String model) {
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

        throw new BadRequestException(ERROR_MODEL_NOT_SUPPORTED.formatted(model));
    }

    /**
     * Finding API keys isn't paginated at the moment.
     * Even in the future, the number of supported LLM providers per workspace is going to be very low.
     */
    private ProviderApiKey getProviderApiKey(String workspaceId, LlmProvider llmProvider) {
        return llmProviderApiKeyService.find(workspaceId).content().stream()
                .filter(providerApiKey -> llmProvider.equals(providerApiKey.provider()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("API key not configured for LLM provider '%s'".formatted(
                        llmProvider.getValue())));
    }

    private static <E extends Enum<E>> boolean isModelBelongToProvider(
            String model, Class<E> enumClass, Function<E, String> valueGetter) {
        return EnumUtils.getEnumList(enumClass).stream()
                .map(valueGetter)
                .anyMatch(model::equals);
    }
}
