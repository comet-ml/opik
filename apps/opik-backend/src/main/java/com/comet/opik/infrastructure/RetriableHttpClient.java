package com.comet.opik.infrastructure;

import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Function;

/**
 * Reactive HTTP POST helper with retry semantics over a JAX-RS {@link Client}. Returns a cold
 * {@link Mono} so callers compose into a non-blocking pipeline; safe to subscribe from any thread.
 * <p>
 * Subscribed on {@link Schedulers#boundedElastic()} so any incidental subscribe-side work
 * is isolated from caller threads. This keeps the client
 * encapsulated regardless of how callers invoke it.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RetriableHttpClient {

    private final @NonNull Client client;

    /**
     * Immutable specification of an outbound POST. {@code connectTimeout} caps TCP/TLS handshake duration;
     * {@code readTimeout} caps the response read. Both are optional but recommended.
     */
    @Builder
    public record Request<T>(
            @NonNull Function<Client, WebTarget> requestFunction,
            @NonNull Retry retryPolicy,
            @NonNull Entity<?> body,
            Duration connectTimeout,
            Duration readTimeout,
            @NonNull Function<Response, T> responseFunction) {
    }

    /**
     * Execute the request and return a cold {@link Mono} that emits the response value, retrying on
     * 503/504 according to the supplied policy.
     */
    public <T> Mono<T> executePostWithRetry(@NonNull Request<T> request) {
        return Mono.defer(() -> performHttpPost(request)
                .flatMap(response -> {
                    int statusCode = response.getStatus();
                    if (isRetryableStatusCode(statusCode)) {
                        response.bufferEntity(); // Buffer the entity to allow multiple reads
                        String body = response.readEntity(String.class);
                        return Mono.error(new RetryUtils.RetryableHttpException(
                                "Service temporarily unavailable (HTTP %s): %s".formatted(statusCode, body),
                                statusCode));
                    }
                    return Mono.just(response);
                })
                .flatMap(value -> Mono.fromCallable(() -> request.responseFunction().apply(value))))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(request.retryPolicy());
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 503 || statusCode == 504;
    }

    private <T> Mono<Response> performHttpPost(Request<T> request) {
        return Mono.create(sink -> {
            var builder = request.requestFunction().apply(client)
                    .request();
            if (request.connectTimeout() != null) {
                builder.property(ClientProperties.CONNECT_TIMEOUT, (int) request.connectTimeout().toMillis());
            }
            if (request.readTimeout() != null) {
                builder.property(ClientProperties.READ_TIMEOUT, (int) request.readTimeout().toMillis());
            }
            builder.async().post(request.body(), new InvocationCallback<Response>() {
                @Override
                public void completed(Response response) {
                    sink.success(response);
                }

                @Override
                public void failed(Throwable throwable) {
                    sink.error(throwable);
                }
            });
        });
    }
}
