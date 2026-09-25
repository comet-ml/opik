package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;
import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_EXTENDED_SQL_CLICKHOUSE_CLIENT;

/**
 * Probes the extended free-form SQL read-only ClickHouse user via the v2 HTTP client.
 *
 * <p>Gated on {@code ollieEnabled} and a non-empty workspace allowlist — the same pair the endpoint checks. An
 * installation that never enables the feature must not be held out of readiness.
 */
@Singleton
public class ClickHouseReadOnlyFreeFormExtendedSqlHealthCheck extends AbstractClickHouseHealthCheck {

    private final boolean enabled;

    @Inject
    public ClickHouseReadOnlyFreeFormExtendedSqlHealthCheck(
            @NonNull @Named(READ_ONLY_FREE_FORM_EXTENDED_SQL_CLICKHOUSE_CLIENT) Client freeFormExtendedSqlClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull @Config("customCharts") CustomChartsConfig customCharts) {
        super(freeFormExtendedSqlClient, healthCheckTimeout, "clickhouse-readonly-freeform-extended-sql");
        // Mirrors the endpoint's gate: the account is provisioned separately and only where the feature is
        // meant to run, so probing it anywhere else would fail against a user that was never created.
        this.enabled = serviceToggles.isOllieEnabled()
                && !customCharts.getEnabledWorkspaces().isEmpty();
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy("Custom Charts queries disabled");
        }
        return super.check();
    }

    /**
     * Same constraint as the Agent Insights probe: the account's profile marks only {@code SQL_workspace_id} /
     * {@code SQL_project_id} as {@code CHANGEABLE_IN_READONLY}, so any other per-call setting is rejected. The
     * caller-side {@code future.get(healthCheckTimeout)} is the deadline.
     */
    @Override
    protected QuerySettings newQuerySettings() {
        return null;
    }
}
