package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// HTTP client decorator for the Custom LLM provider that mutates outgoing
/// requests based on optional configuration keys.
///
/// Current responsibilities:
///   - Substitutes a `{model}` placeholder in the base URL with the model
///     name from the outgoing request body. Lets a single provider entry
///     serve many deployments on gateways that bake the deployment into
///     the path (e.g. Azure APIM `/deployments/{deployment}/chat/completions`).
///   - Appends entries from `configuration["url_query_params"]` (JSON-encoded
///     `Map<String, String>`) to the outgoing URL as query parameters.
///   - Adds a custom auth header `{name}: {apiKey}` when
///     `configuration["auth_header_name"]` is set. Appends alongside the
///     default `Authorization: Bearer` header unless it is suppressed.
///   - Removes the default `Authorization: Bearer <apiKey>` header when
///     `configuration["suppress_default_auth"]` is `"true"`. Used by
///     gateways whose policy rejects an `Authorization` header.
///
/// When none of the relevant config keys are set and no `{model}` placeholder
/// is present, the decorator is a pure no-op — preserving the existing Custom
/// LLM provider contract byte-for-byte with what LangChain4j's `OpenAiClient`
/// already produced.
@Slf4j
class InterceptingHttpClient implements HttpClient {

    static final String URL_QUERY_PARAMS_CONFIG_KEY = "url_query_params";
    static final String AUTH_HEADER_NAME_CONFIG_KEY = "auth_header_name";
    static final String SUPPRESS_DEFAULT_AUTH_CONFIG_KEY = "suppress_default_auth";
    static final String MODEL_PLACEHOLDER = "{model}";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final TypeReference<Map<String, String>> QUERY_PARAMS_TYPE = new TypeReference<>() {
    };

    private final @NonNull HttpClient delegate;
    private final Map<String, String> configuration;
    private final String apiKey;

    /// Normalizes a null `configuration` to an empty map so helpers can call
    /// `configuration.get(...)` unconditionally. Keeps `{model}`-only providers
    /// (which may carry no new config keys yet still reach `mutate()`) safe.
    InterceptingHttpClient(@NonNull HttpClient delegate, Map<String, String> configuration, String apiKey) {
        this.delegate = delegate;
        this.configuration = configuration != null ? configuration : Map.of();
        this.apiKey = apiKey;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        return delegate.execute(mutate(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser,
            ServerSentEventListener listener) {
        delegate.execute(mutate(request), parser, listener);
    }

    private HttpRequest mutate(HttpRequest request) {
        boolean hasPlaceholder = request.url() != null && request.url().contains(MODEL_PLACEHOLDER);
        if (configuration.isEmpty() && !hasPlaceholder) {
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
        return url.replace(MODEL_PLACEHOLDER, modelNode.asText());
    }

    private Map<String, List<String>> applyAuthHeaders(Map<String, List<String>> headers) {
        String customHeaderName = configuration.get(AUTH_HEADER_NAME_CONFIG_KEY);
        boolean addCustomHeader = StringUtils.isNotBlank(customHeaderName)
                && StringUtils.isNotBlank(apiKey);
        boolean suppressDefault = Boolean.TRUE.toString().equalsIgnoreCase(
                StringUtils.trimToNull(configuration.get(SUPPRESS_DEFAULT_AUTH_CONFIG_KEY)));

        if (!addCustomHeader && !suppressDefault) {
            return headers;
        }

        var mutated = new LinkedHashMap<String, List<String>>(headers.size() + 1);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (suppressDefault && AUTHORIZATION_HEADER.equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            mutated.put(entry.getKey(), entry.getValue());
        }
        if (addCustomHeader) {
            mutated.put(customHeaderName, List.of(apiKey));
        }
        return mutated;
    }

    private String applyQueryParams(String url) {
        String raw = configuration.get(URL_QUERY_PARAMS_CONFIG_KEY);
        if (StringUtils.isBlank(raw)) {
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

        var sb = new StringBuilder(url);
        char sep = url.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                continue;
            }
            sb.append(sep)
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }
}
