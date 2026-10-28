package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.AuthTokenException;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterDecisionModel;
import com.comet.opik.infrastructure.llm.vertexai.LlmProviderVertexAI.CutOffBeforeAnsweringException;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.utils.ChunkedOutputHandlers;
import com.comet.opik.utils.HttpStatusRetryability;
import com.fasterxml.jackson.core.JacksonException;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.common.base.Throwables;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.chat.ChatModel;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

@Singleton
@Slf4j
public class ChatCompletionService {
    public static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";
    public static final String UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER = "Unsupported feature for the selected LLM provider";
    public static final String ERROR_EMPTY_MESSAGES = "messages cannot be empty";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderFactory llmProviderFactory;
    private final ParamResolver paramResolver;
    private final ProviderErrorMessageValidator providerErrorMessageValidator;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull ParamResolver paramResolver,
            @NonNull ProviderErrorMessageValidator providerErrorMessageValidator) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.llmProviderFactory = llmProviderFactory;
        this.paramResolver = paramResolver;
        this.providerErrorMessageValidator = providerErrorMessageValidator;
        this.retryPolicy = newRetryPolicy();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest rawRequest, @NonNull String workspaceId) {
        var normalized = MessageContentNormalizer.normalizeRequest(rawRequest);
        rejectDecisionModel(normalized.model());

        var llmProviderClient = llmProviderFactory.getService(workspaceId, normalized.model());
        // must be final or effectively final for lambda
        var request = resolve(normalized, llmProviderClient);
        llmProviderClient.validateRequest(request);

        ChatCompletionResponse chatCompletionResponse;
        try {
            log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            chatCompletionResponse = withRetry(() -> llmProviderClient.generate(request, workspaceId));
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);
            failIfRequestRefused(runtimeException);

            getLlmProviderError(llmProviderClient, runtimeException)
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            failIfProviderReportedHttpStatus(runtimeException);

            logProviderFailure(runtimeException);
            throw new InternalServerErrorException(buildDetailedErrorMessage(runtimeException),
                    ProviderFailures.redacted(runtimeException));
        }

        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public void createAndStreamResponse(
            @NonNull ChatCompletionRequest rawRequest,
            @NonNull String workspaceId,
            @NonNull ChunkedOutputHandlers handlers) {
        var normalized = MessageContentNormalizer.normalizeRequest(rawRequest);
        rejectDecisionModel(normalized.model());

        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId,
                normalized.model());

        var llmProviderClient = llmProviderFactory.getService(workspaceId, normalized.model());
        var request = resolve(normalized, llmProviderClient);
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
        var resolved = paramResolver.resolve(modelParameters);
        logChanges(resolved.request().name(), resolved.changes());
        var languageModelClient = getLanguageModel(workspaceId, resolved.request(), chatRequest);

        ChatResponse chatResponse;
        try {
            log.info("Initiating chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            chatResponse = withRetry(() -> languageModelClient.chat(chatRequest));
            log.info("Completed chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            return chatResponse;
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);
            failIfRequestRefused(runtimeException);

            // Report the status the provider actually sent, same as create() and the streaming handler.
            // BaseRedisSubscriber classifies a ClientErrorException by that status, so a truthful 429 is
            // redelivered and a truthful 400 is retired; this no longer has to misreport either.
            //
            // Only a wire status is used. The provider mappers synthesize one when they cannot parse the body
            // (CustomLlm 400, OpenAi 500) and nothing downstream can tell that from a real 400, so consulting
            // them would retire every unparseable CustomLlm failure on its first delivery. An absent status
            // falls through to 500 and stays retryable: burning maxRetries on a doomed request costs attempts,
            // losing an unknown failure costs the evaluation.
            var status = findProviderHttpStatus(runtimeException)
                    .orElse(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());

            logProviderFailure(runtimeException);

            var detail = buildDetailedErrorMessage(runtimeException);
            // findProviderHttpStatus only yields error statuses, so these two families are exhaustive. The
            // cause is carried redacted: the consumers log this exception, and the provider stack is the
            // diagnostic, but the provider's text must not reach the logs.
            var cause = ProviderFailures.redacted(runtimeException);
            if (familyOf(status) == Response.Status.Family.CLIENT_ERROR) {
                throw new ClientErrorException(detail, status, cause);
            }
            throw new ServerErrorException(detail, status, cause);
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

    // The wrap-up call of a tool loop sends no tool specs but replays the tool calls, so it has to reach the model the
    // way the rounds before it did.
    private static boolean usesTools(ChatRequest chatRequest) {
        return CollectionUtils.isNotEmpty(chatRequest.toolSpecifications())
                || chatRequest.messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage
                        || message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests());
    }

    /**
     * Runs a provider call under the configured retry budget, retrying only a failure another attempt could fix.
     * langchain4j's {@code RetryPolicy.withRetry} isn't used for the loop: it logs every failed attempt with its
     * throwable, whose message is what the provider answered. Only its backoff is used.
     */
    private <T> T withRetry(Supplier<T> call) {
        // maxAttempts has always been the number of retries after the first attempt
        int maxRetries = llmProviderClientConfig.getMaxAttempts();
        for (int retry = 0;; retry++) {
            try {
                return call.get();
            } catch (RuntimeException failure) {
                if (retry >= maxRetries || !isRetriable(failure)) {
                    throw failure;
                }
                log.warn("Retrying the LLM provider call, remaining retries '{}' of '{}', error type '{}'",
                        maxRetries - retry, maxRetries, ProviderFailures.rootType(failure));
                retryPolicy.sleep(retry);
            }
        }
    }

    /**
     * Whether another attempt could succeed, matched explicitly, as an attempt can repeat a call the provider already
     * acted on: a transient status the provider sent, see {@link HttpStatusRetryability#isTransient}, a transport
     * failure, or a failure the provider's client marks as retriable. Anything else, an unrecognised failure included,
     * fails on its first attempt. Never for a failure langchain4j classified as non-retriable, a feature the provider
     * can't serve, or a request Opik refused to send.
     *
     * <p>The status wins over the rest of the chain: a status that can never succeed isn't retried, whatever wraps it.
     * Whether an exhausted failure is redelivered is decided apart from this, by {@code BaseRedisSubscriber} from the
     * status scoreTrace throws.
     */
    private boolean isRetriable(RuntimeException failure) {
        if (failure instanceof NonRetriableException || isUnsupportedFeature(failure) || isRequestRefused(failure)) {
            return false;
        }
        return findProviderHttpStatus(failure)
                .map(HttpStatusRetryability::isTransient)
                .orElseGet(() -> isTransportFailure(failure) || isMarkedRetriable(failure));
    }

    /**
     * A failure to reach the provider or to read its reply, such as a socket timeout, a refused connection or a reset
     * one. Not a destination the guard refused, which no attempt can reach, nor a reply that couldn't be parsed:
     * Jackson's parse failures are {@link IOException}s too.
     */
    private static boolean isTransportFailure(Throwable throwable) {
        return !DestinationGuardDns.isRefused(throwable) && ExceptionUtils.getThrowableList(throwable).stream()
                .anyMatch(cause -> cause instanceof IOException && !(cause instanceof JacksonException));
    }

    /** A failure the provider's client itself marks as worth another attempt. */
    private static boolean isMarkedRetriable(Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream()
                .anyMatch(cause -> cause instanceof RetriableException
                        || cause instanceof OpenAIRetryableException
                        || cause instanceof AuthTokenException tokenFailure && tokenFailure.isRetriable()
                        || cause instanceof ApiException apiException && apiException.isRetryable());
    }

    /** A feature the selected provider cannot serve, so no number of attempts will change the answer. */
    private boolean isUnsupportedFeature(RuntimeException runtimeException) {
        return findUnsupportedFeature(runtimeException).isPresent();
    }

    /** A request Opik refused to send, so no attempt can reach the provider. */
    private boolean isRequestRefused(RuntimeException runtimeException) {
        return findRequestRefused(runtimeException).isPresent();
    }

    // After the key lookup, because the key decides the route, and a route can refuse what the model takes.
    private ChatCompletionRequest resolve(ChatCompletionRequest request, LlmProviderService llmProviderClient) {
        var resolved = paramResolver.resolve(request, llmProviderClient.paramsRoute());
        logChanges(resolved.request().model(), resolved.changes());
        return resolved.request();
    }

    private void logChanges(String model, List<ParamResolver.ParamChange> changes) {
        if (!changes.isEmpty()) {
            log.debug("Request to model '{}' changed: {}", model, changes);
        }
    }

    /**
     * Decisions models (TypeSafe Jev) resolve to OpenRouter so online scoring finds the workspace key, but they
     * only answer through the Decisions API: OpenRouter rejects them on chat completions. Fail here with a 400
     * instead of spending a provider round trip on a call that can't succeed.
     */
    private void rejectDecisionModel(String model) {
        if (OpenRouterDecisionModel.isDecisionModel(model)) {
            throw new BadRequestException(
                    "Decisions models can't be used for chat completions, model '%s'".formatted(model));
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
     * Walks the cause chain, so it matches whether the exception is thrown bare or wrapped by a provider client.
     * {@code ExceptionUtils} stops at the first already-visited throwable, so a self-referencing cause chain
     * terminates rather than looping.
     */
    private Optional<UnsupportedFeatureException> findUnsupportedFeature(Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream()
                .filter(UnsupportedFeatureException.class::isInstance)
                .map(UnsupportedFeatureException.class::cast)
                .findFirst();
    }

    /**
     * Building the client can already refuse the request (a Vertex AI location or token_uri), before scoreTrace's
     * try, so that refusal is mapped here; anything else propagates as before.
     */
    private ChatModel getLanguageModel(String workspaceId, LlmAsJudgeModelParameters modelParameters,
            ChatRequest chatRequest) {
        try {
            return usesTools(chatRequest)
                    ? llmProviderFactory.getToolCallingLanguageModel(workspaceId, modelParameters)
                    : llmProviderFactory.getLanguageModel(workspaceId, modelParameters);
        } catch (RuntimeException runtimeException) {
            failIfRequestRefused(runtimeException);
            throw runtimeException;
        }
    }

    /**
     * Opik's own HTTP client refused the request before sending it, e.g. a destination the guard doesn't allow. The
     * provider was never reached, so this is the caller's 400, never a 500, and its message is already user-facing.
     * Logged by the client that refused it.
     */
    private void failIfRequestRefused(RuntimeException runtimeException) {
        findRequestRefused(runtimeException).ifPresent(requestRefused -> {
            var message = requestRefused.getMessage();
            throw new BadRequestException(
                    message,
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message))
                            .build(),
                    runtimeException);
        });
    }

    /** Walks the cause chain like {@link #findUnsupportedFeature}. */
    private Optional<ProviderRequestRefusedException> findRequestRefused(Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream()
                .filter(ProviderRequestRefusedException.class::isInstance)
                .map(ProviderRequestRefusedException.class::cast)
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
     * throws bare or wraps. {@link HttpException} is searched for across the whole chain before any typed exception
     * is considered, because it carries the upstream code verbatim: langchain4j's
     * {@code ExceptionMapper} raises {@code InternalServerException(HttpException(503))}, and taking the outermost
     * match would collapse that 503 into the flat 500 the typed exception implies.
     *
     * <p>Review finding on #8170 asked whether an outer wrapper's status can shadow a nested provider one, since
     * {@code ExceptionUtils} lists the chain outermost-first. It cannot: {@code HttpException}'s only constructor is
     * {@code (int, String)}, so it never carries a cause and is always terminal in the chain. At most one can appear,
     * and the {@code findFirst} here is therefore not a precedence choice between rival wire statuses.
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
     * prevent a drop, never cause one. That costs reporting fidelity — such a failure is reported as a flat 500
     * rather than, say, the 503 GAX translated — and it is kept deliberately: returning the status regardless would
     * let a GAX-retryable client-error code that {@link HttpStatusRetryability} does not carve out (ABORTED maps to
     * 409, for instance) be classified permanent, and both this fail-fast and {@code BaseRedisSubscriber} would then
     * drop an evaluation GAX itself says is worth retrying. Trading a precise status for that is not worth it. Scoped deliberately: {@link #findProviderHttpStatus} consults a real
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
        logProviderFailure(runtimeException);

        // the redacted cause also marks the message as the provider's, so a server error is logged without it
        var cause = ProviderFailures.redacted(runtimeException);
        if (familyOf(llmProviderError.getCode()) == Response.Status.Family.CLIENT_ERROR) {
            throw new ClientErrorException(llmProviderError.getMessage(), llmProviderError.getCode(), cause);
        }

        throw new ServerErrorException(llmProviderError.getMessage(), llmProviderError.getCode(), cause);
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

            var requestRefused = findRequestRefused(throwable);
            if (requestRefused.isPresent()) {
                handlers.handleError(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                        requestRefused.get().getMessage()));
                return;
            }

            Optional<ErrorMessage> providerError = getLlmProviderError(llmProviderClient, throwable);

            if (providerError.isPresent()) {
                logProviderFailure(throwable);
                handlers.handleError(providerError.get());
            } else {

                if (throwable instanceof BadRequestException userMessage) {
                    logProviderFailure(userMessage);
                    handlers.handleError(
                            new ErrorMessage(userMessage.getResponse().getStatus(), userMessage.getMessage()));
                    return;
                }

                // Same recovery as the non-streaming paths: an unparsed envelope must not downgrade a provider 4xx to
                // the 500 that a bare ErrorMessage(String) defaults to.
                var providerStatus = findProviderHttpStatus(throwable);
                if (providerStatus.isPresent()) {
                    logProviderFailure(throwable);
                    handlers.handleError(new ErrorMessage(providerStatus.get(),
                            buildDetailedErrorMessage(throwable)));
                    return;
                }

                logUnexpectedProviderFailure(throwable);

                handlers.handleError(new ErrorMessage(buildDetailedErrorMessage(throwable)));
            }
        };
    }

    /** A failure the provider's status or envelope explains, so its type is enough: see {@link ProviderFailures}. */
    private static void logProviderFailure(Throwable throwable) {
        log.warn("{}, error type '{}'", UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, ProviderFailures.rootType(throwable));
    }

    /**
     * A failure no provider status or envelope explains, with its stack and without any text the provider sent: see
     * {@link ProviderFailures}.
     */
    private static void logUnexpectedProviderFailure(Throwable throwable) {
        log.error("{}, error type '{}'", UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, ProviderFailures.rootType(throwable),
                ProviderFailures.redacted(throwable));
    }

    private Optional<ErrorMessage> getLlmProviderError(LlmProviderService llmProviderClient, Throwable throwable) {
        return llmProviderClient.getLlmProviderError(throwable).map(providerErrorMessageValidator::validate);
    }

    /**
     * Only text from a failure Opik raised is added to the generic message: what a provider answered reaches a caller
     * solely through a documented error envelope, see {@link ProviderErrorMessageValidator}.
     */
    private static final String SERVICE_UNREACHABLE = "Service is unreachable. Please check the provider URL.";

    private String buildDetailedErrorMessage(Throwable throwable) {
        return extractErrorDetails(throwable)
                .filter(StringUtils::isNotBlank)
                .map(details -> "%s: %s".formatted(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, details))
                .orElse(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
    }

    /**
     * Opik's own validation and failures, which no provider client raises. Only the message Opik composed is used,
     * never that of a cause below it: a network or provider cause can describe the destination or echo its reply.
     */
    private static Optional<Throwable> findRaisedByOpik(Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream()
                .filter(cause -> cause instanceof WebApplicationException
                        || cause instanceof AuthTokenException
                        || cause instanceof CutOffBeforeAnsweringException)
                .findFirst();
    }

    /** The message of a failure Opik raised, which wins over its cause; a blank one adds nothing. */
    private Optional<String> extractErrorDetails(Throwable throwable) {
        var raisedByOpik = findRaisedByOpik(throwable);
        if (raisedByOpik.isPresent()) {
            return raisedByOpik.map(Throwable::getMessage);
        }
        return switch (Throwables.getRootCause(throwable)) {
            // the connection error names the resolved address, which stays server-side
            case ConnectException connectException -> Optional.of(SERVICE_UNREACHABLE);
            case ClosedChannelException closedChannelException -> Optional.of(SERVICE_UNREACHABLE);
            default -> Optional.empty();
        };
    }
}
