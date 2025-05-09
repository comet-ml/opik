package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.gemini.GeminiErrorObject;
import com.comet.opik.utils.JsonUtils;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LlmProviderVertexAI implements LlmProviderService {

    private final @NonNull VertexAIClientGenerator llmProviderClientGenerator;
    private final @NonNull String apiKey;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var response = llmProviderClientGenerator.generate(apiKey, request)
                .generate(request.messages().stream().map(VertexAIMapper.INSTANCE::toChatMessage).toList());

        return VertexAIMapper.INSTANCE.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        Schedulers.boundedElastic()
                .schedule(() -> llmProviderClientGenerator.newVertexAIStreamingClient(apiKey, request)
                        .generate(request.messages().stream().map(VertexAIMapper.INSTANCE::toChatMessage).toList(),
                                new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model())));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

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
