package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.LoggingChunkedResponseHandler;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.anthropic.internal.api.AnthropicStreamingException;
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
import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;

@RequiredArgsConstructor
@Slf4j
class LlmProviderAnthropic implements LlmProviderService {

    private final @NonNull AnthropicClient anthropicClient;
    private final @NonNull LlmModelRegistryService registryService;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var response = anthropicClient
                .createMessage(LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request, registryService));

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

        anthropicClient.createMessage(
                LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request, registryService),
                new LoggingChunkedResponseHandler(delegate, logger));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        // see https://github.com/anthropics/courses/blob/master/anthropic_api_fundamentals/04_parameters.ipynb
        if (CollectionUtils.isEmpty(request.messages())) {
            throw new BadRequestException(ERROR_EMPTY_MESSAGES);
        }
        // Called for its 400s only. The mapper runs the same check again while sending, but a throw there is a 500.
        AnthropicEffort.toCustomParameters(request.model(), request.customParameters(), registryService);
        // maxCompletionTokens is required by Anthropic but defaulted (with a log line) inside
        // LlmProviderAnthropicMapper.resolveMaxTokens, so callers without an explicit cap still
        // succeed — same UX as OpenAI in the same flows.
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return statusOf(throwable).map(status -> new ErrorMessage(status, messageOf(throwable)));
    }

    private static Optional<Integer> statusOf(Throwable throwable) {
        return switch (throwable) {
            case InternalServerException ignored -> Optional.of(500);
            case ModelNotFoundException ignored -> Optional.of(404);
            case TimeoutException ignored -> Optional.of(408);
            case RateLimitException ignored -> Optional.of(429);
            case InvalidRequestException ignored -> Optional.of(400);
            case AuthenticationException ignored -> Optional.of(401);
            // an error event sent after the stream started
            case AnthropicStreamingException ignored -> Optional.of(500);
            // createMessage raises it unmapped; the same error status the caller gets for it without a parsed body
            case HttpException ex when ex.statusCode() >= 400 && ex.statusCode() < 600 ->
                Optional.of(ex.statusCode());
            default -> Optional.empty();
        };
    }

    private static String messageOf(Throwable throwable) {
        var providerError = throwable instanceof AnthropicStreamingException streamingError
                ? Optional.of(new AnthropicErrorMessage(
                        new AnthropicErrorMessage.AnthropicError(streamingError.type(), streamingError.getMessage())))
                        .filter(AnthropicErrorMessage::isDocumented)
                        .map(AnthropicErrorMessage::toErrorMessage)
                : LlmProviderLangChainMapper.INSTANCE.getAnthropicErrorObject(throwable, log);
        return providerError.map(ErrorMessage::getMessage).orElse(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
    }
}
