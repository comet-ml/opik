package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.codahale.metrics.health.HealthCheck;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClickHouseReadOnlyFreeFormSqlHealthCheckTest {

    private static ServiceTogglesConfig toggles(boolean agentInsightsEnabled) {
        var config = new ServiceTogglesConfig();
        config.setAgentInsightsEnabled(agentInsightsEnabled);
        return config;
    }

    @Test
    void check__whenToggleOff__thenHealthyWithoutTouchingClient() {
        var client = mock(Client.class);

        var healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(client, toggles(false), Duration.seconds(1));

        HealthCheck.Result result = healthCheck.execute();

        assertThat(result.isHealthy()).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void check__whenToggleOnAndQuerySucceeds__thenHealthy() {
        var client = mock(Client.class);
        when(client.query("SELECT 1")).thenReturn(CompletableFuture.completedFuture(mock(QueryResponse.class)));

        var healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(client, toggles(true), Duration.seconds(1));

        assertThat(healthCheck.execute().isHealthy()).isTrue();
    }

    @Test
    void check__whenToggleOnAndQueryFails__thenUnhealthy() {
        var client = mock(Client.class);
        when(client.query("SELECT 1"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("clickhouse unavailable")));

        var healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(client, toggles(true), Duration.seconds(1));

        assertThat(healthCheck.execute().isHealthy()).isFalse();
    }
}
