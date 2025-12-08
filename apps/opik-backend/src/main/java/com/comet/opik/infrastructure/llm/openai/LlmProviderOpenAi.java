package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LlmProviderOpenAi implements LlmProviderService {
    private final @NonNull OpenAiClient openAiClient;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        return openAiClient.chatCompletion(request).execute();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        // Create a simple summary of the request for logging
        String requestSummary = String.format("model=%s, messages=%d",
                request.model(),
                request.messages() != null ? request.messages().size() : 0);

        // Create reusable logger for accumulating and logging the complete response
        StreamingResponseLogger logger = new StreamingResponseLogger(requestSummary, request.model());

        openAiClient.chatCompletion(request)
                .onPartialResponse(response -> {
                    // Extract and accumulate content for logging
                    if (response.choices() != null && !response.choices().isEmpty()) {
                        var delta = response.choices().get(0).delta();
                        if (delta != null && delta.content() != null) {
                            logger.appendContent(delta.content());
                        }
                    }
                    // Log complete response if this is the final message (has usage info)
                    if (response.usage() != null) {
                        logger.logComplete(response);
                    }
                    handleMessage.accept(response);
                })
                .onComplete(() -> {
                    handleClose.run();
                })
                .onError(throwable -> {
                    logger.logError(throwable);
                    handleError.accept(throwable);
                })
                .execute();
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getErrorObject(throwable, log);
    }

}
