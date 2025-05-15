package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        var mapper = LlmProviderLangChainMapper.INSTANCE;
        var response = llmProviderClientGenerator.generate(config, request).chat(mapper.mapMessages(request));
        return mapper.toChatCompletionResponse(request, response);
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

                        streamingChatLanguageModel
                                .chat(
                                        LlmProviderLangChainMapper.INSTANCE.mapMessages(request),
                                        new ChunkedResponseHandler(handleMessage, handleClose, handleError,
                                                request.model()));
                    } catch (Exception e) {
                        handleError.accept(e);
                        handleClose.run();
                    }
                });
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
    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getGeminiErrorObject(throwable, log);
    }
}
