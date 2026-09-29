package com.comet.opik.utils;

import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@UtilityClass
@Slf4j
public class AsyncUtils {

    public static Context setRequestContext(Context ctx, Provider<RequestContext> requestContext) {
        return setRequestContext(ctx, requestContext.get());
    }

    public static Context setRequestContext(Context ctx, RequestContext requestContext) {
        return ctx.put(RequestContext.USER_NAME, requestContext.getUserName())
                .put(RequestContext.WORKSPACE_ID, requestContext.getWorkspaceId())
                .put(RequestContext.WORKSPACE_NAME, requestContext.getWorkspaceName())
                .put(RequestContext.CIPX_DEVICE_ID, StringUtils.defaultString(requestContext.getCipxDeviceId()))
                .put(RequestContext.VISIBILITY,
                        Optional.ofNullable(requestContext.getVisibility()).orElse(Visibility.PRIVATE));
    }

    public static Context setRequestContext(Context ctx, String workspaceId, String userName, Visibility visibility) {
        return ctx.put(RequestContext.USER_NAME, userName)
                .put(RequestContext.WORKSPACE_ID, workspaceId)
                .put(RequestContext.VISIBILITY, Optional.ofNullable(visibility).orElse(Visibility.PRIVATE));

    }

    public static Context setRequestContext(Context ctx, String userName, String workspaceId) {
        return ctx.put(RequestContext.USER_NAME, userName)
                .put(RequestContext.WORKSPACE_ID, workspaceId);
    }

    /**
     * Wraps a ClickHouse v2 client future so that cancelling the subscriber cannot leak the response.
     *
     * <p>Plain {@link Mono#fromFuture(Supplier)} cancels the future when the {@link Mono} is cancelled, and
     * the v2 client builds its futures with {@link CompletableFuture#supplyAsync}, which ignores
     * {@code mayInterruptIfRunning}. The HTTP round trip therefore runs to completion and produces a
     * response either way — but a cancelled future refuses it ({@code complete} returns {@code false}), so
     * nothing ever closes it and its pooled connection is gone for good. The client's pool defaults to ten
     * and is shared by every v2 caller in the process, so a handful of cancellations is enough to wedge a
     * pod permanently (OPIK-8576, the same defect the ClickHouse health checks carried).
     *
     * <p>Both halves below are required, in this order. Suppressing the cancel is what lets the response
     * arrive at all; the discard handler is what closes it once it does. Adding only the handler fixes
     * nothing — with the future cancelled, no value ever reaches it.
     */
    public static <T extends AutoCloseable> Mono<T> fromClickHouseFuture(
            Supplier<? extends CompletableFuture<T>> futureSupplier) {
        return Mono.fromFuture(futureSupplier, true)
                .doOnDiscard(AutoCloseable.class, AsyncUtils::closeQuietly);
    }

    /**
     * Closes a response nobody is waiting for any more. Failing to close it would leak the connection this
     * exists to return, so the exception is logged rather than propagated — there is no caller left to take it.
     */
    public static void closeQuietly(AutoCloseable response) {
        try {
            response.close();
        } catch (Exception exception) {
            log.warn("Failed to close a discarded ClickHouse response", exception);
        }
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

}
