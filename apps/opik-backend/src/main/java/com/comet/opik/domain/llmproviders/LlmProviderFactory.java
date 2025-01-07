package com.comet.opik.domain.llmproviders;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.chat.ChatCompletionModel;
import dev.langchain4j.model.anthropic.AnthropicChatModelName;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.EnumUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.function.Function;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LlmProviderFactory {
    public static final String ERROR_MODEL_NOT_SUPPORTED = "model not supported %s";

    private final @NonNull @Config LlmProviderClientConfig llmProviderClientConfig;
    private final @NonNull LlmProviderApiKeyService llmProviderApiKeyService;

    public LlmProviderService getService(@NonNull String workspaceId, @NonNull String model) {
        var llmProvider = getLlmProvider(model);
        var apiKey = EncryptionUtils.decrypt(getEncryptedApiKey(workspaceId, llmProvider));

        return switch (llmProvider) {
            case LlmProvider.OPEN_AI -> new LlmProviderOpenAi(llmProviderClientConfig, apiKey);
            case LlmProvider.ANTHROPIC -> new LlmProviderAnthropic(llmProviderClientConfig, apiKey);
            case LlmProvider.GEMINI -> new LlmProviderGemini(llmProviderClientConfig, apiKey);
        };
    }

    /**
     * The agreed requirement is to resolve the LLM provider and its API key based on the model.
     */
    private LlmProvider getLlmProvider(String model) {
        if (isModelBelongToProvider(model, ChatCompletionModel.class, ChatCompletionModel::toString)) {
            return LlmProvider.OPEN_AI;
        }
        if (isModelBelongToProvider(model, AnthropicChatModelName.class, AnthropicChatModelName::toString)) {
            return LlmProvider.ANTHROPIC;
        }
        if (isModelBelongToProvider(model, GeminiModelName.class, GeminiModelName::toString)) {
            return LlmProvider.GEMINI;
        }

        throw new BadRequestException(ERROR_MODEL_NOT_SUPPORTED.formatted(model));
    }

    /**
     * Finding API keys isn't paginated at the moment.
     * Even in the future, the number of supported LLM providers per workspace is going to be very low.
     */
    private String getEncryptedApiKey(String workspaceId, LlmProvider llmProvider) {
        return llmProviderApiKeyService.find(workspaceId).content().stream()
                .filter(providerApiKey -> llmProvider.equals(providerApiKey.provider()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("API key not configured for LLM provider '%s'".formatted(
                        llmProvider.getValue())))
                .apiKey();
    }

    private static <E extends Enum<E>> boolean isModelBelongToProvider(
            String model, Class<E> enumClass, Function<E, String> valueGetter) {
        return EnumUtils.getEnumList(enumClass).stream()
                .map(valueGetter)
                .anyMatch(model::equals);
    }
}
