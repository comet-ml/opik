package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// HTTP client decorator for the Custom LLM provider that mutates outgoing
/// requests based on optional configuration keys.
///
/// Current responsibilities:
///   - Appends entries from `configuration["url_query_params"]` (JSON-encoded
///     `Map<String, String>`) to the outgoing URL as query parameters.
///   - Adds a custom auth header `{name}: {apiKey}` when
///     `configuration["auth_header_name"]` is set. Appends alongside the
///     default `Authorization: Bearer` header unless it is suppressed.
///   - Removes the default `Authorization: Bearer <apiKey>` header when
///     `configuration["suppress_default_auth"]` is `"true"`. Used by
///     gateways whose policy rejects an `Authorization` header.
///
/// Later revisions will extend this decorator with `{model}` URL substitution.
/// When none of its config keys are set, it is a pure no-op — preserving the
/// existing Custom LLM provider contract and anything byte-exact that
/// LangChain4j's `OpenAiClient` already produced.
@RequiredArgsConstructor
@Slf4j
class InterceptingHttpClient implements HttpClient {

    static final String URL_QUERY_PARAMS_CONFIG_KEY = "url_query_params";
    static final String AUTH_HEADER_NAME_CONFIG_KEY = "auth_header_name";
    static final String SUPPRESS_DEFAULT_AUTH_CONFIG_KEY = "suppress_default_auth";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final TypeReference<Map<String, String>> QUERY_PARAMS_TYPE = new TypeReference<>() {
    };

    private final @NonNull HttpClient delegate;
    private final Map<String, String> configuration;
    private final String apiKey;

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
        if (configuration == null || configuration.isEmpty()) {
            return request;
        }

        String mutatedUrl = applyQueryParams(request.url());
        Map<String, List<String>> mutatedHeaders = applyAuthHeaders(request.headers());

        boolean urlChanged = !mutatedUrl.equals(request.url());
        boolean headersChanged = mutatedHeaders != request.headers();
        if (!urlChanged && !headersChanged) {
            return request;
        }

        return HttpRequest.builder()
                .method(request.method())
                .url(mutatedUrl)
                .headers(mutatedHeaders)
                .body(request.body())
                .build();
    }

    private Map<String, List<String>> applyAuthHeaders(Map<String, List<String>> headers) {
        String customHeaderName = configuration.get(AUTH_HEADER_NAME_CONFIG_KEY);
        boolean addCustomHeader = StringUtils.isNotBlank(customHeaderName)
                && StringUtils.isNotBlank(apiKey);
        boolean suppressDefault = "true".equalsIgnoreCase(
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
        } catch (RuntimeException exception) {
            log.warn("Failed to parse '{}' configuration value as JSON map, forwarding URL unchanged",
                    URL_QUERY_PARAMS_CONFIG_KEY, exception);
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
