package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LlmProviderVertexAI implements LlmProviderService {

    private final @NonNull VertexAIClientGenerator llmProviderClientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var mapper = LlmProviderLangChainMapper.INSTANCE;
        var response = llmProviderClientGenerator.generate(config, request).generate(mapper.mapMessages(request));
        return mapper.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        Schedulers.boundedElastic()
                .schedule(() -> llmProviderClientGenerator.newVertexAIStreamingClient(config, request)
                        .generate(LlmProviderLangChainMapper.INSTANCE.mapMessages(request),
                                new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model())));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getGeminiErrorObject(throwable, log);
    }
}
