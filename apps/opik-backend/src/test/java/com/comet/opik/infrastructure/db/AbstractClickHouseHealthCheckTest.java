package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.codahale.metrics.health.HealthCheck;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.infrastructure.db.AbstractClickHouseHealthCheck.SELECT_1_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AbstractClickHouseHealthCheckTest {

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 1;
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.seconds(HEALTH_CHECK_TIMEOUT_SECONDS);
    private static final String CLICKHOUSE_SETTING_MAX_EXECUTION_TIME = "clickhouse_setting_max_execution_time";

    private final Client clickHouseClient = mock(Client.class);

    @AfterEach
    void afterEach() {
        // Tests that exercise the interrupt path leave the thread's interrupt flag set; clear so
        // it doesn't leak into subsequent tests on the same JUnit worker thread.
        Thread.interrupted();
    }

    /**
     * Probe lifecycle through the unmodified abstract: default {@link
     * AbstractClickHouseHealthCheck#newQuerySettings()} carries a
     * {@code clickhouse_setting_max_execution_time} server-setting derived from the configured
     * timeout, exercised on every path.
     */
    @Nested
    class ClickHouseHealthCheckTests {

        private final AbstractClickHouseHealthCheck healthCheck = new ClickHouseHealthCheck(
                clickHouseClient, HEALTH_CHECK_TIMEOUT);

        @Test
        void check__whenQuerySucceeds__thenHealthy() {
            when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(maxExecutionTimeServerSetting())))
                    .thenReturn(CompletableFuture.completedFuture(mock(QueryResponse.class)));

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.healthy());
        }

        @Test
        void check__whenQueryFails__thenUnhealthy() throws Exception {
            var causeException = new RuntimeException("ClickHouse unavailable");
            var executionException = new ExecutionException(causeException);
            var failingFuture = mock(CompletableFuture.class);
            when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                    .thenThrow(executionException);
            when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(maxExecutionTimeServerSetting())))
                    .thenReturn(failingFuture);

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.unhealthy(executionException));
            verify(failingFuture).cancel(true);
        }

        @Test
        void check__whenQueryInterrupted__thenUnhealthyAndRestoresInterruptFlag() throws Exception {
            var interruptedException = new InterruptedException("Interrupted call unavailable");
            var failingFuture = mock(CompletableFuture.class);
            when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                    .thenThrow(interruptedException);
            when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(maxExecutionTimeServerSetting())))
                    .thenReturn(failingFuture);

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.unhealthy(interruptedException));
            verify(failingFuture).cancel(true);
            // The check must restore the interrupt status it consumed; Thread.interrupted() asserts and clears.
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    /**
     * Behaviours specific to the Agent Insights read-only subclass: the {@code ollieEnabled}
     * short-circuit and the {@link ClickHouseReadOnlyFreeFormSqlHealthCheck#newQuerySettings()}
     * override that returns {@code null} so the probe carries no per-call settings — the production
     * {@code readonly=1} profile rejects any setting not in its {@code CHANGEABLE_IN_READONLY}
     * allowlist.
     */
    @Nested
    class ClickHouseReadOnlyFreeFormSqlHealthCheckTests {

        @Test
        void check__whenToggleOff__thenHealthyWithoutTouchingClient() {
            var healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(
                    clickHouseClient, HEALTH_CHECK_TIMEOUT, toggles(false));

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.healthy("Agent Insights queries disabled"));
            verifyNoInteractions(clickHouseClient);
        }

        @Test
        void check__whenToggleOnAndQuerySucceeds__thenHealthyAndProbeCarriesNoPerCallSettings() {
            when(clickHouseClient.query(eq(SELECT_1_QUERY), isNull(QuerySettings.class)))
                    .thenReturn(CompletableFuture.completedFuture(mock(QueryResponse.class)));

            var healthCheck = new ClickHouseReadOnlyFreeFormSqlHealthCheck(
                    clickHouseClient, HEALTH_CHECK_TIMEOUT, toggles(true));

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.healthy());
        }
    }

    private ArgumentMatcher<QuerySettings> maxExecutionTimeServerSetting() {
        return settings -> String.valueOf(HEALTH_CHECK_TIMEOUT_SECONDS)
                .equals(settings.getAllSettings().get(CLICKHOUSE_SETTING_MAX_EXECUTION_TIME));
    }

    private ServiceTogglesConfig toggles(boolean ollieEnabled) {
        var serviceTogglesConfig = new ServiceTogglesConfig();
        serviceTogglesConfig.setOllieEnabled(ollieEnabled);
        return serviceTogglesConfig;
    }

    /**
     * Per-field comparison: {@link HealthCheck.Result#equals(Object)} folds in a construction
     * timestamp, so a direct {@code isEqualTo} would never match.
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
