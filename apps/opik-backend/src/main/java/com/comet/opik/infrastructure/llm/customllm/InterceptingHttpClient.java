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
import java.util.Map;

/// HTTP client decorator for the Custom LLM provider that mutates outgoing
/// requests based on optional configuration keys.
///
/// Current responsibilities:
///   - Appends entries from `configuration["url_query_params"]` (JSON-encoded
///     `Map<String, String>`) to the outgoing URL as query parameters.
///
/// Later revisions will extend this decorator with auth-header handling and
/// `{model}` URL substitution. When none of its config keys are set, it is a
/// pure no-op — preserving the existing Custom LLM provider contract and
/// anything byte-exact that LangChain4j's `OpenAiClient` already produced.
@RequiredArgsConstructor
@Slf4j
class InterceptingHttpClient implements HttpClient {

    static final String URL_QUERY_PARAMS_CONFIG_KEY = "url_query_params";

    private static final TypeReference<Map<String, String>> QUERY_PARAMS_TYPE = new TypeReference<>() {
    };

    private final @NonNull HttpClient delegate;
    private final Map<String, String> configuration;

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
        if (mutatedUrl.equals(request.url())) {
            return request;
        }

        return HttpRequest.builder()
                .method(request.method())
                .url(mutatedUrl)
                .headers(request.headers())
                .body(request.body())
                .build();
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
