package com.comet.opik.domain.llmproviders;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel.OpenAiChatModelBuilder;
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
    private final OpenAiChatModelBuilder chatLanguageModelBuilder;

    @Inject
    public LlmProviderOpenAi(LlmProviderClientConfig llmProviderClientConfig, String apiKey) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.openAiClient = newOpenAiClient(apiKey);
        this.chatLanguageModelBuilder = newChatLanguageModel(apiKey);
    }

    private OpenAiChatModelBuilder newChatLanguageModel(String apiKey) {
        var builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .logRequests(true)
                .logResponses(true);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .ifPresent(baseUrl -> {
                    if (StringUtils.isNotBlank(baseUrl)) {
                        builder.baseUrl(baseUrl);
                    }
                });

        return builder;
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

    @Override
    public ChatResponse structuredResponseChat(@NonNull ChatRequest chatRequest,
            @NonNull AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        var openAiChatModel = chatLanguageModelBuilder.modelName(modelParameters.name());

        Optional.ofNullable(modelParameters.temperature()).ifPresent(openAiChatModel::temperature);

        return openAiChatModel.build().chat(chatRequest);
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
