package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.Records;
import io.dropwizard.util.Duration;
import lombok.NonNull;

/**
 * Toggle-gated probe that asserts a single {@code count()} query returns at least one row — used to
 * verify that a piece of ClickHouse topology (a Distributed cluster definition, a storage disk) is
 * actually visible from this node.
 *
 * <p>Unlike the plain {@code SELECT 1} of {@link AbstractClickHouseHealthCheck}, "the query ran" is
 * not enough here: {@code SELECT count() FROM system.clusters WHERE cluster = 'cluster'} succeeds and
 * returns {@code 0} when the cluster is absent. So the probe reads the count and reports unhealthy
 * when it is zero. It reuses the parent's timeout, {@code max_execution_time} cap and
 * cancel-on-failure machinery.
 *
 * <p>When the feature toggle is off the probe reports healthy without touching ClickHouse, so a
 * deployment that doesn't have the topology (single-shard / OSS Docker) is never gated by it.
 */
abstract class AbstractClickHouseExistenceHealthCheck extends AbstractClickHouseHealthCheck {

    private static final String COUNT_COLUMN = "cnt";

    private final boolean enabled;
    private final String disabledMessage;
    private final String existenceQuery;
    private final String notFoundMessage;

    protected AbstractClickHouseExistenceHealthCheck(@NonNull Client clickHouseClient,
            @NonNull Duration healthCheckTimeout, String name, boolean enabled, @NonNull String disabledMessage,
            @NonNull String existenceQuery, @NonNull String notFoundMessage) {
        super(clickHouseClient, healthCheckTimeout, name);
        this.enabled = enabled;
        this.disabledMessage = disabledMessage;
        this.existenceQuery = existenceQuery;
        this.notFoundMessage = notFoundMessage;
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy(disabledMessage);
        }
        return executeProbe(clickHouseClient.queryRecords(existenceQuery, newQuerySettings()),
                records -> exists(records) ? Result.healthy() : Result.unhealthy(notFoundMessage));
    }

    private static boolean exists(Records records) {
        var iterator = records.iterator();
        return iterator.hasNext() && iterator.next().getLong(COUNT_COLUMN) > 0L;
    }
}
