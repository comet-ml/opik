package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class LlmProviderGemini implements LlmProviderService {
    private final @NonNull GeminiClientGenerator llmProviderClientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var mapper = LlmProviderGeminiMapper.INSTANCE;
        var response = llmProviderClientGenerator.generate(config, request)
                .generate(request.messages().stream().map(mapper::toChatMessage).toList());

        return mapper.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        CompletableFuture.runAsync(() -> llmProviderClientGenerator.newGeminiStreamingClient(config.apiKey(), request)
                .generate(request.messages().stream().map(LlmProviderGeminiMapper.INSTANCE::toChatMessage).toList(),
                        new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model())));
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
        String message = throwable.getMessage();
        var openBraceIndex = message.indexOf('{');
        if (openBraceIndex >= 0) {
            String jsonPart = message.substring(openBraceIndex); // Extract JSON part
            try {
                var geminiError = JsonUtils.readValue(jsonPart, GeminiErrorObject.class);
                return geminiError.toErrorMessage();
            } catch (UncheckedIOException e) {
                log.warn("failed to parse Gemini error message", e);
                return Optional.empty();
            }
        }

        return Optional.empty();
    }
}
