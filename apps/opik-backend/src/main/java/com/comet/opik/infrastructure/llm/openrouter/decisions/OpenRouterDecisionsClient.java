package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.domain.llm.ProviderFailures;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.http.BodyDeadline;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.ProviderHeaders;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.llm.decisions.DecisionsClient;
import com.comet.opik.infrastructure.llm.decisions.DecisionsRequest;
import com.comet.opik.infrastructure.llm.decisions.DecisionsResponse;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.OPENROUTER_DECISIONS_URL;

/**
 * Client for the OpenRouter Decisions API ({@code POST /api/alpha/decisions}), which serves decisions models
 * such as TypeSafe Jev. Those models aren't reachable through chat completions, so there is no LangChain4j
 * client for them.
 *
 * <p>Requests go to the workspace's OpenRouter {@code base_url}, or else to {@code openRouterDecisionsUrl} as is, or
 * else to {@code openRouterUrl}; a base URL has its trailing {@code /v1} replaced by {@code /alpha/decisions}, and one
 * without it is refused with a 400. The caller validates the workspace's base URL with {@link ProviderBaseUrlGuard},
 * and this client checks each address it connects to, as for a custom provider.
 *
 * <p>Error messages keep only a documented OpenRouter error, checked by {@link ProviderErrorMessageValidator} as on
 * the chat path; any other upstream text is dropped, also from the 503/504 that {@link RetriableHttpClient} retries.
 *
 * <p>Errors carry the upstream status: 4xx as {@link ClientErrorException}, 5xx as {@link ServerErrorException} and
 * anything else (e.g. a redirect from a misconfigured URL) as {@link WebApplicationException}, so the online-scoring
 * consumer drops permanent failures (bad request, missing credits) and redelivers transient ones. Rate limits and
 * gateway errors are also retried in-process first.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenRouterDecisionsClient implements DecisionsClient {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);
    private static final String CHAT_API_SUFFIX = "/v1";
    private static final String DECISIONS_API_SUFFIX = "/alpha/decisions";
    private static final Set<String> URL_SCHEMES = Set.of("http", "https");
    private static final int SUCCESS_BODY_MAX_BYTES = 1024 * 1024;
    private static final Duration DEFAULT_BODY_DEADLINE = Duration.ofSeconds(60);

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

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPEN_ROUTER;
    }

    @Override
    public Mono<DecisionsResponse> decide(@NonNull DecisionsRequest request,
            @NonNull LlmProviderClientApiConfig config) {
        // deferred, so a refused URL fails the Mono and reaches the caller's error mapping
        return Mono.defer(() -> execute(request, config));
    }

    private Mono<DecisionsResponse> execute(DecisionsRequest request, LlmProviderClientApiConfig config) {
        var decisionsUrl = targetUrl(config.baseUrl());
        var httpRequest = RetriableHttpClient.Request.<DecisionsResponse>builder()
                .requestFunction(httpClient -> httpClient.target(decisionsUrl))
                .requestCustomizer(builder -> {
                    builder.property(ClientProperties.FOLLOW_REDIRECTS, false)
                            .property(DestinationGuardDnsResolver.PROPERTY, providerBaseUrlGuard.destinationGuard());
                    builder.headers(requestHeaders(config));
                })
                .retryPolicy(RetryUtils.handleProviderHttpErrors(
                        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).orElse(DEFAULT_MAX_ATTEMPTS),
                        Duration.ofMillis(llmProviderClientConfig.getDelayMillis()),
                        MAX_BACKOFF))
                .body(Entity.json(request))
                .connectTimeout(Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                        .map(io.dropwizard.util.Duration::toJavaDuration).orElse(null))
                .readTimeout(Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                        .map(io.dropwizard.util.Duration::toJavaDuration).orElse(null))
                .responseFunction(this::processResponse)
                .errorMessageFunction(this::extractErrorMessage)
                .build();
        return client.executePostWithRetry(httpRequest);
    }

    /**
     * The stored headers, which replace the ones Opik sets, as on the chat path; the JSON body sets Content-Type.
     * Asks for an uncompressed reply, but the shared client still inflates a compressed one, so every body read is
     * capped too.
     */
    private static MultivaluedMap<String, Object> requestHeaders(LlmProviderClientApiConfig config) {
        var stored = ProviderHeaders.normalized(config.headers());
        var headers = new MultivaluedHashMap<String, Object>();
        if (!stored.containsKey(HttpHeaders.ACCEPT_ENCODING)) {
            headers.putSingle(HttpHeaders.ACCEPT_ENCODING, "identity");
        }
        if (!stored.containsKey(HttpHeaders.AUTHORIZATION)) {
            headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        stored.forEach(headers::putSingle);
        return headers;
    }

    private String targetUrl(String baseUrl) {
        if (StringUtils.isNotBlank(baseUrl)) {
            return decisionsUrl(baseUrl);
        }
        if (StringUtils.isNotBlank(llmProviderClientConfig.getOpenRouterDecisionsUrl())) {
            return llmProviderClientConfig.getOpenRouterDecisionsUrl();
        }
        return decisionsUrl(StringUtils.defaultString(llmProviderClientConfig.getOpenRouterUrl()));
    }

    private String decisionsUrl(String openRouterUrl) {
        URI uri;
        try {
            uri = new URI(openRouterUrl.trim());
        } catch (URISyntaxException invalidUrl) {
            throw new ProviderRequestRefusedException(OPENROUTER_DECISIONS_URL);
        }
        var path = StringUtils.stripEnd(uri.getRawPath(), "/");
        // the scheme compared exactly, as for media URLs; Set.of refuses a null lookup
        if (uri.getScheme() == null || !URL_SCHEMES.contains(uri.getScheme()) || uri.getHost() == null
                || !StringUtils.endsWith(path, CHAT_API_SUFFIX)) {
            throw new ProviderRequestRefusedException(OPENROUTER_DECISIONS_URL);
        }
        // the host and port only: the HTTP client refuses userinfo, which the chat client doesn't send either
        return "%s://%s%s%s%s".formatted(uri.getScheme(), uri.getHost(), uri.getPort() == -1 ? "" : ":" + uri.getPort(),
                StringUtils.removeEnd(path, CHAT_API_SUFFIX) + DECISIONS_API_SUFFIX,
                uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    private DecisionsResponse processResponse(Response response) {
        int status = response.getStatus();
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            DecisionsResponse body;
            try {
                body = response.hasEntity()
                        ? JsonUtils.readValue(readSuccessBody(response), DecisionsResponse.class)
                        : null;
            } catch (IOException | RuntimeException unreadable) {
                // redacted as on the chat path: a parse error can quote the upstream body
                throw new InternalServerErrorException(
                        "OpenRouter Decisions API returned an unreadable response, status '%s'".formatted(status),
                        ProviderFailures.redacted(unreadable));
            }
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
        if (!response.hasEntity()) {
            return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
        }
        OpenRouterErrorMessage errorResponse;
        try {
            errorResponse = JsonUtils.readValue(readErrorBody(response), OpenRouterErrorMessage.class);
        } catch (IOException | RuntimeException unreadable) {
            // the type only: the message can quote the upstream body or name the destination
            log.debug("Failed to read OpenRouter error response, error type '{}'",
                    unreadable.getClass().getSimpleName());
            return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
        }
        if (errorResponse != null && errorResponse.error() != null && errorResponse.isDocumented()) {
            return providerErrorMessageValidator.validate(errorResponse.toErrorMessage()).getMessage();
        }
        return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
    }

    private byte[] readSuccessBody(Response response) throws IOException {
        var bytes = readBody(response, SUCCESS_BODY_MAX_BYTES + 1);
        if (bytes.length > SUCCESS_BODY_MAX_BYTES) {
            throw new IOException("the body is over the size limit");
        }
        return bytes;
    }

    private byte[] readErrorBody(Response response) throws IOException {
        return readBody(response, Math.toIntExact(llmProviderClientConfig.getProviderErrorBodyMaxSize().toBytes()));
    }

    /**
     * At most {@code maxBytes}, read within the read timeout as a whole, as for a custom provider: the base URL can
     * be the workspace's. Closing the body before its end cancels the connection.
     */
    private byte[] readBody(Response response, int maxBytes) throws IOException {
        var within = Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .map(io.dropwizard.util.Duration::toJavaDuration)
                .orElse(DEFAULT_BODY_DEADLINE);
        try (var body = response.readEntity(InputStream.class);
                var deadline = BodyDeadline.closing(body, within)) {
            var bytes = body.readNBytes(maxBytes);
            if (deadline.expired()) {
                throw new IOException("the deadline closed the body");
            }
            return bytes;
        }
    }
}
