package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.llm.DelegatingHttpClient;
import com.comet.opik.infrastructure.llm.DelegatingHttpClientBuilder;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.CompletableFuture;

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
 * Rate-limit 429s and every other status are passed through unchanged and remain retryable. Streaming responses
 * are not wrapped in a retry policy, so there is nothing to short-circuit and both streaming calls are forwarded
 * as they are.
 */
public class QuotaAwareHttpClient extends DelegatingHttpClient {

    private static final String INSUFFICIENT_QUOTA = "insufficient_quota";

    /** Makes the calls through {@code delegate} quota-aware. */
    public QuotaAwareHttpClient(@NonNull HttpClient delegate) {
        super(delegate);
    }

    /** Throws {@link NonRetriableException} for an {@code insufficient_quota} response. */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        try {
            return delegate.execute(request);
        } catch (HttpException httpException) {
            if (isInsufficientQuota(httpException)) {
                throw nonRetriable(httpException);
            }
            throw httpException;
        }
    }

    /** Fails the future with {@link NonRetriableException} for an {@code insufficient_quota} response. */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        return recoverAsync(delegate.executeAsync(request), failure -> CompletableFuture.failedFuture(
                failure instanceof HttpException httpException && isInsufficientQuota(httpException)
                        ? nonRetriable(httpException)
                        : failure));
    }

    /**
     * Body as message (so it still maps to 402 downstream); no HttpException cause (so the inner ExceptionMapper
     * cannot unwrap and re-map the 429 to a retryable error).
     */
    private static NonRetriableException nonRetriable(HttpException httpException) {
        return new NonRetriableException(httpException.getMessage());
    }

    private static boolean isInsufficientQuota(HttpException httpException) {
        return httpException.statusCode() == Response.Status.TOO_MANY_REQUESTS.getStatusCode()
                && StringUtils.containsIgnoreCase(httpException.getMessage(), INSUFFICIENT_QUOTA);
    }

    /** Wraps the given {@link HttpClientBuilder} so every client it builds is quota-aware. */
    public static HttpClientBuilder builder(@NonNull HttpClientBuilder delegate) {
        return new Builder(delegate);
    }

    private static final class Builder extends DelegatingHttpClientBuilder {

        private Builder(HttpClientBuilder delegate) {
            super(delegate);
        }

        @Override
        protected HttpClient wrap(HttpClient delegate) {
            return new QuotaAwareHttpClient(delegate);
        }
    }
}
