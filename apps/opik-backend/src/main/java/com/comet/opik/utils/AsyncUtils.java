package com.comet.opik.utils;

import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.net.SocketException;
import java.time.Duration;
import java.util.Optional;

@UtilityClass
@Slf4j
public class AsyncUtils {

    public static Context setRequestContext(Context ctx, Provider<RequestContext> requestContext) {
        return ctx.put(RequestContext.USER_NAME, requestContext.get().getUserName())
                .put(RequestContext.WORKSPACE_ID, requestContext.get().getWorkspaceId())
                .put(RequestContext.WORKSPACE_NAME, requestContext.get().getWorkspaceName())
                .put(RequestContext.VISIBILITY,
                        Optional.ofNullable(requestContext.get().getVisibility()).orElse(Visibility.PRIVATE));
    }

    public static Context setRequestContext(Context ctx, String userName, String workspaceId) {
        return ctx.put(RequestContext.USER_NAME, userName)
                .put(RequestContext.WORKSPACE_ID, workspaceId);
    }

    public interface ContextAwareAction<T> {
        Mono<T> subscriberContext(String userName, String workspaceId);
    }

    public interface ContextAwareStream<T> {
        Flux<T> subscriberContext(String userName, String workspaceId);
    }

    public static <T> Mono<T> makeMonoContextAware(ContextAwareAction<T> action) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return action.subscriberContext(userName, workspaceId);
        });
    }

    public static <T> Flux<T> makeFluxContextAware(ContextAwareStream<T> action) {
        return Flux.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return action.subscriberContext(userName, workspaceId);
        });
    }

    public static RetryBackoffSpec handleConnectionError() {
        return Retry.backoff(3, Duration.ofMillis(100))
                .doBeforeRetry(retrySignal -> log.debug("Retrying due to: {}", retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("Filtering for retry: {}", throwable.getMessage());

                    return SocketException.class.isAssignableFrom(throwable.getClass())
                            || (throwable instanceof IllegalStateException
                                    && throwable.getMessage().contains("Connection pool shut down"));
                });
    }

}
