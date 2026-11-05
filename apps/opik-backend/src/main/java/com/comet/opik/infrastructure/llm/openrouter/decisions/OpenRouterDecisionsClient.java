package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.comet.opik.infrastructure.net.LoggableUrl;
import com.comet.opik.utils.RetryUtils;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;

/**
 * Client for the OpenRouter Decisions API ({@code POST /api/alpha/decisions}), which serves decisions models
 * such as TypeSafe Jev. Those models aren't reachable through chat completions, so there is no LangChain4j
 * client for them.
 *
 * <p>Error messages keep only a documented OpenRouter error, checked by {@link ProviderErrorMessageValidator} as on
 * the chat path; any other upstream text is dropped. Errors carry the upstream status: 4xx as {@link ClientErrorException}, 5xx as {@link ServerErrorException}
 * and anything else (e.g. a redirect from a misconfigured URL) as {@link WebApplicationException}, so the
 * online-scoring consumer drops permanent failures (bad request, missing credits) and redelivers transient ones.
 * Rate limits and gateway errors are also retried in-process first.
 *
 * <p>Provided by {@link com.comet.opik.infrastructure.llm.openrouter.OpenRouterModule} rather than injected
 * directly: the {@code @Config} qualifier has to sit on a hand-written parameter, since the Docker build compiles
 * without {@code lombok.config} and Lombok then drops it from generated constructors.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenRouterDecisionsClient {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);
    private static final String CHAT_API_SUFFIX = "/v1";
    private static final String DECISIONS_API_SUFFIX = "/alpha/decisions";

    /**
     * Retried in-process on top of the 503/504 that {@link RetriableHttpClient} already retries: rate limit, bad
     * gateway and OpenRouter's 529 (provider overloaded). Not OpenRouter's 524 (timeout): the timed-out call may
     * still have run and been billed, so it is left to the consumer's redelivery alone instead of both layers.
     *
     * <p>Every status outside this set fails on the first attempt but keeps its wire status on the thrown exception,
     * and the consumer decides redelivery from that status ({@link com.comet.opik.utils.HttpStatusRetryability}):
     * 408 and 425 are redelivered like any 5xx, other 4xx are dropped as permanent. Adding a status here only adds
     * in-process attempts, each one a billed call.
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 529);

    private final @NonNull RetriableHttpClient client;
    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;
    private final @NonNull ProviderBaseUrlGuard providerBaseUrlGuard;
    private final @NonNull ProviderErrorMessageValidator providerErrorMessageValidator;

    public Mono<DecisionsResponse> decide(@NonNull DecisionsRequest request,
            @NonNull LlmProviderClientApiConfig config) {
        var openRouterUrl = StringUtils.isNotBlank(config.baseUrl())
                ? config.baseUrl()
                : llmProviderClientConfig.getOpenRouterUrl();
        var decisionsUrl = decisionsUrl(openRouterUrl);
        var httpRequest = RetriableHttpClient.Request.<DecisionsResponse>builder()
                .requestFunction(httpClient -> httpClient.target(decisionsUrl))
                .requestCustomizer(builder -> {
                    builder.property(ClientProperties.FOLLOW_REDIRECTS, false)
                            .property(DestinationGuardDnsResolver.PROPERTY, providerBaseUrlGuard.destinationGuard());
                    Optional.ofNullable(config.headers()).orElse(Map.of()).forEach(builder::header);
                    builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
                })
                .retryPolicy(RetryUtils.handleHttpErrors(
                        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).orElse(DEFAULT_MAX_ATTEMPTS),
                        Duration.ofMillis(llmProviderClientConfig.getDelayMillis()),
                        MAX_BACKOFF))
                .body(Entity.json(request))
                .connectTimeout(Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                        .map(io.dropwizard.util.Duration::toJavaDuration).orElse(null))
                .readTimeout(Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                        .map(io.dropwizard.util.Duration::toJavaDuration).orElse(null))
                .responseFunction(this::processResponse)
                .build();
        return client.executePostWithRetry(httpRequest);
    }

    static String decisionsUrl(@NonNull String openRouterUrl) {
        var url = StringUtils.stripEnd(openRouterUrl.trim(), "/");
        if (!url.endsWith(CHAT_API_SUFFIX)) {
            throw new DecisionsUrlException(("Decision models cannot use the OpenRouter base URL '%s'. The base URL"
                    + " must end with %s, for example https://openrouter.ai/api/v1.").formatted(
                            LoggableUrl.of(openRouterUrl), CHAT_API_SUFFIX));
        }
        return StringUtils.removeEnd(url, CHAT_API_SUFFIX) + DECISIONS_API_SUFFIX;
    }

    private DecisionsResponse processResponse(Response response) {
        int status = response.getStatus();
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var body = response.hasEntity() && response.bufferEntity()
                    ? response.readEntity(DecisionsResponse.class)
                    : null;
            if (body == null || body.answers() == null) {
                throw new InternalServerErrorException(
                        "OpenRouter Decisions API returned an empty response, status '%s'".formatted(status));
            }
            return body;
        }

        var message = "OpenRouter Decisions API request failed, status '%s', message '%s'"
                .formatted(status, extractErrorMessage(response));
        if (RETRYABLE_STATUSES.contains(status)) {
            throw new RetryUtils.RetryableHttpException(message, status);
        }
        switch (response.getStatusInfo().getFamily()) {
            case CLIENT_ERROR -> throw new ClientErrorException(message, status);
            // ServerErrorException rejects any non-5xx status with an IllegalArgumentException.
            case SERVER_ERROR -> throw new ServerErrorException(message, status);
            default -> throw new WebApplicationException(message, status);
        }
    }

    private String extractErrorMessage(Response response) {
        if (!response.hasEntity() || !response.bufferEntity()) {
            return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
        }
        try {
            var errorResponse = response.readEntity(OpenRouterErrorMessage.class);
            if (errorResponse != null && errorResponse.error() != null && errorResponse.isDocumented()) {
                return providerErrorMessageValidator.validate(errorResponse.toErrorMessage()).getMessage();
            }
        } catch (RuntimeException parseErrorResponse) {
            log.debug("Failed to parse OpenRouter error response, error type '{}'",
                    parseErrorResponse.getClass().getSimpleName());
        }
        return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
    }
}
