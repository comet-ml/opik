package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;

import java.time.Duration;

/**
 * {@link HttpClient} decorator that marks OpenAI {@code insufficient_quota} (HTTP 429, out-of-credits)
 * as non-retryable.
 * <p>
 * OpenAI returns both transient rate-limits and out-of-credits as HTTP 429. LangChain4j maps any 429 to
 * a retryable {@link dev.langchain4j.exception.RateLimitException} — both in the model's internal
 * {@code RetryUtils.withRetryMappingExceptions} and in Opik's outer retry policy — so a key that is
 * permanently out of quota gets retried repeatedly per request (inner attempts x outer attempts),
 * generating an error storm against a key that will keep failing. This decorator inspects 429 responses
 * and, when the body identifies {@code insufficient_quota}, rethrows a {@link NonRetriableException} so
 * both retry layers stop immediately.
 * <p>
 * Two details are required for correct downstream handling:
 * <ul>
 *   <li>the original response body is carried as the exception <b>message</b>, so the provider-error
 *       mapping ({@code getLlmProviderError} -> {@link OpenAiCompatStatusCodes}) still parses it and
 *       surfaces HTTP 402; and</li>
 *   <li>the {@link HttpException} is <b>not</b> retained as the cause — otherwise LangChain4j's
 *       {@code ExceptionMapper.findRoot()} would unwrap to it and re-map the 429 back to a retryable
 *       {@code RateLimitException}, defeating the guard.</li>
 * </ul>
 * Rate-limit 429s and every other status are passed through unchanged and remain retryable.
 */
public class QuotaAwareHttpClient implements HttpClient {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final String INSUFFICIENT_QUOTA = "insufficient_quota";

    private final HttpClient delegate;

    public QuotaAwareHttpClient(@NonNull HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        try {
            return delegate.execute(request);
        } catch (HttpException httpException) {
            if (isInsufficientQuota(httpException)) {
                // Body as message (so it still maps to 402 downstream); no HttpException cause (so the
                // inner ExceptionMapper cannot unwrap and re-map the 429 to a retryable error).
                throw new NonRetriableException(httpException.getMessage());
            }
            throw httpException;
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        // Streaming responses are not wrapped in a retry policy, so there is nothing to short-circuit.
        delegate.execute(request, parser, listener);
    }

    private static boolean isInsufficientQuota(HttpException httpException) {
        return httpException.statusCode() == TOO_MANY_REQUESTS
                && httpException.getMessage() != null
                && httpException.getMessage().contains(INSUFFICIENT_QUOTA);
    }

    /**
     * Wraps the default (classpath) {@link HttpClientBuilder} so every client it builds is quota-aware.
     */
    public static HttpClientBuilder builder() {
        return new Builder(HttpClientBuilderLoader.loadHttpClientBuilder());
    }

    private static final class Builder implements HttpClientBuilder {

        private final HttpClientBuilder delegate;

        private Builder(@NonNull HttpClientBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            delegate.connectTimeout(connectTimeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            delegate.readTimeout(readTimeout);
            return this;
        }

        @Override
        public HttpClient build() {
            return new QuotaAwareHttpClient(delegate.build());
        }
    }
}
