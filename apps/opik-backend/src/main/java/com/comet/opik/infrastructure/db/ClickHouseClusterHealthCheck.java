package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;

/**
 * Verifies that the Distributed cluster definition is visible from this node via the v2 HTTP client.
 *
 * <p>Gated by {@code databaseAnalytics.clusterHealthCheckEnabled}: single-shard / non-Distributed
 * deployments (OSS Docker, existing single-node environments) leave it off and are unaffected. Only
 * the Hyperscale Distributed deployment turns it on, where a node that can't see the cluster can't
 * serve distributed queries and must be pulled from readiness.
 */
@Singleton
public class ClickHouseClusterHealthCheck extends AbstractClickHouseExistenceHealthCheck {

    private static final String NAME = "clickhouse-cluster";

    // The cluster name set in both the Helm CHI and the docker-compose macros.
    private static final String CLUSTER_NAME = "cluster";
    private static final String EXISTENCE_QUERY = "SELECT count() AS cnt FROM system.clusters WHERE cluster = '%s'"
            .formatted(CLUSTER_NAME);
    private static final String DISABLED_MESSAGE = "Distributed cluster health check disabled";
    private static final String NOT_FOUND_MESSAGE = "ClickHouse cluster '%s' is not visible from this node"
            .formatted(CLUSTER_NAME);

    @Inject
    public ClickHouseClusterHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("databaseAnalytics") DatabaseAnalyticsFactory databaseAnalytics) {
        super(clickHouseClient, healthCheckTimeout, NAME, databaseAnalytics.isClusterHealthCheckEnabled(),
                DISABLED_MESSAGE, EXISTENCE_QUERY, NOT_FOUND_MESSAGE);
    }
}
