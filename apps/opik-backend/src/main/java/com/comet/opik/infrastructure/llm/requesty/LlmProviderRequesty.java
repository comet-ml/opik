package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.OpenAiStreamingHelper;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Requesty speaks the OpenAI chat completions protocol, so the OpenAI client is reused. The only
 * Requesty-specific work is dropping the {@code requesty/} prefix Opik uses to tell Requesty models
 * apart from OpenRouter ones before the request leaves the backend, and parsing the router's error
 * envelope on the way back.
 */
@RequiredArgsConstructor
@Slf4j
public class LlmProviderRequesty implements LlmProviderService {
    static final String ERROR_EMPTY_ROUTER_MODEL = "Requesty model must be a router id such as 'requesty/openai/gpt-4o', got '%s'";

    private final @NonNull OpenAiClient openAiClient;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        return openAiClient.chatCompletion(cleanModelName(request)).execute();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        OpenAiStreamingHelper.executeStreamingRequest(openAiClient, cleanModelName(request), handleMessage,
                handleClose, handleError);
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        // A bare "requesty/" would be stripped down to an empty model and rejected by the router with a
        // confusing 400, so fail fast here with a message that names the expected format.
        if (RequestyModelName.isRequestyModel(request.model())
                && StringUtils.isBlank(RequestyModelName.stripPrefix(request.model()))) {
            throw new BadRequestException(ERROR_EMPTY_ROUTER_MODEL.formatted(request.model()));
        }
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getRequestyErrorObject(throwable, log);
    }

    static ChatCompletionRequest cleanModelName(@NonNull ChatCompletionRequest request) {
        if (!RequestyModelName.isRequestyModel(request.model())) {
            return request;
        }

        String routerModel = RequestyModelName.stripPrefix(request.model());
        log.debug("Cleaned model name from '{}' to '{}'", request.model(), routerModel);

        // Use .from() to copy all fields, then override the model name
        return ChatCompletionRequest.builder()
                .from(request)
                .model(routerModel)
                .build();
    }
}
