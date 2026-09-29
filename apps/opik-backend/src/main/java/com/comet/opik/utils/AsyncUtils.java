package com.comet.opik.utils;

import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
     * Runs {@code consume} over a ClickHouse v2 client response and closes that response exactly once,
     * whatever happens to the subscriber. <b>Callers must not close it themselves</b> — no
     * try-with-resources around the response, or it is closed twice.
     *
     * <p>Why this exists rather than a plain {@link Mono#fromFuture(Supplier)}: that cancels the future when
     * the {@link Mono} is cancelled, and the v2 client builds its futures with
     * {@link CompletableFuture#supplyAsync}, which ignores {@code mayInterruptIfRunning}. The HTTP round trip
     * therefore runs to completion and produces a response either way — but a cancelled future refuses it
     * ({@code complete} returns {@code false}), so nothing ever closes it and its pooled connection is gone
     * for good. The pool defaults to ten and is shared by every v2 caller in the process, so a handful of
     * cancellations wedges a pod permanently (OPIK-8576).
     *
     * <p>There are two ways to lose a response, and they need different mechanisms:
     *
     * <ul>
     *   <li><b>It never arrives.</b> Cancellation lands before the future completes. Suppressing the cancel
     *       keeps the future able to accept the value, and the discard handler closes it when it turns up.
     *       A discard handler on its own fixes nothing here: a cancelled future hands a value to nobody.</li>
     *   <li><b>It arrives and then the subscriber goes away.</b> {@link Mono#usingWhen} owns the response
     *       from that point and runs the cleanup on completion, error <i>and</i> cancellation.</li>
     * </ul>
     *
     * <p>Both live here on purpose. Leaving either to the call site means an operator that has to be
     * remembered at the right position in every chain, and forgetting it leaks silently — the response is
     * simply never closed, with nothing failing and nothing logged until the pool runs out.
     *
     * <p>The cleanup runs on {@link Schedulers#boundedElastic()} because closing is I/O:
     * {@code QueryResponse.close()} closes the underlying HTTP response. On the completion path that would
     * land on whichever thread the consumer terminated on, and on the cancellation path on whichever thread
     * cancelled — neither is ours to block. ({@code InsertResponse.close()} is an empty method, so for the
     * insert callers this costs a scheduler hop and saves nothing; it is the query callers that need it.)
     *
     * <p>{@code consume} is NOT moved to a scheduler here — the caller knows whether its own mapping blocks,
     * and says so by handing back a {@link Mono} that carries its own {@code subscribeOn}.
     */
    public static <T extends AutoCloseable, R> Mono<R> usingClickHouseFuture(
            Supplier<? extends CompletableFuture<T>> futureSupplier,
            Function<? super T, ? extends Mono<R>> consume) {
        return Mono.usingWhen(
                Mono.fromFuture(futureSupplier, true)
                        .doOnDiscard(AutoCloseable.class, AsyncUtils::closeQuietly),
                consume,
                response -> Mono.fromRunnable(() -> closeQuietly(response))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Closes a response nobody is waiting for any more. Failing to close it would leak the connection this
     * exists to return, so the exception is logged rather than propagated — there is no caller left to take it.
     */
    private static void closeQuietly(AutoCloseable response) {
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
