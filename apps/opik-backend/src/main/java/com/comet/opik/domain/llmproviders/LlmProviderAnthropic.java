package com.comet.opik.domain.llmproviders;

import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.anthropic.internal.client.AnthropicHttpException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Optional;
import java.util.function.Consumer;

import static com.comet.opik.domain.ChatCompletionService.ERROR_EMPTY_MESSAGES;
import static com.comet.opik.domain.ChatCompletionService.ERROR_NO_COMPLETION_TOKENS;

@RequiredArgsConstructor
@Slf4j
class LlmProviderAnthropic implements LlmProviderService {
    private final @NonNull AnthropicClient anthropicClient;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var mapper = LlmProviderAnthropicMapper.INSTANCE;
        var response = anthropicClient.createMessage(mapper.toCreateMessageRequest(request));

        return mapper.toResponse(response);
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose, @NonNull Consumer<Throwable> handleError) {
        validateRequest(request);
        anthropicClient.createMessage(LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request),
                new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model()));
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
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable runtimeException) {
        if (runtimeException instanceof AnthropicHttpException anthropicHttpException) {
            return Optional.of(new ErrorMessage(anthropicHttpException.statusCode(),
                    anthropicHttpException.getMessage()));
        }

        return Optional.empty();
    }
}
