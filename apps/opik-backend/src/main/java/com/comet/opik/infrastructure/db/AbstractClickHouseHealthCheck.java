package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shared probe shape for ClickHouse v2 HTTP health checks: bounded {@code SELECT 1} with a
 * server-side {@code max_execution_time} aligned to the configured deadline, plus
 * interrupt-aware cancellation on failure.
 *
 * <p>Subclasses provide {@link #getName()} for the health check identifier and may override
 * {@link #check()} to short-circuit before delegating to {@code super.check()} (e.g. when a
 * feature toggle disables the underlying capability).
 */
abstract class AbstractClickHouseHealthCheck extends NamedHealthCheck {

    protected static final String SELECT_1_QUERY = "SELECT 1";

    private final Client clickHouseClient;
    private final Duration healthCheckTimeout;

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
        // QuerySettings is not thread-safe; build a fresh instance per call
        var querySettings = new QuerySettings().setMaxExecutionTime(queryMaxExecutionTimeSeconds);
        var queryFuture = clickHouseClient.query(SELECT_1_QUERY, querySettings);
        try (var _ = queryFuture.get(healthCheckTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return Result.healthy();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return getUnhealthyAndCancelQuery(queryFuture, exception);
        } catch (Exception exception) {
            return getUnhealthyAndCancelQuery(queryFuture, exception);
        }
    }

    /**
     * Cancel on failure so the query doesn't keep running server-side.
     */
    private Result getUnhealthyAndCancelQuery(CompletableFuture<QueryResponse> queryFuture, Exception exception) {
        queryFuture.cancel(true);
        return Result.unhealthy(exception);
    }
}
