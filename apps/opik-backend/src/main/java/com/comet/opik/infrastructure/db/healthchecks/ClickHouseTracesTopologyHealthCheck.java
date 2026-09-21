package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;

/**
 * Asserts that {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled} agrees with the actual {@code traces}
 * topology in ClickHouse (OPIK-7773). Post-cutover {@code traces} is a {@code Distributed} table and {@code TraceDAO}
 * routes deletes at {@code traces_local} only while the flag is on (OPIK-7455).
 *
 * <p>The assertion, the messages it reports and the reasoning behind both live in
 * {@link AbstractClickHouseTopologyHealthCheck}, shared with {@link ClickHouseSpansTopologyHealthCheck}.
 */
@Singleton
public class ClickHouseTracesTopologyHealthCheck extends AbstractClickHouseTopologyHealthCheck {

    private static final String NAME = "clickhouse-traces-topology";

    private static final String TRACES_TABLE = "traces";
    private static final String TRACES_LOCAL_TABLE = "traces_local";
    private static final String TRACE_ENTITY = "trace";

    private static final String FLAG = "databaseAnalyticsDataModel.tracesDistributedWrapEnabled";

    @Inject
    public ClickHouseTracesTopologyHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModel) {
        super(clickHouseClient, healthCheckTimeout, NAME, TRACES_TABLE, TRACES_LOCAL_TABLE, TRACE_ENTITY, FLAG,
                dataModel.tracesDistributedWrapEnabled());
    }
}
