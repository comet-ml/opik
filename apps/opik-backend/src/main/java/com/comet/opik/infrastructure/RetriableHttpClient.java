package com.comet.opik.infrastructure;

import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import java.util.function.Function;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RetriableHttpClient {

    private final @NonNull Client client;

    /**
     * Fluent interface for building and executing retryable HTTP POST requests.
     */
    public interface RetryableHttpPost {

        @NonNull Function<Client, WebTarget> callEndpoint();

        static RetryableHttpPost of(@NonNull Function<Client, WebTarget> requestFunction) {
            return () -> requestFunction;
        }

        default RetryableHttpCallContext withRetryPolicy(@NonNull Retry retryPolicy) {
            return () -> Tuples.of(this, retryPolicy);
        }

    }

    public interface RetryableHttpCallContext {
        Tuple2<RetryableHttpPost, Retry> getRequestWithContext();

        default RetryableHttpCallContextWithResponse withRequestBody(@NonNull Entity<?> requestEntity) {
            return () -> Tuples.of(this, requestEntity);
        }
    }

    public interface RetryableHttpCallContextWithResponse {
        Tuple2<RetryableHttpCallContext, Entity<?>> getRequestWithContextAndFunction();

        default <T> RetryableHttpExecutableContext<T> withResponse(@NonNull Function<Response, T> responseFunction) {
            return () -> Tuples.of(this, responseFunction);
        }
    }

    public interface RetryableHttpExecutableContext<T> {

        Tuple2<RetryableHttpCallContextWithResponse, Function<Response, T>> getFullContext();

        default T execute(@NonNull RetriableHttpClient httpClient) {
            Function<Client, WebTarget> requestFunction = getFullContext().getT1().getRequestWithContextAndFunction()
                    .getT1().getRequestWithContext().getT1().callEndpoint();
            Retry retrySpec = getFullContext().getT1().getRequestWithContextAndFunction().getT1()
                    .getRequestWithContext().getT2();
            Entity<?> requestBody = getFullContext().getT1().getRequestWithContextAndFunction().getT2();
            Function<Response, T> responseFunction = getFullContext().getT2();

            return httpClient.executePostWithRetry(requestBody, retrySpec, requestFunction, responseFunction);
        }

    }

    /**
     * Initiates a new retryable HTTP POST request to the specified endpoint.
     *
     * @param requestFunction Function that takes a JAX-RS Client and returns a WebTarget for the desired endpoint.
     * @return A RetryableHttpPost instance to further configure the request.
     */
    public static RetryableHttpPost newPost(@NonNull Function<Client, WebTarget> requestFunction) {
        return RetryableHttpPost.of(requestFunction);
    }

    private <T> T executePostWithRetry(Entity<?> request, Retry retrySpec, Function<Client, WebTarget> requestFunction,
            Function<Response, T> responseFunction) {
        return Mono.defer(() -> performHttpPost(request, requestFunction)
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
                .flatMap(value -> Mono.fromCallable(() -> responseFunction.apply(value))))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(retrySpec)
                .block();
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 503 || statusCode == 504;
    }

    private Mono<Response> performHttpPost(Entity<?> request, Function<Client, WebTarget> requestFunction) {
        return Mono.create(sink -> requestFunction.apply(client)
                .request()
                .async()
                .post(request, new InvocationCallback<Response>() {
                    @Override
                    public void completed(Response response) {
                        sink.success(response);
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        sink.error(throwable);
                    }
                }));
    }

}
