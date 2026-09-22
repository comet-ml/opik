package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;

import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * HTTP client decorator for the Custom LLM provider that mutates outgoing
 * requests based on optional configuration keys.
 *
 * <p>Current responsibilities:
 * <ul>
 *   <li>Substitutes a {@code {model}} placeholder in the base URL with the model
 *       name from the outgoing request body. Lets a single provider entry serve
 *       many deployments on gateways that bake the deployment into the path
 *       (e.g. Azure APIM {@code /deployments/{deployment}/chat/completions}).</li>
 *   <li>Appends entries from {@code configuration["url_query_params"]}
 *       (JSON-encoded {@code Map<String, String>}) to the outgoing URL as query
 *       parameters.</li>
 *   <li>Adds a custom auth header {@code {name}: {apiKey}} when
 *       {@code configuration["auth_header_name"]} is set. Appends alongside the
 *       default {@code Authorization: Bearer} header unless it is suppressed.</li>
 *   <li>Removes the default {@code Authorization: Bearer <apiKey>} header when
 *       {@code configuration["suppress_default_auth"]} is {@code "true"}. Used
 *       by gateways whose policy rejects an {@code Authorization} header.</li>
 *   <li>Injects a dynamically fetched bearer for token-auth providers ({@code auth_config}):
 *       the token is asked from the shared cache on every request and placed in
 *       {@code Authorization: Bearer} (or raw under the configured {@code auth_header_name}).
 *       Precedence: the fetched token wins — a pre-configured static {@code Authorization}
 *       header is dropped with a warn. A 401/403 from the gateway invalidates the cached token
 *       and retries the request once; for streamed responses only before the first delivered
 *       event.</li>
 * </ul>
 *
 * <p>When none of the relevant config keys are set and no {@code {model}}
 * placeholder is present, the decorator is a pure no-op — preserving the
 * existing Custom LLM provider contract byte-for-byte with what LangChain4j's
 * {@code OpenAiClient} already produced.
 */
@Slf4j
class InterceptingHttpClient implements HttpClient {

    static final String URL_QUERY_PARAMS_CONFIG_KEY = "url_query_params";
    static final String AUTH_HEADER_NAME_CONFIG_KEY = "auth_header_name";
    static final String SUPPRESS_DEFAULT_AUTH_CONFIG_KEY = "suppress_default_auth";
    static final String MODEL_PLACEHOLDER = "{model}";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final TypeReference<Map<String, String>> QUERY_PARAMS_TYPE = new TypeReference<>() {
    };

    // RFC 6750 signals a token problem via WWW-Authenticate error="invalid_token", but the
    // http client abstraction surfaces only status + body; gateways typically echo the marker
    // in the body, so that is the signal available here.
    private static final Pattern TOKEN_REJECTION_HINT = Pattern.compile("invalid_token|expired|revoked",
            Pattern.CASE_INSENSITIVE);

    private final @NonNull HttpClient delegate;
    private final Map<String, String> configuration;
    private final String apiKey;
    private final Supplier<String> tokenSupplier;
    private final Runnable tokenInvalidator;

    InterceptingHttpClient(@NonNull HttpClient delegate, Map<String, String> configuration, String apiKey) {
        this(delegate, configuration, apiKey, null, null);
    }

