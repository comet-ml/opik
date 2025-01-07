package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
class LlmProviderOpenAi implements LlmProviderService {
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final OpenAiClient openAiClient;

    @Inject
    public LlmProviderOpenAi(LlmProviderClientConfig llmProviderClientConfig, String apiKey) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.openAiClient = newOpenAiClient(apiKey);
    }

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
        openAiClient.chatCompletion(request)
                .onPartialResponse(handleMessage)
                .onComplete(handleClose)
                .onError(handleError)
                .execute();
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public @NonNull Optional<ErrorMessage> getLlmProviderError(Throwable runtimeException) {
        if (runtimeException instanceof OpenAiHttpException openAiHttpException) {
            return Optional.of(new ErrorMessage(openAiHttpException.code(), openAiHttpException.getMessage()));
        }

        return Optional.empty();
    }

    /**
     * At the moment, openai4j client and also langchain4j wrappers, don't support dynamic API keys. That can imply
     * an important performance penalty for next phases. The following options should be evaluated:
     * - Cache clients, but can be unsafe.
     * - Find and evaluate other clients.
     * - Implement our own client.
     * TODO as part of : <a href="https://comet-ml.atlassian.net/browse/OPIK-522">OPIK-522</a>
     */
    private OpenAiClient newOpenAiClient(String apiKey) {
        var openAiClientBuilder = OpenAiClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .ifPresent(baseUrl -> {
                    if (StringUtils.isNotBlank(baseUrl)) {
                        openAiClientBuilder.baseUrl(baseUrl);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> openAiClientBuilder.callTimeout(callTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getWriteTimeout())
                .ifPresent(writeTimeout -> openAiClientBuilder.writeTimeout(writeTimeout.toJavaDuration()));
        return openAiClientBuilder
                .openAiApiKey(apiKey)
                .build();
    }
}
