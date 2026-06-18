package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;

/**
 * Probes ClickHouse readiness via the v2 HTTP client.
 *
 * <p>Deliberately decoupled from the R2DBC reactor-netty pool that serves application queries: a
 * wedged application pool is a pod-local concern, not a signal that ClickHouse itself is unhealthy.
 * Conflating the two would drain the LB on client-side problems.
 */
@Singleton
public class ClickHouseHealthCheck extends AbstractClickHouseHealthCheck {

    @Inject
    public ClickHouseHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout) {
        super(clickHouseClient, healthCheckTimeout, "clickhouse");
    }
}
