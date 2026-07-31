package com.comet.opik.infrastructure.llm.orcarouter;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.OpenAiStreamingHelper;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * OrcaRouter is OpenAI-compatible, so requests are delegated to the shared {@link OpenAiClient}.
 * <p>
 * Inside Opik every OrcaRouter model carries an {@code orcarouter/} prefix (e.g. {@code orcarouter/auto},
 * {@code orcarouter/openai/gpt-4o-mini}) so the provider can be resolved from the model string without
 * colliding with OpenRouter's shared namespaces. Before the request reaches the OrcaRouter API the prefix
 * must be stripped, with one nuance verified against the live API:
 * <ul>
 *   <li>a namespaced upstream ({@code orcarouter/openai/gpt-4o-mini}) is sent without the prefix
 *       ({@code openai/gpt-4o-mini});</li>
 *   <li>a bare router name ({@code orcarouter/auto}) is sent unchanged, because the API rejects
 *       {@code auto} but accepts {@code orcarouter/auto}.</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
public class OrcaRouterProvider implements LlmProviderService {

    private static final String ORCA_ROUTER_PREFIX = "orcarouter/";

    private final @NonNull OpenAiClient openAiClient;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        return openAiClient.chatCompletion(transformModel(request)).execute();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        OpenAiStreamingHelper.executeStreamingRequest(openAiClient, transformModel(request), handleMessage, handleClose,
                handleError);
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getOrcaRouterErrorObject(throwable, log);
    }

    /**
     * Strips the {@code orcarouter/} prefix before the request leaves Opik. A namespaced upstream
     * (remainder still contains a {@code /}) is forwarded without the prefix; a bare router name is
     * forwarded unchanged. See the class javadoc for the empirically verified behaviour.
     */
    ChatCompletionRequest transformModel(@NonNull ChatCompletionRequest request) {
        String model = request.model();
        if (model == null || !model.startsWith(ORCA_ROUTER_PREFIX)) {
            return request;
        }

        String remainder = model.substring(ORCA_ROUTER_PREFIX.length());
        String outgoingModel = remainder.contains("/") ? remainder : model;

        if (outgoingModel.equals(model)) {
            return request;
        }

        log.debug("Rewrote OrcaRouter model from '{}' to '{}'", model, outgoingModel);
        return ChatCompletionRequest.builder()
                .from(request)
                .model(outgoingModel)
                .build();
    }
}
