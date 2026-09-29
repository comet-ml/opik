package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Shared probe shape for ClickHouse v2 HTTP health checks: bounded {@code SELECT 1} with a
 * server-side {@code max_execution_time} cap aligned to the configured deadline, and the
 * abandoned-future handling that keeps a timed-out probe from leaking its connection.
 *
 * <p>Subclasses pass their name via the constructor and may override {@link #check()} to
 * short-circuit before delegating to {@code super.check()} (e.g. when a feature toggle disables
 * the underlying capability), or override {@link #newQuerySettings()} when their user runs under
 * a profile that forbids per-query setting changes. Subclasses running a different query drive it
 * through {@link #executeProbe(CompletableFuture, Function)} so the timeout, abandonment and
 * {@code log_comment} handling live in one place.
 */
@Slf4j
abstract class AbstractClickHouseHealthCheck extends NamedHealthCheck {

    protected static final String SELECT_1_QUERY = "SELECT 1";

    /**
     * Server-side ClickHouse setting carried via {@link QuerySettings#serverSetting(String, String)}
     * — the v2 client only serializes settings prefixed with {@code clickhouse_setting_} to the
     * request URL. {@link QuerySettings#setMaxExecutionTime(Integer)} stores the value without that
     * prefix and never reaches the server, so it must not be used to impose a server-side cap.
     */
    private static final String MAX_EXECUTION_TIME = "max_execution_time";

    private static final String LOG_COMMENT = "log_comment";
    private static final String LOG_COMMENT_TEMPLATE = "health_check:%s";

    protected final Client clickHouseClient;
    protected final Duration healthCheckTimeout;

    /**
     * Server-side ceiling aligned with the call-site deadline so ClickHouse aborts a stuck probe
     * within the same budget. This is the only thing that actually bounds the query: nothing
     * client-side can stop it once issued. Whole seconds rounded up from
     * {@link #healthCheckTimeout}, with a 1 s floor so the cap stays meaningful below sub-second
     * timeouts.
     */
    private final int queryMaxExecutionTimeSeconds;

    @Getter
    private final String name;

    protected AbstractClickHouseHealthCheck(@NonNull Client clickHouseClient,
            @NonNull io.dropwizard.util.Duration healthCheckTimeout,
            String name) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "Argument 'name' must not be blank");
        this.clickHouseClient = clickHouseClient;
        this.healthCheckTimeout = healthCheckTimeout.toJavaDuration();
        this.queryMaxExecutionTimeSeconds = Math.toIntExact(
                Math.max(1L, Math.ceilDiv(this.healthCheckTimeout.toMillis(), 1000L)));
        this.name = name;
    }

    @Override
    protected Result check() {
        return executeProbe(clickHouseClient.query(SELECT_1_QUERY, newQuerySettings()), response -> Result.healthy());
    }

    /**
     * Runs a probe query under the shared deadline: the caller-side {@code future.get(healthCheckTimeout)}
     * bounds the wait and {@code onResult} maps the (auto-closed) result to a {@link Result}. Subclasses
     * supply the future (via {@link #newQuerySettings()}) and the result mapping; the flow lives here so
     * fixes to the abandonment path stay in one place.
     *
     * <p>When the deadline passes, try-with-resources never binds a result — there is nothing to close yet —
     * so the abandoned future has to be dealt with explicitly, or the response it later produces holds its
     * connection forever. See {@link #releaseAbandonedQuery}.
     *
     * <p>Acquisition and mapping are separate blocks precisely so that only the first can reach
     * {@code releaseAbandonedQuery}. Once the result is in hand it belongs to try-with-resources, which closes
     * it exactly once; routing a mapping failure through the abandonment path would register the handler on an
     * already-completed future, firing it inline and closing that same response a second time.
     */
    protected <T extends AutoCloseable> Result executeProbe(CompletableFuture<T> queryFuture,
            Function<? super T, Result> onResult) {
        T result;
        try {
            result = queryFuture.get(healthCheckTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return releaseAbandonedQuery(queryFuture, exception);
        } catch (Exception exception) {
            return releaseAbandonedQuery(queryFuture, exception);
        }

        try (result) {
            return onResult.apply(result);
        } catch (Exception exception) {
            return Result.unhealthy(exception);
        }
    }

    /**
     * Per-call probe settings, built fresh each invocation because {@link QuerySettings} is not
     * thread-safe. Default carries the server-side {@code max_execution_time} cap from
     * {@link #queryMaxExecutionTimeSeconds} plus a {@code log_comment} tagging the query with this
     * probe's name in {@code system.query_log}; the caller-side {@code future.get(healthCheckTimeout)}
     * remains the deadline on top.
     *
     * <p>Subclasses whose user runs under a profile that forbids per-query setting changes
     * (ClickHouse {@code readonly=1} with a {@code CHANGEABLE_IN_READONLY} allowlist that
     * excludes {@code max_execution_time}) MUST override to return {@code null}; otherwise
     * ClickHouse rejects the probe with a {@code READONLY} error.
     */
    protected QuerySettings newQuerySettings() {
        return new QuerySettings()
                .serverSetting(MAX_EXECUTION_TIME, String.valueOf(queryMaxExecutionTimeSeconds))
                .serverSetting(LOG_COMMENT, LOG_COMMENT_TEMPLATE.formatted(name));
    }

    /**
     * Closes whatever the abandoned probe eventually produces, so its connection goes back to the pool. Only for
     * a probe whose result was never acquired: on a future that has already handed one over, the handler runs
     * inline and closes a response the caller is already closing.
     *
     * <p>This used to call {@code queryFuture.cancel(true)}, which not only failed to help but caused the
     * leak it looked like it was preventing. The v2 client builds this future with
     * {@code CompletableFuture.supplyAsync}, and {@link CompletableFuture#cancel} ignores
     * {@code mayInterruptIfRunning}: it cannot stop the supplier, so the HTTP round trip completes and
     * builds a {@code QueryResponse} regardless. Cancelling first completes the future exceptionally, so
     * that response is then discarded without ever being closed — and its connection is gone for good.
     *
     * <p>Each timed-out probe leaked one. The client's pool defaults to ten, readiness probes run
     * continuously against a one-second deadline, and once ten were lost every query waited out the
     * client's ten-second acquire timeout and failed. In production that left a pod permanently unready,
     * thousands of {@code ConnectionRequestTimeoutException} an hour, with ClickHouse itself healthy and
     * the other pods untouched — and liveness kept passing, so nothing ever restarted it (OPIK-8576).
     *
     * <p>Not cancelling costs nothing here: the probe already caps the query server-side with
     * {@code max_execution_time} in {@link #newQuerySettings()}, which is what actually bounds it. Stopping
     * the query on the server needs the client's own cancellation API, not this future.
     */
    protected Result releaseAbandonedQuery(CompletableFuture<?> queryFuture, Exception exception) {
        queryFuture.whenComplete((result, throwable) -> closeQuietly(result));
        return Result.unhealthy(exception);
    }

    private void closeQuietly(Object result) {
        if (result instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                log.warn("Failed to close abandoned '{}' probe response", name, exception);
            }
        }
    }
}
