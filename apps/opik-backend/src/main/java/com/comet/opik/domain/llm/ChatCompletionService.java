package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.ChunkedOutputHandlers;
import com.google.common.base.Throwables;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
import java.util.function.Consumer;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

@Singleton
@Slf4j
public class ChatCompletionService {
    public static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";
    public static final String UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER = "Unsupported feature for the selected LLM provider";
    public static final String ERROR_EMPTY_MESSAGES = "messages cannot be empty";

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

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest rawRequest, @NonNull String workspaceId) {
        // must be final or effectively final for lambda
        var request = MessageContentNormalizer.normalizeRequest(rawRequest);

        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        llmProviderClient.validateRequest(request);

        ChatCompletionResponse chatCompletionResponse;
        try {
            log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            chatCompletionResponse = retryPolicy.withRetry(() -> llmProviderClient.generate(request, workspaceId));
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);

            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(buildDetailedErrorMessage(runtimeException), runtimeException);
        }

        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public void createAndStreamResponse(
            @NonNull ChatCompletionRequest rawRequest,
            @NonNull String workspaceId,
            @NonNull ChunkedOutputHandlers handlers) {
        var request = MessageContentNormalizer.normalizeRequest(rawRequest);

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
            @NonNull LlmAsJudgeModelParameters modelParameters,
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
            failIfUnsupportedFeature(runtimeException);

            LlmProviderService provider = llmProviderFactory.getService(workspaceId, modelParameters.name());

            Optional<ErrorMessage> providerError = provider.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(buildDetailedErrorMessage(runtimeException), runtimeException);
        }
    }

    /**
     * langchain4j raises {@link UnsupportedFeatureException} when the request asks for a capability the selected
     * provider does not implement — e.g. {@code ToolChoice.REQUIRED} against Vertex AI Gemini. The provider is never
     * reached, so {@code getLlmProviderError} has nothing to map and the call used to surface as a 500. That is
     * misleading on two counts: nothing failed server-side, and no amount of retrying can make it succeed. Report it
     * as a 400 so clients get an actionable error and the online-scoring consumers treat it as terminal instead of
     * burning their retry budget on it.
     */
    private void failIfUnsupportedFeature(RuntimeException runtimeException) {
        if (ExceptionUtils.indexOfType(runtimeException, UnsupportedFeatureException.class) < 0) {
            return;
        }

        log.warn(UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER, runtimeException);
        throw new BadRequestException(
                buildDetailedErrorMessage(UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER, runtimeException),
                runtimeException);
    }

    private void failHandlingLLMProviderError(RuntimeException runtimeException, ErrorMessage llmProviderError) {
        log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);

        if (familyOf(llmProviderError.getCode()) == Response.Status.Family.CLIENT_ERROR) {
            throw new ClientErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
        }

        throw new ServerErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
    }

    private RetryUtils.RetryPolicy newRetryPolicy() {
        var retryPolicyBuilder = RetryUtils.retryPolicyBuilder();
        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).ifPresent(retryPolicyBuilder::maxRetries);
        Optional.ofNullable(llmProviderClientConfig.getJitterScale()).ifPresent(retryPolicyBuilder::jitterScale);
        Optional.ofNullable(llmProviderClientConfig.getBackoffExp()).ifPresent(retryPolicyBuilder::backoffExp);
        return retryPolicyBuilder.delayMillis(llmProviderClientConfig.getDelayMillis()).build();
    }

    private Consumer<Throwable> getErrorHandler(ChunkedOutputHandlers handlers, LlmProviderService llmProviderClient) {
        return throwable -> {
            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(throwable);

            if (providerError.isPresent()) {
                log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
                handlers.handleError(providerError.get());
            } else {

                if (throwable instanceof BadRequestException userMessage) {
                    log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, userMessage);
                    handlers.handleError(
                            new ErrorMessage(userMessage.getResponse().getStatus(), userMessage.getMessage()));
                    return;
                }

                // Same rationale as failIfUnsupportedFeature: an unsupported capability is a caller problem, not a
                // server fault, so the stream reports it as a 400 rather than the default 500.
                if (ExceptionUtils.indexOfType(throwable, UnsupportedFeatureException.class) >= 0) {
                    log.warn(UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER, throwable);
                    handlers.handleError(new ErrorMessage(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            buildDetailedErrorMessage(UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER, throwable)));
                    return;
                }

                log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);

                var errorMessage = new ErrorMessage(buildDetailedErrorMessage(throwable));
                handlers.handleError(errorMessage);
            }
        };
    }

    /**
     * Builds a detailed error message by combining the base error message with exception details.
     * Extracts meaningful error information from the exception chain.
     *
     * @param throwable the exception to extract details from
     * @return a detailed error message combining the base message with exception details
     */
    private String buildDetailedErrorMessage(Throwable throwable) {
        return buildDetailedErrorMessage(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
    }

    private String buildDetailedErrorMessage(String baseMessage, Throwable throwable) {
        String exceptionDetails = extractErrorDetails(throwable);
        if (StringUtils.isNotBlank(exceptionDetails)) {
            return baseMessage + ": " + exceptionDetails;
        }
        return baseMessage;
    }

    /**
     * Extracts meaningful error details from an exception chain.
     * Walks through the exception chain to find the most informative error message,
     * preferring root causes over wrapper exceptions.
     * Provides user-friendly messages for common exception types.
     *
     * @param throwable the exception to extract details from
     * @return the extracted error details, or null if no meaningful details found
     */
    private String extractErrorDetails(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Throwable rootCause = Throwables.getRootCause(throwable);

        // Use the most specific exception (root cause if available)
        Throwable exceptionToHandle = (rootCause != throwable) ? rootCause : throwable;

        // Provide user-friendly messages based on exception type
        return switch (exceptionToHandle) {
            case ConnectException connectException -> {
                // Extract host/URL from the exception message if available
                String message = connectException.getMessage();
                if (message != null && message.contains("Connection refused")) {
                    yield "Service is unreachable. Please check the provider URL.";
                }
                yield "Service is unreachable: " + message;
            }
            case ClosedChannelException closedChannelException ->
                "Service is unreachable. Please check the provider URL.";
            default -> {
                // For other exceptions, use the exception message
                String message = exceptionToHandle.getMessage();
                if (StringUtils.isNotBlank(message)) {
                    yield message;
                }
                // Fallback to exception class name if no message
                yield exceptionToHandle.getClass().getSimpleName();
            }
        };
    }
}
