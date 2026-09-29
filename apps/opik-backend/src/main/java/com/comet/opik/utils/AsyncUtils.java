package com.comet.opik.utils;

import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Provider;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.util.Objects;
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
     *       A discard handler on its own fixes nothing here: a cancelled future hands a value to nobody.
     *       {@code doOnDiscard} is the only hook that is handed that value — {@code doOnCancel} and
     *       {@code doFinally} fire without it, and {@code doOnNext} never runs once cancelled.</li>
     *   <li><b>It arrives and then the subscriber goes away.</b> {@link Mono#usingWhen} owns the response
     *       from that point and runs the cleanup on completion, error <i>and</i> cancellation.</li>
     * </ul>
     *
     * <p>{@code consume} is wrapped here rather than taken as a {@link Mono} so that every caller gets the
     * same deferral instead of remembering it. It runs on the thread that delivered the response; a caller
     * whose mapping blocks passes a {@link Scheduler} to the overload below.
     *
     * <p>The cleanup deliberately runs inline rather than on {@link Schedulers#boundedElastic()}. Closing is
     * I/O, so dispatching it looks like the safe choice, but {@code InsertResponse.close()} is an empty
     * method and the query callers already map on {@code boundedElastic}, so it protects nobody — while
     * {@code boundedElastic} is shared with most blocking work in this service, including the JSONEachRow
     * body serialization on the ingestion path. Queueing connection returns behind that couples releasing a
     * connection to the load consuming connections. Returning a connection must not wait on a queue.
     */
    public static <T extends AutoCloseable, R> Mono<R> usingClickHouseFuture(
            @NonNull Supplier<? extends CompletableFuture<T>> futureSupplier,
            @NonNull Function<? super T, ? extends R> consume) {
        return usingClickHouseFuture(futureSupplier, consume, null);
    }

    /**
     * As {@link #usingClickHouseFuture(Supplier, Function)}, with {@code consume} subscribed on
     * {@code consumeScheduler}. For a mapping that blocks; the response lifecycle is unchanged.
     *
     * <p>{@code consumeScheduler} is optional: {@code null} means {@link Schedulers#immediate()}, i.e. the
     * thread that delivered the response, so the choice stays optional whichever overload is called.
     */
    public static <T extends AutoCloseable, R> Mono<R> usingClickHouseFuture(
            @NonNull Supplier<? extends CompletableFuture<T>> futureSupplier,
            @NonNull Function<? super T, ? extends R> consume,
            @Nullable Scheduler consumeScheduler) {
        Scheduler scheduler = Objects.requireNonNullElseGet(consumeScheduler, Schedulers::immediate);
        return Mono.usingWhen(
                Mono.fromFuture(futureSupplier, true)
                        .doOnDiscard(AutoCloseable.class, response -> closeQuietly(response, "discarded")),
                response -> Mono.<R>fromCallable(() -> consume.apply(response)).subscribeOn(scheduler),
                response -> Mono.fromRunnable(() -> closeQuietly(response, "released")));
    }

    /**
     * Closes a ClickHouse v2 response nobody is waiting for any more, logging rather than propagating: there
     * is no caller left to take the exception, and failing to close leaks the connection this exists to
     * return. Shared with the callers that hold the v2 client outside Reactor — the ClickHouse health checks
     * — so the closing rule lives in one place rather than being restated per call style.
     *
     * <p>Takes {@link Object} and type-checks because an abandoned future's value arrives untyped. The check
     * is on {@link AutoCloseable}, which {@link java.io.Closeable} extends, so both are covered.
     */
    public static void closeQuietly(Object response, @NonNull String context) {
        if (response instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                log.warn("Failed to close a '{}' ClickHouse response", context, exception);
            }
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
