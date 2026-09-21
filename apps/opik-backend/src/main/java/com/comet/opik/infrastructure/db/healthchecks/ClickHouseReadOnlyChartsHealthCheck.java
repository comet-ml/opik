package com.comet.opik.infrastructure.db.healthchecks;

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
import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.READ_ONLY_CHARTS_CLICKHOUSE_CLIENT;

/**
 * Probes the Custom Charts read-only ClickHouse user via the v2 HTTP client.
 *
 * <p>Gated on the workspace allowlist being non-empty: an install that never enables the feature has no such
 * account provisioned, and must not be held out of readiness for missing one.
 */
@Singleton
public class ClickHouseReadOnlyChartsHealthCheck extends AbstractClickHouseHealthCheck {

    private final boolean enabled;

    @Inject
    public ClickHouseReadOnlyChartsHealthCheck(
            @NonNull @Named(READ_ONLY_CHARTS_CLICKHOUSE_CLIENT) Client chartsClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles) {
        super(chartsClient, healthCheckTimeout, "clickhouse-readonly-charts");
        this.enabled = !serviceToggles.getCustomChartsEnabledWorkspaces().isEmpty();
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
