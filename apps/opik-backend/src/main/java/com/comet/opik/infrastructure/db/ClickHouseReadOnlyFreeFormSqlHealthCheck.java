package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;
import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT;

/**
 * Probes the Agent Insights read-only free-form SQL ClickHouse user via the v2 HTTP client.
 *
 * <p>Gated by {@code agentInsightsEnabled}: when the feature is off the probe reports healthy
 * without querying ClickHouse, so a misconfigured read-only user never gates overall readiness
 * for an environment that doesn't use the feature.
 */
@Singleton
public class ClickHouseReadOnlyFreeFormSqlHealthCheck extends AbstractClickHouseHealthCheck {

    private final boolean enabled;

    @Inject
    public ClickHouseReadOnlyFreeFormSqlHealthCheck(
            @NonNull @Named(READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT) Client readOnlyClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles) {
        super(readOnlyClient, healthCheckTimeout, "clickhouse-readonly-freeform-sql");
        this.enabled = serviceToggles.isAgentInsightsEnabled();
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy("Agent Insights queries disabled");
        }
        return super.check();
    }
}
