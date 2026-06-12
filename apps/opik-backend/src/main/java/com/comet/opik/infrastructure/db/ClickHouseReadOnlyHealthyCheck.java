package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.concurrent.TimeUnit;

@Singleton
public class ClickHouseReadOnlyHealthyCheck extends NamedHealthCheck {

    private final Client readOnlyClient;
    private final boolean enabled;
    private final long healthCheckTimeoutMs;

    @Inject
    public ClickHouseReadOnlyHealthyCheck(
            @NonNull @Named(DatabaseAnalyticsModule.READ_ONLY_CLICKHOUSE_CLIENT) Client readOnlyClient,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @Named("clickhouse_health_check_timeout") Duration healthCheckTimeout) {
        this.readOnlyClient = readOnlyClient;
        this.enabled = serviceToggles.isAgentInsightsEnabled();
        this.healthCheckTimeoutMs = healthCheckTimeout.toMilliseconds();
    }

    @Override
    public String getName() {
        return "clickhouse-readonly";
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy("Agent Insights queries disabled");
        }
        var queryFuture = readOnlyClient.query("SELECT 1");
        try (var response = queryFuture.get(healthCheckTimeoutMs, TimeUnit.MILLISECONDS)) {
            return Result.healthy();
        } catch (Exception ex) {
            // On timeout (or any failure) cancel the future so the query doesn't keep running server-side.
            queryFuture.cancel(true);
            return Result.unhealthy(ex);
        }
    }
}
