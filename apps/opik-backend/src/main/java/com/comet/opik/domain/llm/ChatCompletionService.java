package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.ChunkedOutputHandlers;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.openai.errors.OpenAIServiceException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
            chatCompletionResponse = retryPolicy.withRetry(
                    () -> failFastOnUnsupportedFeature(() -> llmProviderClient.generate(request, workspaceId)));
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);

            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            failIfProviderReportedHttpStatus(runtimeException);

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
        var errorHandler = getErrorHandler(handlers, llmProviderClient);

        try {
            llmProviderClient.generateStream(
                    request,
                    workspaceId,
                    handlers::handleMessage,
                    handlers::handleClose,
                    errorHandler);
        } catch (UnsupportedFeatureException unsupportedFeature) {
            // Streaming clients get one contract: HTTP 200 with the error delivered in-stream. VertexAI and Gemini
            // already guarantee that by catching everything inside their own boundedElastic task, but
            // OpenAiResponses, OpenAI, CustomLlm and Anthropic run inline, so an unsupported feature raised before
            // the provider engages would otherwise escape as an HTTP status and break the contract for those
            // providers only. Caught by exact type rather than RuntimeException: no retry policy wraps this call, so
            // these arrive unwrapped, and everything else keeps propagating to the resource layer untouched.
            // BadRequestException is deliberately NOT caught: LlmProviderAnthropic.generateStream validates messages
            // inline and throws it, and that must stay a real HTTP 400.
            errorHandler.accept(unsupportedFeature);
            return;
        }

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
            chatResponse = retryPolicy.withRetry(
                    () -> failFastOnPermanentFailure(
                            () -> failFastOnUnsupportedFeature(() -> languageModelClient.chat(chatRequest))));
            log.info("Completed chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            return chatResponse;
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);

            // Deliberately NOT failHandlingLLMProviderError here, unlike create() and the streaming handler:
            // those answer an HTTP caller and want the status verbatim, whereas this answers the online-
            // scoring subscribers, where the thrown TYPE decides retryability. BaseRedisSubscriber matches
            // ClientErrorException by class, so recovering the status here would make the whole 4xx family
            // non-retryable, transient 408s and 429s included.
            //
            // Permanence is decided only from a status the provider actually sent. The mappers synthesize
            // one when they cannot parse the body (CustomLlm 400, OpenAi 500) and nothing downstream can
            // tell that apart from a real 400, so consulting them would drop every unparseable CustomLlm
            // failure on its first delivery. Retrying a permanent error costs maxRetries attempts; dropping
            // an unknown one loses the evaluation, so an absent status stays retryable.
            var wireStatus = findProviderHttpStatus(runtimeException);

            log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);

            if (wireStatus.filter(ChatCompletionService::isPermanentFailure).isPresent()) {
                // The subscriber acks and removes on the first delivery rather than burning
                // maxRetries x pendingMessageDuration on a request whose outcome cannot change.
                throw new ClientErrorException(buildDetailedErrorMessage(runtimeException), wireStatus.get(),
                        runtimeException);
            }
            // Everything else stays a blanket 500 so it lands outside NON_RETRYABLE_EXCEPTIONS and honours
            // onlineScoring.maxRetries -- including transient 4xx, a distinction ClientErrorException
            // cannot express on its own while the subscriber matches it by class.
            throw new InternalServerErrorException(buildDetailedErrorMessage(runtimeException), runtimeException);
        } finally {
            // Close the Vertex client (reused across retries) to release its GAX threads; other providers self-reclaim.
            if (languageModelClient instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("Failed to close language model client", e);
                }
            }
        }
    }

    /**
     * {@link UnsupportedFeatureException} extends {@code LangChain4jException}, not {@code NonRetriableException}, so
     * {@code RetryPolicy.withRetry} treats it like any transient failure and burns the whole retry budget (plus its
     * backoff delays) on a call that can never succeed. Re-throwing it as {@link NonRetriableException} makes
     * {@code withRetry} give up on the first attempt while leaving genuinely transient provider errors retryable. The
     * original exception is kept as the cause, so {@link #failIfUnsupportedFeature} still recognises it downstream.
     */
    private <T> T failFastOnUnsupportedFeature(Callable<T> action) throws Exception {
        return failFastWhen(action, runtimeException -> findUnsupportedFeature(runtimeException).isPresent());
    }

    /**
     * Skips the in-process retries for a provider status that can never succeed, mirroring
     * {@link #failFastOnUnsupportedFeature}. Applied only on the scoreTrace path: {@code create()} answers an HTTP
     * caller, and narrowing its retry behaviour is not this change's business. Mainly reached for VertexAI, whose GAX
     * exceptions langchain4j does not model as {@code NonRetriableException}; the mapped providers already fail fast
     * on their own. The cause is preserved, so the catch block still classifies from the same status.
     */
    private <T> T failFastOnPermanentFailure(Callable<T> action) throws Exception {
        return failFastWhen(action,
                runtimeException -> findProviderHttpStatus(runtimeException)
                        .filter(ChatCompletionService::isPermanentFailure)
                        .isPresent());
    }

    /**
     * Shared mechanism for both fail-fast wrappers: run the action, and rewrap a matching failure as
     * {@link NonRetriableException} so {@code RetryPolicy.withRetry} gives up on the first attempt. Only the
     * predicate differs between the two, so keeping one copy of the propagation means a future change to how
     * NonRetriableException is raised cannot apply to one and not the other. The cause is always preserved.
     */
    private <T> T failFastWhen(Callable<T> action, Predicate<RuntimeException> nonRetriable) throws Exception {
        try {
            return action.call();
        } catch (RuntimeException runtimeException) {
            if (nonRetriable.test(runtimeException)) {
                throw new NonRetriableException(runtimeException);
            }
            throw runtimeException;
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
        var unsupportedFeature = findUnsupportedFeature(runtimeException);
        if (unsupportedFeature.isEmpty()) {
            return;
        }

        var message = buildUnsupportedFeatureMessage(unsupportedFeature.get());
        // Logged without the throwable: this is an expected, deterministic client error, and at production volumes a
        // stack trace per rejection buries the genuine provider failures.
        log.warn(message);
        // The message is carried as an ErrorMessage entity, not just on the exception: Jersey renders
        // WebApplicationException via its Response, so a message-only constructor would return a bodiless 400 and the
        // caller would never learn which capability was rejected.
        throw new BadRequestException(
                message,
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message))
                        .build(),
                runtimeException);
    }

    /**
     * Built from the {@link UnsupportedFeatureException}'s own message rather than the chain's root cause, so the
     * client is told which capability was rejected and nothing deeper in the chain can leak into the response.
     */
    private String buildUnsupportedFeatureMessage(UnsupportedFeatureException unsupportedFeature) {
        String detail = unsupportedFeature.getMessage();
        return StringUtils.isNotBlank(detail)
                ? UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER + ": " + detail
                : UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER;
    }

    /**
     * Walks the cause chain, so it matches whether the exception is thrown bare, wrapped by a provider client, or
     * re-thrown by {@link #failFastOnUnsupportedFeature}. {@code ExceptionUtils} stops at the first already-visited
     * throwable, so a self-referencing cause chain terminates rather than looping.
     */
    private Optional<UnsupportedFeatureException> findUnsupportedFeature(Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream()
                .filter(UnsupportedFeatureException.class::isInstance)
                .map(UnsupportedFeatureException.class::cast)
                .findFirst();
    }

    /**
     * Last resort before the blanket 500. {@code getLlmProviderError} only recognises payloads it can parse into the
     * provider's own error envelope, so it comes back empty for a plain-text body (an upstream proxy's "service
     * temporarily unavailable", a Cloudflare "error code: 1015") or for a typed exception nested under a retry
     * wrapper — and a caller-side fault was then reported as an Opik fault. langchain4j has already classified the
     * response by that point: {@code ExceptionMapper} reads {@link HttpException#statusCode()} and re-raises it as one
     * of its typed exceptions, keeping the {@code HttpException} in the chain. That verdict is recovered here and run
     * through the same {@link #failHandlingLLMProviderError} classification a parsed envelope gets, so a provider 4xx
     * reaches the caller as a 4xx. Failures that never reached HTTP (connection refused, closed channel) carry no
     * status and keep falling through to the 500 below.
     */
    private void failIfProviderReportedHttpStatus(RuntimeException runtimeException) {
        findProviderHttpStatus(runtimeException)
                .ifPresent(status -> failHandlingLLMProviderError(runtimeException,
                        new ErrorMessage(status, buildDetailedErrorMessage(runtimeException))));
    }

    /**
     * Walks the cause chain like {@link #findUnsupportedFeature}, so the status is found whether the provider client
     * throws bare, wraps, or is re-thrown by the retry policy. {@link HttpException} is searched for across the whole
     * chain before any typed exception is considered, because it carries the upstream code verbatim: langchain4j's
     * {@code ExceptionMapper} raises {@code InternalServerException(HttpException(503))}, and taking the outermost
     * match would collapse that 503 into the flat 500 the typed exception implies.
     */
    private Optional<Integer> findProviderHttpStatus(Throwable throwable) {
        List<Throwable> chain = ExceptionUtils.getThrowableList(throwable);

        // Filtered at EACH stage, not once at the end. Filtering only the winner lets a non-error
        // HttpException anywhere in the chain (a 302, or a synthetic 0) satisfy findFirst, suppress the
        // typed fallback, and then be discarded -- losing the real status. That matters most for VertexAI
        // and the OpenAI Responses SDK, whose statuses come only from that fallback.
        return chain.stream()
                .filter(HttpException.class::isInstance)
                .map(HttpException.class::cast)
                .map(HttpException::statusCode)
                .filter(ChatCompletionService::isErrorStatus)
                .findFirst()
                .or(() -> chain.stream()
                        .map(this::canonicalStatusOf)
                        .flatMap(Optional::stream)
                        .filter(ChatCompletionService::isErrorStatus)
                        .findFirst());
    }

    /**
     * {@link HttpException} takes any int, so an upstream that answers outside the error families — or a client that
     * builds one for a failure that never carried a status — must not reach {@link #failHandlingLLMProviderError}:
     * {@code ClientErrorException} and {@code ServerErrorException} both validate the family, and
     * {@code Response.status} rejects anything outside 100-599, so an out-of-family code would leave the catch block
     * as an {@code IllegalArgumentException} instead of the 500 the caller is promised. Anything not recognisably a
     * 4xx or 5xx therefore keeps the existing fall-through.
     */
    private static boolean isErrorStatus(int status) {
        var family = familyOf(status);
        return family == Response.Status.Family.CLIENT_ERROR || family == Response.Status.Family.SERVER_ERROR;
    }

    /**
     * VertexAI is one of two providers whose client raises no {@link HttpException}: the Google Cloud SDK throws GAX
     * {@code ApiException}, which is also not a {@code NonRetriableException}, so without this a permanent Vertex
     * failure consumed the whole retry budget. The status is GAX's own transport-neutral translation, identical for
     * the gRPC and HTTP-JSON transports, rather than a table of our own.
     *
     * <p>An exception GAX marks retryable yields no status, so <em>within this method</em> the mapping can only
     * prevent a drop, never cause one. Scoped deliberately: {@link #findProviderHttpStatus} consults a real
     * {@code HttpException} in the chain first, and a wire status legitimately outranks GAX's {@code isRetryable},
     * which is a configured judgment rather than something the server said. Not reachable for VertexAI in any case —
     * {@code VertexAiGeminiChatModel} goes through the Google Cloud SDK and never produces a langchain4j
     * {@code HttpException} — but that is the intended precedence if it ever were.
     */
    private static Optional<Integer> gaxHttpStatus(ApiException apiException) {
        if (apiException.isRetryable()) {
            return Optional.empty();
        }
        return Optional.ofNullable(apiException.getStatusCode())
                .map(StatusCode::getCode)
                .map(StatusCode.Code::getHttpStatusCode);
    }

    /** 425 Too Early has no {@link Response.Status} constant; 408 and 429 do and are used directly. */
    private static final int TOO_EARLY_STATUS = 425;

    /**
     * 4xx statuses that say "not now", not "not ever", so family alone can't decide retryability.
     * langchain4j agrees on 408 and 429 but maps 425 to a non-retriable {@code InvalidRequestException};
     * we diverge deliberately per RFC 8470, which is safe because retryability is read from the wire
     * status, not the mapped type.
     */
    private static final Set<Integer> TRANSIENT_CLIENT_ERRORS = Set.of(
            Response.Status.REQUEST_TIMEOUT.getStatusCode(),
            TOO_EARLY_STATUS,
            Response.Status.TOO_MANY_REQUESTS.getStatusCode());

    /**
     * Whether this exact request can never succeed, so the caller should give up rather than retry.
     * Server errors are excluded: a 5xx is the textbook retry case. Anything unrecognised is retryable,
     * matching {@code BaseRedisSubscriber}'s "unknown defaults to retryable for safety".
     */
    @VisibleForTesting
    static boolean isPermanentFailure(int status) {
        return familyOf(status) == Response.Status.Family.CLIENT_ERROR
                && !TRANSIENT_CLIENT_ERRORS.contains(status);
    }

    /**
     * The status langchain4j's own exception types stand for, used for providers whose clients raise them without an
     * {@link HttpException} in the chain. {@code ContentFilteredException} is covered by its
     * {@link InvalidRequestException} supertype.
     */
    private Optional<Integer> canonicalStatusOf(Throwable throwable) {
        return switch (throwable) {
            case InvalidRequestException ignored -> Optional.of(Response.Status.BAD_REQUEST.getStatusCode());
            case AuthenticationException ignored -> Optional.of(Response.Status.UNAUTHORIZED.getStatusCode());
            case ModelNotFoundException ignored -> Optional.of(Response.Status.NOT_FOUND.getStatusCode());
            case TimeoutException ignored -> Optional.of(Response.Status.REQUEST_TIMEOUT.getStatusCode());
            case RateLimitException ignored -> Optional.of(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            case InternalServerException ignored -> Optional.of(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            case ApiException apiException -> gaxHttpStatus(apiException);
            case OpenAIServiceException responsesException -> Optional.of(responsesException.statusCode());
            default -> Optional.empty();
        };
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
            // Checked before the provider-error mapper so the classification matches create() and scoreTrace(): if a
            // provider envelope and an unsupported feature ever collide, the deterministic capability failure wins.
            var unsupportedFeature = findUnsupportedFeature(throwable);
            if (unsupportedFeature.isPresent()) {
                var message = buildUnsupportedFeatureMessage(unsupportedFeature.get());
                log.warn(message);
                handlers.handleError(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message));
                return;
            }

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

                // Same recovery as the non-streaming paths: an unparsed envelope must not downgrade a provider 4xx to
                // the 500 that a bare ErrorMessage(String) defaults to.
                var providerStatus = findProviderHttpStatus(throwable);
                if (providerStatus.isPresent()) {
                    log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
                    handlers.handleError(
                            new ErrorMessage(providerStatus.get(), buildDetailedErrorMessage(throwable)));
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
        String exceptionDetails = extractErrorDetails(throwable);
        if (StringUtils.isNotBlank(exceptionDetails)) {
            return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER + ": " + exceptionDetails;
        }
        return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
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
