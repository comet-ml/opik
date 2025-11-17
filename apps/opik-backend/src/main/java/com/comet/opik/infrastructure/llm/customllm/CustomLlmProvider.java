package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class CustomLlmProvider implements LlmProviderService {
    // assume that the provider is compatible with OpenAI API, so we use the OpenAiClient to interact with it
    private final @NonNull OpenAiClient openAiClient;
    private final Map<String, String> configuration;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("CustomLlmProvider.generate() called with stream='{}', streamOptions='{}', model='{}'",
                request.stream(), request.streamOptions(), request.model());
        ChatCompletionRequest cleanedRequest = cleanModelName(request);
        log.info(
                "CustomLlmProvider after cleanModelName: stream='{}', streamOptions='{}', model='{}', messageCount='{}'",
                cleanedRequest.stream(), cleanedRequest.streamOptions(), cleanedRequest.model(),
                cleanedRequest.messages() != null ? cleanedRequest.messages().size() : 0);

        // Extra validation to ensure streaming is disabled
        if (Boolean.TRUE.equals(cleanedRequest.stream())) {
            log.warn("Request has stream=true, but generate() should never use streaming! Forcing stream=false");
            cleanedRequest = ChatCompletionRequest.builder()
                    .model(cleanedRequest.model())
                    .messages(cleanedRequest.messages())
                    .temperature(cleanedRequest.temperature())
                    .topP(cleanedRequest.topP())
                    .n(cleanedRequest.n())
                    .stream(false) // Force non-streaming
                    .streamOptions(null) // Explicitly null
                    .stop(cleanedRequest.stop())
                    .maxTokens(cleanedRequest.maxTokens())
                    .maxCompletionTokens(cleanedRequest.maxCompletionTokens())
                    .presencePenalty(cleanedRequest.presencePenalty())
                    .frequencyPenalty(cleanedRequest.frequencyPenalty())
                    .logitBias(cleanedRequest.logitBias())
                    .user(cleanedRequest.user())
                    .responseFormat(cleanedRequest.responseFormat())
                    .seed(cleanedRequest.seed())
                    .tools(cleanedRequest.tools())
                    .toolChoice(cleanedRequest.toolChoice())
                    .parallelToolCalls(cleanedRequest.parallelToolCalls())
                    .store(cleanedRequest.store())
                    .metadata(cleanedRequest.metadata())
                    .reasoningEffort(cleanedRequest.reasoningEffort())
                    .serviceTier(cleanedRequest.serviceTier())
                    .functions(cleanedRequest.functions())
                    .functionCall(cleanedRequest.functionCall())
                    .build();
        }

        try {
            return openAiClient.chatCompletion(cleanedRequest).execute();
        } catch (Exception e) {
            // Check if this is the SSE format error (vLLM bug where it ignores stream:false)
            // Walk the entire exception chain to find JsonProcessingException
            Throwable current = e;
            JsonProcessingException jsonException = null;

            while (current != null) {
                if (current instanceof JsonProcessingException) {
                    jsonException = (JsonProcessingException) current;
                    break;
                }
                current = current.getCause();
            }

            if (jsonException != null && jsonException.getMessage() != null
                    && jsonException.getMessage().contains("Unrecognized token 'data'")) {
                log.warn("SSE format detected despite stream:false. Falling back to SSE parsing for model '{}'",
                        cleanedRequest.model());
                return generateViaStreaming(cleanedRequest, workspaceId);
            }

            throw e;
        }
    }

    /**
     * Fallback for vLLM bug where it returns SSE format even when stream:false.
     * Collects all streaming chunks and returns the final complete response.
     */
    private ChatCompletionResponse generateViaStreaming(ChatCompletionRequest request, String workspaceId) {
        StringBuilder accumulatedContent = new StringBuilder();
        AtomicReference<ChatCompletionResponse> lastResponse = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Force stream: true since we're using streaming API to parse SSE
        ChatCompletionRequest streamRequest = ChatCompletionRequest.builder()
                .model(request.model())
                .messages(request.messages())
                .temperature(request.temperature())
                .topP(request.topP())
                .n(request.n())
                .stream(true) // Enable streaming to properly parse SSE
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

        // Use streaming API to handle SSE format
        openAiClient.chatCompletion(streamRequest)
                .onPartialResponse(response -> {
                    // Accumulate content from delta
                    if (response.choices() != null && !response.choices().isEmpty()) {
                        var delta = response.choices().get(0).delta();
                        if (delta != null && delta.content() != null && !delta.content().isEmpty()) {
                            String chunk = delta.content();
                            accumulatedContent.append(chunk);
                            log.info("Accumulated chunk of {} chars, total now: {} chars for model '{}'",
                                    chunk.length(), accumulatedContent.length(), request.model());
                        }
                    }
                    // Keep the last response for metadata
                    lastResponse.set(response);
                })
                .onComplete(() -> {
                    log.debug("SSE stream completed for model '{}', total content length: {}",
                            request.model(), accumulatedContent.length());
                    latch.countDown();
                })
                .onError(throwable -> {
                    log.error("Error in SSE stream for model '{}'", request.model(), throwable);
                    error.set(throwable);
                    latch.countDown();
                })
                .execute();

        // Wait for stream to complete (with 5 minute timeout matching our config)
        try {
            boolean completed = latch.await(5, TimeUnit.MINUTES);
            if (!completed) {
                throw new RuntimeException("Timeout waiting for vLLM SSE stream after 5 minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for vLLM SSE stream", e);
        }

        if (error.get() != null) {
            log.error("Error during SSE streaming fallback for model '{}'", request.model(), error.get());
            throw new RuntimeException("Failed to parse vLLM SSE response", error.get());
        }

        if (lastResponse.get() == null) {
            throw new RuntimeException("No response received from vLLM SSE stream");
        }

        // Build final response with accumulated content
        var completeContent = accumulatedContent.toString();
        if (completeContent.isEmpty()) {
            log.warn("SSE stream completed but accumulated content is empty for model '{}'", request.model());
        }

        var originalResponse = lastResponse.get();
        var completeChoice = dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice.builder()
                .index(0)
                .message(dev.langchain4j.model.openai.internal.chat.AssistantMessage.builder()
                        .content(completeContent)
                        .build())
                .finishReason(originalResponse.choices() != null && !originalResponse.choices().isEmpty()
                        ? originalResponse.choices().get(0).finishReason()
                        : "stop")
                .build();

        var completeResponse = ChatCompletionResponse.builder()
                .id(originalResponse.id())
                .created(originalResponse.created())
                .model(originalResponse.model())
                .choices(java.util.List.of(completeChoice))
                .usage(originalResponse.usage())
                .build();

        log.info("Successfully parsed vLLM SSE response for model '{}', content length: {}",
                request.model(), completeContent.length());
        return completeResponse;
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
