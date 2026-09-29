package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.codahale.metrics.health.HealthCheck;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.db.healthchecks.AbstractClickHouseHealthCheck.SELECT_1_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
            // Deliberately not cancel(true): see releaseAbandonedQuery. Cancelling completes the future
            // exceptionally, so the response the supplier is still building is discarded unclosed.
            verify(failingFuture, never()).cancel(anyBoolean());
        }

        @Test
        @DisplayName("a probe abandoned at the deadline still closes the response it later produces")
        void check__whenProbeTimesOut__thenTheLateResponseIsClosed() throws Exception {
            // The leak behind OPIK-8576. try-with-resources never binds on timeout — there is nothing to
            // close yet — so unless the abandoned future is handled, the QueryResponse it produces a moment
            // later keeps its connection for the life of the process. Ten of those and the pool is gone.
            var lateResponse = mock(QueryResponse.class);
            var slowFuture = new CompletableFuture<QueryResponse>();
            when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(maxExecutionTimeServerSetting())))
                    .thenReturn(slowFuture);

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.unhealthy(new TimeoutException()));
            // Nothing to close while the query is still in flight.
            verify(lateResponse, never()).close();

            // The query the client never stopped now finishes, after the probe has walked away.
            slowFuture.complete(lateResponse);

            verify(lateResponse).close();
        }

        @Test
        @DisplayName("a response the probe body already closed is not closed a second time")
        void check__whenResultMappingFails__thenTheResponseIsClosedExactlyOnce() throws Exception {
            // Reading the result is real work in the subclasses — iterating Records, pulling a column — so it
            // can throw with the response in hand. try-with-resources has already closed it by the time the
            // failure surfaces; the abandonment handler must stay out of that path or it closes the response
            // again, on a completed future, inline.
            var response = mock(QueryResponse.class);
            var failure = new IllegalStateException("Malformed probe row");
            when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(maxExecutionTimeServerSetting())))
                    .thenReturn(CompletableFuture.completedFuture(response));
            var healthCheck = new ThrowingProbeHealthCheck(clickHouseClient, HEALTH_CHECK_TIMEOUT, failure);

            var actualResult = healthCheck.execute();

            assertResult(actualResult, HealthCheck.Result.unhealthy(failure));
            verify(response, times(1)).close();
        }

        /**
         * {@code close()} is declared {@code throws Exception} on both {@link AutoCloseable} and
         * {@code QueryResponse}, so a failed close carries no type that distinguishes it from a failed
         * {@code get()}. Only the block structure keeps it out of the abandonment path, which would close the
         * same response again.
         */
        @Test
        void check__whenClosingTheResponseFails__thenTheResponseIsNotClosedAgain() throws Exception {
            var closeException = new TimeoutException("Closing the response failed");
            var response = mock(QueryResponse.class);
            doThrow(closeException).when(response).close();
            when(clickHouseClient.query(eq(SELECT_1_QUERY), argThat(maxExecutionTimeServerSetting())))
                    .thenReturn(CompletableFuture.completedFuture(response));

            var actualResult = healthCheck.execute();

            // A probe that cannot release its connection is not healthy, whatever the query returned.
            assertResult(actualResult, HealthCheck.Result.unhealthy(closeException));
            verify(response).close();
        }

        /**
         * Why {@code cancel(true)} was fatal rather than merely useless: cancelling completes the future
         * exceptionally, and nothing registered on it afterwards is ever handed the response.
         */
        @Test
        void releaseAbandonedQuery__whenTheFutureWasCancelled__thenTheResponseCanNeverBeRecovered()
                throws Exception {
            var response = mock(QueryResponse.class);
            var queryFuture = new CompletableFuture<QueryResponse>();
            queryFuture.cancel(true);

            healthCheck.releaseAbandonedQuery(queryFuture, new CancellationException());

            // The in-flight supplier finishes later and tries to publish its response; the future refuses it.
            assertThat(queryFuture.complete(response)).isFalse();
            verify(response, never()).close();
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
            verify(failingFuture, never()).cancel(anyBoolean());
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

    /**
     * Probe whose result mapping always throws, standing in for the real subclasses' record reading.
     */
    private static final class ThrowingProbeHealthCheck extends AbstractClickHouseHealthCheck {

        private final RuntimeException failure;

        private ThrowingProbeHealthCheck(Client clickHouseClient, Duration healthCheckTimeout,
                RuntimeException failure) {
            super(clickHouseClient, healthCheckTimeout, "throwing-probe");
            this.failure = failure;
        }

        @Override
        protected HealthCheck.Result check() {
            return executeProbe(clickHouseClient.query(SELECT_1_QUERY, newQuerySettings()), response -> {
                throw failure;
            });
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
     * Per-field comparison: {@link HealthCheck.Result#equals(Object)} folds in a construction timestamp, so a
     * direct {@code isEqualTo} would never match. Deliberately not a recursive comparison over the cause
     * chain — {@link Throwable#getCause()} can cycle, which is why Guava's {@code getCausalChain} guards for
     * it, and a test helper is the wrong place to carry that.
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
