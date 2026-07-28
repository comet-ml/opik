package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Shared probe shape for ClickHouse v2 HTTP health checks: bounded {@code SELECT 1} with a
 * server-side {@code max_execution_time} cap aligned to the configured deadline, plus
 * interrupt-aware cancellation on failure.
 *
 * <p>Subclasses pass their name via the constructor and may override {@link #check()} to
 * short-circuit before delegating to {@code super.check()} (e.g. when a feature toggle disables
 * the underlying capability), or override {@link #newQuerySettings()} when their user runs under
 * a profile that forbids per-query setting changes. Subclasses running a different query drive it
 * through {@link #executeProbe(CompletableFuture, Function)} so the timeout, cancellation and
 * {@code log_comment} handling live in one place.
 */
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
     * within the same budget, even if {@code cancel(true)} is a no-op on the in-flight HTTP
     * request. Whole seconds rounded up from {@link #healthCheckTimeout}, with a 1 s floor so the
     * cap stays meaningful below sub-second timeouts.
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
     * Runs a probe query under the shared deadline and cancellation contract: the caller-side
     * {@code future.get(healthCheckTimeout)} bounds the wait, {@code onResult} maps the (auto-closed)
     * result to a {@link Result}, and any interrupt/failure cancels the in-flight query so it doesn't
     * keep running server-side. Subclasses supply the future (via {@link #newQuerySettings()}) and the
     * result mapping; the flow lives here so timeout and cancellation fixes stay in one place.
     */
    protected <T extends AutoCloseable> Result executeProbe(CompletableFuture<T> queryFuture,
            Function<? super T, Result> onResult) {
        try (var result = queryFuture.get(healthCheckTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return onResult.apply(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return getUnhealthyAndCancelQuery(queryFuture, exception);
        } catch (Exception exception) {
            return getUnhealthyAndCancelQuery(queryFuture, exception);
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
     * Cancel on failure so the query doesn't keep running server-side.
     */
    protected Result getUnhealthyAndCancelQuery(CompletableFuture<?> queryFuture, Exception exception) {
        queryFuture.cancel(true);
        return Result.unhealthy(exception);
    }
}