    /**
     * Normalizes a null {@code configuration} to an empty map so helpers can
     * call {@code configuration.get(...)} unconditionally. Keeps
     * {@code {model}}-only providers (which may carry no new config keys yet
     * still reach {@code mutate()}) safe.
     */
    InterceptingHttpClient(@NonNull HttpClient delegate, Map<String, String> configuration, String apiKey,
            Supplier<String> tokenSupplier, Runnable tokenInvalidator) {
        this.delegate = delegate;
        this.configuration = configuration != null ? configuration : Map.of();
        this.apiKey = apiKey;
        this.tokenSupplier = tokenSupplier;
        this.tokenInvalidator = tokenInvalidator;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        try {
            return delegate.execute(mutate(request));
        } catch (HttpException exception) {
            if (!shouldRetryWithFreshToken(exception)) {
                throw exception;
            }
            invalidateToken();
            // re-mutating asks the supplier again, which now yields a fresh token
            return delegate.execute(mutate(request));
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser,
            ServerSentEventListener listener) {
        if (tokenSupplier == null) {
            delegate.execute(mutate(request), parser, listener);
            return;
        }
        delegate.execute(mutate(request), parser, new RetryOnAuthFailureListener(request, parser, listener));
    }

    /**
     * 401 is unambiguous. A 403 is usually a policy/quota/WAF decision, so reying only when
     * the response body hints the token itself was rejected.
     */
    private boolean shouldRetryWithFreshToken(HttpException exception) {
        if (tokenSupplier == null) {
            return false;
        }
        if (exception.statusCode() == 401) {
            return true;
        }
        return exception.statusCode() == 403
                && exception.getMessage() != null
                && TOKEN_REJECTION_HINT.matcher(exception.getMessage()).find();
    }

    private void invalidateToken() {
        if (tokenInvalidator != null) {
            tokenInvalidator.run();
        }
    }

    /**
     * Retries a streamed request once on 401/403, but only while nothing has been delivered to
     * the downstream listener — once content flowed, a retry would splice two half-responses, so
     * the failure propagates honestly instead.
     */
    @RequiredArgsConstructor
    private class RetryOnAuthFailureListener implements ServerSentEventListener {

        private final HttpRequest originalRequest;
        private final ServerSentEventParser parser;
        private final ServerSentEventListener downstream;
        private final AtomicBoolean delivered = new AtomicBoolean(false);

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            delivered.set(true);
            downstream.onOpen(response);
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            delivered.set(true);
            downstream.onEvent(event, context);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            delivered.set(true);
            downstream.onEvent(event);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!delivered.get() && throwable instanceof HttpException httpException
                    && shouldRetryWithFreshToken(httpException)) {
                invalidateToken();
                try {
                    // retry straight to the downstream listener: a second failure must not retry again
                    delegate.execute(mutate(originalRequest), parser, downstream);
                    return;
                } catch (RuntimeException retryFailure) {
                    downstream.onError(retryFailure);
                    return;
                }
            }
            downstream.onError(throwable);
        }

