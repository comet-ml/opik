package com.comet.opik.infrastructure.llm.freemodel;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * LLM Provider service for Opik Free Model that transforms the model name
 * from "opik-free-model" to the actual model.
 */
@RequiredArgsConstructor
@Slf4j
public class FreeModelLlmProvider implements LlmProviderService {
    private final @NonNull OpenAiClient openAiClient;
    private final @NonNull String actualModel;
    private final boolean isReasoningModel;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        // Transform the model name to the actual model
        var transformedRequest = transformRequest(request);
        log.debug("Transformed model from '{}' to '{}' for Free Model provider",
                request.model(), transformedRequest.model());
        return openAiClient.chatCompletion(transformedRequest).execute();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        // Transform the model name to the actual model
        var transformedRequest = transformRequest(request);
        log.debug("Transformed model from '{}' to '{}' for Free Model provider (streaming)",
                request.model(), transformedRequest.model());
        openAiClient.chatCompletion(transformedRequest)
                .onPartialResponse(handleMessage)
                .onComplete(handleClose)
                .onError(handleError)
                .execute();
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        // No special validation needed
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getErrorObject(throwable, log);
    }

    /**
     * Transforms the request to use the actual model name instead of "opik-free-model".
     * For reasoning models (GPT-5, O-series), clamps temperature to >= 1.0 since existing
     * automation rules may have temperature=0.0 saved from when the free model was gpt-4o-mini.
     *
     * Reasoning is set to the minimum level (reasoning_effort=minimal) because the free model
     * is used for simple evaluation tasks where reasoning adds latency and cost without benefit.
     * Models like gpt-5-nano default to medium reasoning effort, which makes them significantly
     * slower. Note: "none" is not a supported value for all models.
     */
    private ChatCompletionRequest transformRequest(ChatCompletionRequest request) {
        Double temperature = request.temperature();

        if (isReasoningModel && temperature != null
                && temperature < FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE) {
            log.debug("Clamping temperature from '{}' to '{}' for reasoning model '{}'",
                    temperature, FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE, actualModel);
            temperature = FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE;
        }

        return ChatCompletionRequest.builder()
                .model(actualModel)
                .messages(request.messages())
                .temperature(temperature)
                .topP(request.topP())
                .n(request.n())
                .stream(request.stream())
                .streamOptions(request.streamOptions())
                .stop(request.stop())
                .maxCompletionTokens(request.maxCompletionTokens())
                .presencePenalty(request.presencePenalty())
                .frequencyPenalty(request.frequencyPenalty())
                .logitBias(request.logitBias())
                .responseFormat(request.responseFormat())
                .seed(request.seed())
                .user(request.user())
                .tools(request.tools())
                .toolChoice(request.toolChoice())
                .parallelToolCalls(request.parallelToolCalls())
                .reasoningEffort("minimal")
                .customParameters(request.customParameters())
                .build();
    }
}
