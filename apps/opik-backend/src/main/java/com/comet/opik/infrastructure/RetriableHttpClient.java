package com.comet.opik.infrastructure;

import com.comet.opik.utils.RetryUtils;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reactive HTTP helper with retry semantics over a JAX-RS {@link Client}. Returns a cold
 * {@link Mono} so callers compose into a non-blocking pipeline; safe to subscribe from any thread.
 * <p>
 * Subscribe-side work runs on {@link Schedulers#boundedElastic()}, or on
 * {@link Request#scheduler()} when a caller supplies one, so it is isolated from caller threads.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RetriableHttpClient {

    /** Upstream error bodies are arbitrary content; cap what reaches exception messages and logs. */
    private static final int MAX_DIAGNOSTIC_BODY_LENGTH = 512;

    private final @NonNull Client client;

    /**
     * Immutable specification of an outbound POST. {@code connectTimeout} caps TCP/TLS handshake duration;
     * {@code readTimeout} caps the response read. Both are optional but recommended.
     */
    @Builder
    public record Request<T>(
            @NonNull Function<Client, WebTarget> requestFunction,
            @NonNull Retry retryPolicy,
            /** Request entity. Required for POST; must be null for GET. */
            Entity<?> body,
            /**
             * Applied to the {@link Invocation.Builder} before the call, for headers, cookies and
             * {@code accept}/{@code acceptEncoding}. Needed by callers whose request carries
             * credentials -- an {@code Authorization} header or a session cookie -- which cannot be
             * expressed through {@code requestFunction}, since that only reaches the {@link WebTarget}.
             * Re-applied on every attempt, so retries carry the same headers as the first try.
             */
            Consumer<Invocation.Builder> requestCustomizer,
            Duration connectTimeout,
            Duration readTimeout,
            /**
             * Scheduler for subscribe-side work. Defaults to {@link Schedulers#boundedElastic()};
             * a caller that blocks on the result should supply its own so a retry storm on its hop
             * cannot queue behind — or ahead of — unrelated work on the shared scheduler.
             */
            Scheduler scheduler,
            @NonNull Function<Response, T> responseFunction) {
    }

    /**
     * Execute the request and return a cold {@link Mono} that emits the response value, retrying on
     * 503/504 according to the supplied policy.
     */
    public <T> Mono<T> executePostWithRetry(@NonNull Request<T> request) {
        Preconditions.checkArgument(request.body() != null, "body is required for POST");
        return execute(request, HttpMethod.POST);
    }

    /**
     * GET variant of {@link #executePostWithRetry}, with the same retry and timeout semantics.
     * Any {@code body} on the request is ignored rather than rejected.
     */
    public <T> Mono<T> executeGetWithRetry(@NonNull Request<T> request) {
        return execute(request, HttpMethod.GET);
    }

    private <T> Mono<T> execute(Request<T> request, String method) {
        var scheduler = request.scheduler() != null ? request.scheduler() : Schedulers.boundedElastic();
        return Mono.defer(() -> performHttpRequest(request, method)
                .flatMap(response -> transformAndClose(request, response)))
                .subscribeOn(scheduler)
                .retryWhen(request.retryPolicy());
    }

    /**
     * Applies the caller's transform and closes the response on every path.
     * <p>
     * All three exits used to leak: a retryable status returned {@code Mono.error} without closing,
     * a transform that threw unwound past the close, and the success path left it to the caller.
     * Under the retry this compounds -- each attempt leaks another pooled connection against a
     * service that is already failing, which is exactly when the pool can least afford it.
     * <p>
     * The transform is evaluated eagerly inside the try block rather than deferred with
     * {@code Mono.fromCallable}, because a deferred callable would run after the resource had
     * already been closed.
     */
    private <T> Mono<T> transformAndClose(Request<T> request, Response response) {
        try (response) {
            int statusCode = response.getStatus();
            if (isRetryableStatusCode(statusCode)) {
                return Mono.error(new RetryUtils.RetryableHttpException(
                        "Service temporarily unavailable (HTTP %s): %s"
                                .formatted(statusCode, diagnosticBody(response)),
                        statusCode));
            }
            // justOrEmpty, not just: the previous Mono.fromCallable completed empty when the
            // transform returned null, and Mono.just would turn that into a NullPointerException.
            return Mono.justOrEmpty(request.responseFunction().apply(response));
        }
    }

    /**
     * The upstream body, truncated to {@link #MAX_DIAGNOSTIC_BODY_LENGTH}.
     * <p>
     * The result reaches {@code RetryableHttpException}'s message, which is logged before every
     * retry and propagates to the caller on exhaustion. A 503/504 body is arbitrary upstream
     * content, so it must not be able to flood the logs. Mirrors the bound in
     * {@code RemoteAuthService.readErrorMessage}.
     * <p>
     * Both guards are required before the entity can be read: {@code hasEntity} for a response
     * without one, and {@code bufferEntity}'s return value because a stream already consumed or
     * closed cannot be buffered for the re-read.
     */
    private String diagnosticBody(Response response) {
        try {
            if (!response.hasEntity() || !response.bufferEntity()) {
                return "<no body>";
            }
            return StringUtils.abbreviate(response.readEntity(String.class), MAX_DIAGNOSTIC_BODY_LENGTH);
        } catch (RuntimeException unreadable) {
            log.warn("Failed to read upstream error body for diagnostics", unreadable);
            return "<unreadable body>";
        }
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 503 || statusCode == 504;
    }

    private <T> Mono<Response> performHttpRequest(Request<T> request, String method) {
        return Mono.create(sink -> {
            var builder = request.requestFunction().apply(client)
                    .request();
            if (request.requestCustomizer() != null) {
                request.requestCustomizer().accept(builder);
            }
            if (request.connectTimeout() != null) {
                builder.property(ClientProperties.CONNECT_TIMEOUT, (int) request.connectTimeout().toMillis());
            }
            if (request.readTimeout() != null) {
                builder.property(ClientProperties.READ_TIMEOUT, (int) request.readTimeout().toMillis());
            }
            var callback = new InvocationCallback<Response>() {
                @Override
                public void completed(Response response) {
                    sink.success(response);
                }

                @Override
                public void failed(Throwable throwable) {
                    sink.error(throwable);
                }
            };
            // Branch on the method, not on body presence: a GET must not carry an entity even if
            // the caller set one.
            if (HttpMethod.POST.equals(method)) {
                builder.async().method(method, request.body(), callback);
            } else {
                builder.async().method(method, callback);
            }
        });
    }
}
