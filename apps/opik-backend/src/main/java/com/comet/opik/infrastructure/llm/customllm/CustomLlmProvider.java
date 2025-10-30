package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class CustomLlmProvider implements LlmProviderService {
    // assume that the provider is compatible with OpenAI API, so we use the OpenAiClient to interact with it
    private final @NonNull OpenAiClient openAiClient;
    private final Map<String, String> configuration;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        ChatCompletionRequest cleanedRequest = cleanModelName(request);
        return openAiClient.chatCompletion(cleanedRequest).execute();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        ChatCompletionRequest cleanedRequest = cleanModelName(request);
        openAiClient.chatCompletion(cleanedRequest)
                .onPartialResponse(handleMessage)
                .onComplete(handleClose)
                .onError(handleError)
                .execute();
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

        return ChatCompletionRequest.builder()
                .model(actualModelName)
                .messages(request.messages())
                .temperature(request.temperature())
                .topP(request.topP())
                .n(request.n())
                .stream(request.stream())
                .streamOptions(request.streamOptions())
                .stop(request.stop())
                .maxTokens(request.maxTokens())
                .maxCompletionTokens(request.maxCompletionTokens())
                .presencePenalty(request.presencePenalty())
                .frequencyPenalty(request.frequencyPenalty())
                .logitBias(request.logitBias())
                .user(request.user())
                .responseFormat(request.responseFormat())
                .seed(request.seed())
                .tools(request.tools())
                .toolChoice(request.toolChoice())
                .parallelToolCalls(request.parallelToolCalls())
                .store(request.store())
                .metadata(request.metadata())
                .reasoningEffort(request.reasoningEffort())
                .serviceTier(request.serviceTier())
                .functions(request.functions())
                .functionCall(request.functionCall())
                .build();
    }

}
