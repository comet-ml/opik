package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LoggingChunkedResponseHandler;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Optional;
import java.util.function.Consumer;

import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_EMPTY_MESSAGES;
import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_NO_COMPLETION_TOKENS;

@RequiredArgsConstructor
@Slf4j
class LlmProviderAnthropic implements LlmProviderService {

    private final @NonNull AnthropicClient anthropicClient;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var response = anthropicClient
                .createMessage(LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request));

        return LlmProviderAnthropicMapper.INSTANCE.toResponse(response);
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        validateRequest(request);

        // Create a simple summary of the request for logging
        String requestSummary = String.format("model=%s, messages=%d",
                request.model(),
                request.messages() != null ? request.messages().size() : 0);

        // Create dependencies following IoC principle
        var delegate = new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model());
        var logger = new StreamingResponseLogger(requestSummary, request.model());

        anthropicClient.createMessage(LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request),
                new LoggingChunkedResponseHandler(delegate, logger));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        // see https://github.com/anthropics/courses/blob/master/anthropic_api_fundamentals/04_parameters.ipynb
        if (CollectionUtils.isEmpty(request.messages())) {
            throw new BadRequestException(ERROR_EMPTY_MESSAGES);
        }
        if (request.maxCompletionTokens() == null) {
            throw new BadRequestException(ERROR_NO_COMPLETION_TOKENS);
        }

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return switch (throwable) {
            case InternalServerException ex -> Optional.of(new ErrorMessage(500, ex.getMessage()));
            case ModelNotFoundException ex -> Optional.of(new ErrorMessage(404, ex.getMessage()));
            case TimeoutException ex -> Optional.of(new ErrorMessage(408, ex.getMessage()));
            case RateLimitException ex -> Optional.of(new ErrorMessage(429, ex.getMessage()));
            case InvalidRequestException ex -> Optional.of(new ErrorMessage(400, ex.getMessage()));
            case AuthenticationException ex -> Optional.of(new ErrorMessage(401, ex.getMessage()));
            default -> Optional.empty();
        };
    }
}