        @Override
        public void onClose() {
            downstream.onClose();
        }
    }

    private HttpRequest mutate(HttpRequest request) {
        boolean hasPlaceholder = request.url() != null && request.url().contains(MODEL_PLACEHOLDER);
        if (configuration.isEmpty() && !hasPlaceholder && tokenSupplier == null) {
            return request;
        }

        String url = applyModelPlaceholder(request.url(), request.body());
        url = applyQueryParams(url);
        Map<String, List<String>> mutatedHeaders = applyAuthHeaders(request.headers());

        boolean urlChanged = !url.equals(request.url());
        boolean headersChanged = mutatedHeaders != request.headers();
        if (!urlChanged && !headersChanged) {
            return request;
        }

        return HttpRequest.builder()
                .method(request.method())
                .url(url)
                .headers(mutatedHeaders)
                .body(request.body())
                .build();
    }

    /**
     * Substitutes the {@code {model}} placeholder in {@code url} with the
     * {@code "model"} string from the request body. The substitution is raw
     * (no URL encoding) so the deployment name lands as a literal path
     * segment, matching what the gateway expects. Model names containing
     * {@code /} (e.g. HuggingFace-style {@code mistralai/Mistral-7B}) will
     * split into extra path segments — a {@code log.warn} surfaces this so
     * misconfiguration is visible without breaking gateways that legitimately
     * use slashed segments.
     */
    private String applyModelPlaceholder(String url, String body) {
        if (url == null || !url.contains(MODEL_PLACEHOLDER)) {
            return url;
        }
        if (StringUtils.isBlank(body)) {
            log.warn("Base URL contains '{}' but request body is empty; forwarding URL unchanged",
                    MODEL_PLACEHOLDER);
            return url;
        }
        JsonNode root;
        try {
            root = JsonUtils.getJsonNodeFromString(body);
        } catch (UncheckedIOException exception) {
            log.warn("Failed to parse request body as JSON for '{}' substitution; forwarding URL unchanged",
                    MODEL_PLACEHOLDER, exception);
            return url;
        }

        JsonNode modelNode = root == null ? null : root.get("model");
        if (modelNode == null || !modelNode.isTextual() || StringUtils.isBlank(modelNode.asText())) {
            log.warn(
                    "Base URL contains '{}' but request body has no string 'model' field; forwarding URL unchanged",
                    MODEL_PLACEHOLDER);
            return url;
        }
        String modelName = modelNode.asText();
        if (modelName.indexOf('/') >= 0) {
            log.warn(
                    "Substituted model '{}' contains '/'; the resulting URL will gain extra path segments — verify the gateway expects this",
                    modelName);
        }
        return url.replace(MODEL_PLACEHOLDER, modelName);
    }

    private Map<String, List<String>> applyAuthHeaders(Map<String, List<String>> headers) {
        if (tokenSupplier != null) {
            return applyBearerToken(headers);
        }
        String customHeaderName = configuration.get(AUTH_HEADER_NAME_CONFIG_KEY);
        boolean addCustomHeader = StringUtils.isNotBlank(customHeaderName)
                && StringUtils.isNotBlank(apiKey);
        boolean suppressDefault = Boolean.TRUE.toString().equalsIgnoreCase(
                StringUtils.trimToNull(configuration.get(SUPPRESS_DEFAULT_AUTH_CONFIG_KEY)));

        // Only honour `suppress_default_auth` when there is a custom auth
        // header to replace it with. Otherwise dropping `Authorization` would
        // send an unauthenticated request upstream — almost never what the
        // operator intended.
        boolean effectiveSuppress = suppressDefault && addCustomHeader;
        if (suppressDefault && !addCustomHeader) {
            log.warn(
                    "'{}' is true but '{}' is blank; keeping default Authorization header to avoid sending an unauthenticated request",
                    SUPPRESS_DEFAULT_AUTH_CONFIG_KEY, AUTH_HEADER_NAME_CONFIG_KEY);
        }

        if (!addCustomHeader && !effectiveSuppress) {
            return headers;
        }

        var mutated = new LinkedHashMap<String, List<String>>(headers.size() + 1);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (effectiveSuppress && AUTHORIZATION_HEADER.equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            mutated.put(entry.getKey(), entry.getValue());
        }
        if (addCustomHeader) {
            mutated.put(customHeaderName, List.of(apiKey));
        }
        return mutated;
    }

    /**
     * Token-auth mode: the fetched bearer is the sole credential on the request. It lands in
     * {@code Authorization: Bearer <token>} by default, or raw under the configured
     * {@code auth_header_name}. A pre-existing {@code Authorization} (e.g. a static header from
     * the provider's headers map) loses to the fetched token and is dropped loudly.
     */
    private Map<String, List<String>> applyBearerToken(Map<String, List<String>> headers) {
        String token = tokenSupplier.get();
        String customHeaderName = StringUtils.trimToNull(configuration.get(AUTH_HEADER_NAME_CONFIG_KEY));
        String targetHeader = customHeaderName != null ? customHeaderName : AUTHORIZATION_HEADER;
        String targetValue = customHeaderName != null ? token : "Bearer " + token;

        var mutated = new LinkedHashMap<String, List<String>>(headers.size() + 1);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (AUTHORIZATION_HEADER.equalsIgnoreCase(entry.getKey())
                    || targetHeader.equalsIgnoreCase(entry.getKey())) {
                log.warn("Dropping pre-configured '{}' header: the fetched token takes precedence", entry.getKey());
                continue;
            }
            mutated.put(entry.getKey(), entry.getValue());
        }
        mutated.put(targetHeader, List.of(targetValue));
        return mutated;
    }

    private String applyQueryParams(String url) {
        String raw = configuration.get(URL_QUERY_PARAMS_CONFIG_KEY);
        if (StringUtils.isBlank(raw) || StringUtils.isBlank(url)) {
            return url;
        }

        Map<String, String> params;
        try {
            params = JsonUtils.readValue(raw, QUERY_PARAMS_TYPE);
        } catch (UncheckedIOException exception) {
            log.warn(
                    "Failed to parse '{}' configuration value '{}' as JSON map; forwarding URL unchanged",
                    URL_QUERY_PARAMS_CONFIG_KEY, raw, exception);
            return url;
        }

        if (params == null || params.isEmpty()) {
            return url;
        }

        // URIBuilder parses the URL (validating shape), handles the `?`/`&`
        // separator, encodes keys/values, and preserves the fragment so the
        // rebuilt URL stays correct for inputs like `https://host/path#x`.
        try {
            var builder = new URIBuilder(url);
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (StringUtils.isBlank(entry.getKey())) {
                    continue;
                }
                builder.addParameter(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
            return builder.build().toString();
        } catch (URISyntaxException exception) {
            log.warn("Failed to parse custom provider URL as URI; forwarding unchanged", exception);
            return url;
        }
    }
}
