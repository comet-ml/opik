package com.comet.opik.domain.llmproviders;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.infrastructure.EncryptionUtils;
import dev.ai4j.openai4j.chat.ChatCompletionModel;
import dev.langchain4j.model.anthropic.AnthropicChatModelName;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.EnumUtils;

import java.util.function.Function;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LlmProviderFactory {
    public static final String ERROR_MODEL_NOT_SUPPORTED = "model not supported %s";

    private final @NonNull LlmProviderApiKeyService llmProviderApiKeyService;
    private final @NonNull LlmProviderClientGenerator llmProviderClientGenerator;

    public LlmProviderService getService(@NonNull String workspaceId, @NonNull String model) {
        var llmProvider = getLlmProvider(model);
        var apiKey = EncryptionUtils.decrypt(getEncryptedApiKey(workspaceId, llmProvider));

        return switch (llmProvider) {
            case LlmProvider.OPEN_AI -> new LlmProviderOpenAi(llmProviderClientGenerator.newOpenAiClient(apiKey));
            case LlmProvider.ANTHROPIC ->
                new LlmProviderAnthropic(llmProviderClientGenerator.newAnthropicClient(apiKey));
            case LlmProvider.GEMINI -> new LlmProviderGemini(llmProviderClientGenerator, apiKey);
        };
    }

    public ChatLanguageModel getLanguageModel(@NonNull String workspaceId,
            @NonNull AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        var llmProvider = getLlmProvider(modelParameters.name());
        var apiKey = EncryptionUtils.decrypt(getEncryptedApiKey(workspaceId, llmProvider));

        return switch (llmProvider) {
            case LlmProvider.OPEN_AI -> llmProviderClientGenerator.newOpenAiChatLanguageModel(apiKey, modelParameters);
            default -> throw new BadRequestException(String.format(ERROR_MODEL_NOT_SUPPORTED, modelParameters.name()));
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
