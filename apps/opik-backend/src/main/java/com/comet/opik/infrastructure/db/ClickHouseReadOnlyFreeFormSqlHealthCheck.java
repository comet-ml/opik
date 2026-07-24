package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
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
 * <p>Gated by {@code ollieEnabled}: when the feature is off the probe reports healthy
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
        this.enabled = serviceToggles.isOllieEnabled();
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy("Agent Insights queries disabled");
        }
        return super.check();
    }

    /**
     * The Agent Insights read-only free-form SQL user runs under the production settings profile
     * (see {@code provision_agent_insights_readonly_user.sh}): {@code readonly=1} with only
     * {@code SQL_workspace_id} / {@code SQL_project_id} marked {@code CHANGEABLE_IN_READONLY}.
     * That explicit allowlist makes ClickHouse reject any other per-call setting change —
     * including {@code max_execution_time}. The probe must therefore carry no per-call settings;
     * the caller-side {@code future.get(healthCheckTimeout)} is the deadline.
     */
    @Override
    protected QuerySettings newQuerySettings() {
        return null;
    }
}
