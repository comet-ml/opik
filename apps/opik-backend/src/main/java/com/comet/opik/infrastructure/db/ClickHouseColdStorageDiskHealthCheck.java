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
 * Verifies that the {@code cold_s3} tiered-storage disk is reachable from this node via the v2 HTTP
 * client.
 *
 * <p>Gated by {@code databaseAnalytics.coldStorageDiskHealthCheckEnabled}: deployments without tier
 * storage (OSS Docker) leave it off and are unaffected. Only environments with tiered storage
 * activated turn it on, where a node that can't reach the S3 disk must be pulled from readiness.
 */
@Singleton
public class ClickHouseColdStorageDiskHealthCheck extends AbstractClickHouseExistenceHealthCheck {

    private static final String NAME = "clickhouse-cold-storage-disk";

    private static final String COLD_DISK_NAME = "cold_s3";
    private static final String EXISTENCE_QUERY = "SELECT count() AS cnt FROM system.disks WHERE name = '%s'"
            .formatted(COLD_DISK_NAME);
    private static final String DISABLED_MESSAGE = "Cold storage disk health check disabled";
    private static final String NOT_FOUND_MESSAGE = "ClickHouse disk '%s' is not reachable from this node"
            .formatted(COLD_DISK_NAME);

    @Inject
    public ClickHouseColdStorageDiskHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("databaseAnalytics") DatabaseAnalyticsFactory databaseAnalytics) {
        super(clickHouseClient, healthCheckTimeout, NAME, databaseAnalytics.isColdStorageDiskHealthCheckEnabled(),
                DISABLED_MESSAGE, EXISTENCE_QUERY, NOT_FOUND_MESSAGE);
    }
}
