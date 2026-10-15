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
 * Asserts that {@code databaseAnalyticsDataModel.spansDistributedWrapEnabled} agrees with the actual {@code spans}
 * topology in ClickHouse (OPIK-8376). Post-cutover {@code spans} is a {@code Distributed} table and {@code SpanDAO}
 * routes its cascade and retention deletes at {@code spans_local} only while the flag is on (OPIK-7799).
 *
 * <p>The assertion, the messages it reports and the reasoning behind both live in
 * {@link AbstractClickHouseTopologyHealthCheck}, shared with {@link ClickHouseTracesTopologyHealthCheck}. A separate
 * probe rather than one merged check because the two cutovers flip independently — an estate can be wrapped on traces
 * and not on spans — so an operator reading {@code /health-check} has to see which of the two disagrees with its table.
 *
 * <p>Spans reach the probe with one difference, and it makes the check matter more rather than less: they have no
 * standalone delete endpoint. Every span delete arrives from somewhere else — the trace-delete cascade or a retention
 * sweep — so a mismatch surfaces as a failing trace delete or a retention sweep that reclaims nothing, never on the
 * operation that caused it. Readiness is where it should be caught.
 */
@Singleton
public class ClickHouseSpansTopologyHealthCheck extends AbstractClickHouseTopologyHealthCheck {

    private static final String NAME = "clickhouse-spans-topology";

    private static final String SPANS_TABLE = "spans";
    private static final String SPANS_LOCAL_TABLE = "spans_local";
    private static final String SPAN_ENTITY = "span";

    private static final String FLAG = "databaseAnalyticsDataModel.spansDistributedWrapEnabled";

    @Inject
    public ClickHouseSpansTopologyHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModel) {
        super(clickHouseClient, healthCheckTimeout, NAME, SPANS_TABLE, SPANS_LOCAL_TABLE, SPAN_ENTITY, FLAG,
                dataModel.spansDistributedWrapEnabled());
    }
}
