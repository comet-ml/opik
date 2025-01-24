package com.comet.opik.domain.llm;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.ChunkedOutputHandlers;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;
import java.util.function.Consumer;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

@Singleton
@Slf4j
public class ChatCompletionService {
    public static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";
    public static final String ERROR_EMPTY_MESSAGES = "messages cannot be empty";
    public static final String ERROR_NO_COMPLETION_TOKENS = "maxCompletionTokens cannot be null";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderFactory llmProviderFactory;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderFactory llmProviderFactory) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.llmProviderFactory = llmProviderFactory;
        this.retryPolicy = newRetryPolicy();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        llmProviderClient.validateRequest(request);

        ChatCompletionResponse chatCompletionResponse;
        try {
            log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            chatCompletionResponse = retryPolicy.withRetry(() -> llmProviderClient.generate(request, workspaceId));
            log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        } catch (RuntimeException runtimeException) {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            llmProviderClient.getLlmProviderError(runtimeException).ifPresent(llmProviderError -> {
                if (familyOf(llmProviderError.getCode()) == Response.Status.Family.CLIENT_ERROR) {
                    throw new ClientErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
                }

                throw new ServerErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
            });

            throw new InternalServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        }

        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public void createAndStreamResponse(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull ChunkedOutputHandlers handlers) {
        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());

        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());

        llmProviderClient.generateStream(
                request,
                workspaceId,
                handlers::handleMessage,
                handlers::handleClose,
                getErrorHandler(handlers, llmProviderClient));

        log.info("Created and streaming chat completions, workspaceId '{}', model '{}'", workspaceId,
                request.model());
    }

    public ChatResponse scoreTrace(@NonNull ChatRequest chatRequest,
            @NonNull AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters,
            @NonNull String workspaceId) {
        var languageModelClient = llmProviderFactory.getLanguageModel(workspaceId, modelParameters);

        ChatResponse chatResponse;
        try {
            log.info("Initiating chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            chatResponse = retryPolicy
                    .withRetry(() -> languageModelClient.chat(chatRequest));
            log.info("Completed chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            return chatResponse;
        } catch (RuntimeException runtimeException) {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        }
    }

    private RetryUtils.RetryPolicy newRetryPolicy() {
        var retryPolicyBuilder = RetryUtils.retryPolicyBuilder();
        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).ifPresent(retryPolicyBuilder::maxAttempts);
        Optional.ofNullable(llmProviderClientConfig.getJitterScale()).ifPresent(retryPolicyBuilder::jitterScale);
        Optional.ofNullable(llmProviderClientConfig.getBackoffExp()).ifPresent(retryPolicyBuilder::backoffExp);
        return retryPolicyBuilder
                .delayMillis(llmProviderClientConfig.getDelayMillis())
                .build();
    }

    private Consumer<Throwable> getErrorHandler(
            ChunkedOutputHandlers handlers, LlmProviderService llmProviderClient) {
        return throwable -> {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);

            var errorMessage = llmProviderClient.getLlmProviderError(throwable)
                    .orElse(new ErrorMessage(ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER));

            handlers.handleError(errorMessage);
        };
    }
}
