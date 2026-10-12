package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.OpenAiStreamingHelper;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class CustomLlmProvider implements LlmProviderService {
    // assume that the provider is compatible with OpenAI API, so we use the OpenAiClient to interact with it
    private final @NonNull OpenAiClient openAiClient;
    private final Map<String, String> configuration;
    private final LlmProvider provider;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        ChatCompletionRequest cleanedRequest = normalizeForProvider(cleanModelName(request));
        return openAiClient.chatCompletion(cleanedRequest).execute();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        ChatCompletionRequest cleanedRequest = normalizeForProvider(cleanModelName(request));
        OpenAiStreamingHelper.executeStreamingRequest(openAiClient, cleanedRequest, handleMessage, handleClose,
                handleError);
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getCustomLlmErrorObject(throwable, log);
    }

    private ChatCompletionRequest cleanModelName(@NonNull ChatCompletionRequest request) {
        if (!CustomLlmModelNameChecker.isCustomLlmModel(request.model())) {
            return request;
        }

        // Extract provider_name from configuration (null for legacy providers)
        String providerName = Optional.ofNullable(configuration)
                .map(config -> config.get("provider_name"))
                .orElse(null);

        // Extract the actual model name using the provider name
        String actualModelName = CustomLlmModelNameChecker.extractModelName(request.model(), providerName);

        log.debug("Cleaned model name from '{}' to '{}' (providerName='{}')",
                request.model(), actualModelName, providerName);

        // Use .from() to copy all fields, then override the model name
        return ChatCompletionRequest.builder()
                .from(request)
                .model(actualModelName)
                .build();
    }

    // Ollama's /v1 endpoint reads only max_tokens and silently drops max_completion_tokens, so the limit never applied.
    // Bedrock documents max_completion_tokens for its OpenAI-compatible body, so it gets the limit in that field only.
    // Neither gets the 0 the playground slider allows: OpenAI-style APIs reject it, and Ollama passes it to its runner
    // as the budget. A generic custom server gets the request exactly as sent, since some of them need
    // max_completion_tokens.
    // Bedrock also gets no penalty of 0: the playground seeds 0 for both, AWS does not say every Chat Completions model
    // accepts the fields, and 0 is the API default anyway, so leaving it out changes nothing for a model that does.
    private ChatCompletionRequest normalizeForProvider(ChatCompletionRequest request) {
        if (provider == LlmProvider.OLLAMA) {
            return ChatCompletionRequest.builder()
                    .from(request)
                    .maxTokens(firstPositiveTokenLimit(request))
                    .maxCompletionTokens(null)
                    .build();
        }
        if (provider == LlmProvider.BEDROCK) {
            return ChatCompletionRequest.builder()
                    .from(request)
                    .maxCompletionTokens(firstPositiveTokenLimit(request))
                    .maxTokens(null)
                    .frequencyPenalty(nonZeroOrNull(request.frequencyPenalty()))
                    .presencePenalty(nonZeroOrNull(request.presencePenalty()))
                    .build();
        }
        return request;
    }

    private static Integer firstPositiveTokenLimit(ChatCompletionRequest request) {
        return ObjectUtils.firstNonNull(
                positiveOrNull(request.maxCompletionTokens()), positiveOrNull(request.maxTokens()));
    }

    private static Integer positiveOrNull(Integer tokens) {
        return tokens != null && tokens > 0 ? tokens : null;
    }

    private static Double nonZeroOrNull(Double penalty) {
        return penalty != null && penalty != 0 ? penalty : null;
    }

}
