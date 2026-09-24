package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client for the OpenRouter Decisions API ({@code POST /api/alpha/decisions}), which serves decisions models
 * such as TypeSafe Jev. Those models aren't reachable through chat completions, so there is no LangChain4j
 * client for them.
 *
 * <p>Errors carry the upstream status: 4xx as {@link ClientErrorException}, 5xx as {@link ServerErrorException},
 * so the online-scoring consumer drops permanent failures (bad request, missing credits) and redelivers
 * transient ones. Rate limits and gateway errors are also retried in-process first.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OpenRouterDecisionsClient {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    /**
     * Retried in-process on top of the 503/504 that {@link RetriableHttpClient} already retries: rate limit,
     * bad gateway, and OpenRouter's 524 (timeout) and 529 (provider overloaded).
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 524, 529);

    private final @NonNull RetriableHttpClient client;
    private final @NonNull @Config("llmProviderClient") LlmProviderClientConfig llmProviderClientConfig;

    public Mono<DecisionsResponse> decide(@NonNull DecisionsRequest request,
            @NonNull LlmProviderClientApiConfig config) {
        var httpRequest = RetriableHttpClient.Request.<DecisionsResponse>builder()
                .requestFunction(httpClient -> httpClient.target(llmProviderClientConfig.getOpenRouterDecisionsUrl()))
                .requestCustomizer(builder -> {
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
        if (response.getStatusInfo().getFamily() == Response.Status.Family.CLIENT_ERROR) {
            throw new ClientErrorException(message, status);
        }
        throw new ServerErrorException(message, status);
    }

    private String extractErrorMessage(Response response) {
        if (!response.hasEntity() || !response.bufferEntity()) {
            return "<no body>";
        }
        try {
            var errorResponse = response.readEntity(DecisionsErrorResponse.class);
            if (errorResponse != null && errorResponse.error() != null
                    && StringUtils.isNotBlank(errorResponse.error().message())) {
                return StringUtils.truncate(errorResponse.error().message(), MAX_ERROR_MESSAGE_LENGTH);
            }
        } catch (RuntimeException parseErrorResponse) {
            // Expected when the body is not the structured error shape; fall back to the raw body.
            log.debug("Failed to parse OpenRouter error response, falling back to raw body", parseErrorResponse);
        }
        try {
            return StringUtils.truncate(response.readEntity(String.class), MAX_ERROR_MESSAGE_LENGTH);
        } catch (RuntimeException unreadable) {
            return "<unreadable body>";
        }
    }
}
