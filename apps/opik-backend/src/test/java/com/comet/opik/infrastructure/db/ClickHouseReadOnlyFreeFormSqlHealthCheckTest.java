package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.codahale.metrics.health.HealthCheck;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.infrastructure.db.AbstractClickHouseHealthCheck.SELECT_1_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClickHouseReadOnlyFreeFormSqlHealthCheckTest {

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 1;
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.seconds(HEALTH_CHECK_TIMEOUT_SECONDS);

    private final Client clickHouseClient = mock(Client.class);

    private ClickHouseReadOnlyFreeFormSqlHealthCheck healthCheck;

    @BeforeEach
    void beforeEach() {
        healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(
                clickHouseClient, HEALTH_CHECK_TIMEOUT, toggles(true));
    }

    @AfterEach
    void afterEach() {
        // Tests that exercise the interrupt path leave the thread's interrupt flag set; clear it so
        // it doesn't leak into subsequent tests on the same JUnit worker thread.
        Thread.interrupted();
    }

    @Test
    void check__whenToggleOff__thenHealthyWithoutTouchingClient() {
        healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(
                clickHouseClient, HEALTH_CHECK_TIMEOUT, toggles(false));

        var actualResult = healthCheck.execute();

        assertResult(actualResult, HealthCheck.Result.healthy("Agent Insights queries disabled"));
        verifyNoInteractions(clickHouseClient);
    }

    @Test
    void check__whenToggleOnAndQuerySucceeds__thenHealthy() {
        when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(queryMaxExecutionTimeSeconds())))
                .thenReturn(CompletableFuture.completedFuture(mock(QueryResponse.class)));

        var actualResult = healthCheck.execute();

        assertResult(actualResult, HealthCheck.Result.healthy());
    }

    @Test
    void check__whenToggleOnAndQueryFails__thenUnhealthy() throws Exception {
        var causeException = new RuntimeException("ClickHouse unavailable");
        var executionException = new ExecutionException(causeException);
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                .thenThrow(executionException);
        when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(queryMaxExecutionTimeSeconds())))
                .thenReturn(failingFuture);

        var actualResult = healthCheck.execute();

        assertResult(actualResult, HealthCheck.Result.unhealthy(executionException));
        verify(failingFuture).cancel(true);
    }

    @Test
    void check__whenToggleOnAndQueryInterrupted__thenUnhealthyAndRestoresInterruptFlag() throws Exception {
        var interruptedException = new InterruptedException("Interrupted call unavailable");
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                .thenThrow(interruptedException);
        when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(queryMaxExecutionTimeSeconds())))
                .thenReturn(failingFuture);

        var actualResult = healthCheck.execute();

        assertResult(actualResult, HealthCheck.Result.unhealthy(interruptedException));
        verify(failingFuture).cancel(true);
        // The check must restore the interrupt status it consumed; Thread.interrupted() asserts and clears.
        assertThat(Thread.interrupted()).isTrue();
    }

    private ServiceTogglesConfig toggles(boolean agentInsightsEnabled) {
        var serviceTogglesConfig = new ServiceTogglesConfig();
        serviceTogglesConfig.setAgentInsightsEnabled(agentInsightsEnabled);
        return serviceTogglesConfig;
    }

    private ArgumentMatcher<QuerySettings> queryMaxExecutionTimeSeconds() {
        return settings -> HEALTH_CHECK_TIMEOUT_SECONDS == settings.getMaxExecutionTime();
    }

    /**
     * Per-field comparison. {@link HealthCheck.Result#equals(Object)} folds in a construction
     * timestamp, so a full {@code isEqualTo} can't be used; AssertJ's
     * {@code usingRecursiveComparison().ignoringFields("time")} would work but bypasses the type's
     * own {@code equals}.
     */
    private void assertResult(HealthCheck.Result actual, HealthCheck.Result expected) {
        assertThat(actual.isHealthy()).isEqualTo(expected.isHealthy());
        assertThat(actual.getMessage()).isEqualTo(expected.getMessage());
        assertError(actual.getError(), expected.getError());
    }

    private void assertError(Throwable actual, Throwable expected) {
        if (expected == null) {
            assertThat(actual).isNull();
            return;
        }
        assertThat(actual)
                .isExactlyInstanceOf(expected.getClass())
                .hasMessage(expected.getMessage())
                .hasCause(expected.getCause() == null ? null : expected.getCause());
    }
}
