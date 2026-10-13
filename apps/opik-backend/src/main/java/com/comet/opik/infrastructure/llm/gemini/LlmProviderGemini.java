package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.LoggingChunkedResponseHandler;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.FinishReason;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class LlmProviderGemini implements LlmProviderService {

    private final @NonNull GeminiClientGenerator llmProviderClientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var mappedMessages = GeminiLangChainMapper.INSTANCE.mapMessagesForGemini(request);
        var response = llmProviderClientGenerator.generate(config, request).chat(mappedMessages);
        return LlmProviderLangChainMapper.INSTANCE.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        Schedulers.boundedElastic()
                .schedule(() -> {
                    try {
                        var streamingChatLanguageModel = llmProviderClientGenerator.newGeminiStreamingClient(
                                config.apiKey(),
                                request);

                        var mappedMessages = GeminiLangChainMapper.INSTANCE.mapMessagesForGemini(request);

                        // Create a simple summary of the request for logging
                        String requestSummary = String.format("model=%s, messages=%d",
                                request.model(),
                                request.messages() != null ? request.messages().size() : 0);

                        // Create dependencies following IoC principle
                        var delegate = new ChunkedResponseHandler(handleMessage, handleClose, handleError,
                                request.model());
                        var logger = new StreamingResponseLogger(requestSummary, request.model());

                        streamingChatLanguageModel
                                .chat(
                                        mappedMessages,
                                        streamHandler(request, delegate, logger));
                    } catch (Exception e) {
                        handleError.accept(e);
                        handleClose.run();
                    }
                });
    }

    static LoggingChunkedResponseHandler streamHandler(ChatCompletionRequest request, ChunkedResponseHandler delegate,
            StreamingResponseLogger logger) {
        return new LoggingChunkedResponseHandler(delegate, logger) {
            @Override
            public void onCompleteResponse(ChatResponse chatResponse) {
                super.onCompleteResponse(withCutOffWhileThinking(request, chatResponse));
            }
        };
    }

    // When a thinking model spends the whole output limit on thinking, Google's stream drops the last chunk because it
    // has no parts, so the body is empty: no text, no finish reason, no usage. The same call without streaming says
    // MAX_TOKENS. Asking for thought summaries keeps that chunk, but it delays every answer and models that cannot
    // think reject it, so the empty stream is read as the limit running out instead.
    static ChatResponse withCutOffWhileThinking(ChatCompletionRequest request, ChatResponse response) {
        if (response.finishReason() != null
                || response.tokenUsage() != null
                || StringUtils.isNotEmpty(response.aiMessage().text())
                || !hasOutputLimit(request)
                || isThinkingTurnedOff(request)) {
            return response;
        }
        log.info("Gemini stream for model '{}' ended empty with max output tokens '{}', reporting finish reason '{}'",
                request.model(), request.maxCompletionTokens(), FinishReason.LENGTH);
        return response.toBuilder()
                .metadata(response.metadata().toBuilder().finishReason(FinishReason.LENGTH).build())
                .build();
    }

    private static boolean hasOutputLimit(ChatCompletionRequest request) {
        return request.maxCompletionTokens() != null && request.maxCompletionTokens() > 0;
    }

    private static boolean isThinkingTurnedOff(ChatCompletionRequest request) {
        return GeminiThinkingParams.from(request.customParameters())
                .wireBudgetFor(request.model())
                .filter(budget -> budget == 0)
                .isPresent();
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
    }

    /// gemini throws RuntimeExceptions with message structure as follows:
    /// ```
    /// java.lang.RuntimeException: HTTP error (429): {
    ///   "error": {
    ///     "code": 429,
    ///     "message": "Resource has been exhausted (e.g. check quota).",
    ///     "status": "RESOURCE_EXHAUSTED"
    ///   }
    /// }
    ///  ```
    ///
    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getGeminiErrorObject(throwable, log);
    }
}
