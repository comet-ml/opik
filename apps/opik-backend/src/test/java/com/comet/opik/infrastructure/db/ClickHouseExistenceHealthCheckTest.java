package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.api.query.Records;
import com.codahale.metrics.health.HealthCheck;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatcher;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the toggle-gated existence probes ({@link ClickHouseClusterHealthCheck},
 * {@link ClickHouseColdStorageDiskHealthCheck}): both share {@link AbstractClickHouseExistenceHealthCheck},
 * differing only in query, name and messages, so they are exercised with the same parameterized cases.
 */
class ClickHouseExistenceHealthCheckTest {

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 1;
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.seconds(HEALTH_CHECK_TIMEOUT_SECONDS);
    private static final String CLICKHOUSE_SETTING_MAX_EXECUTION_TIME = "clickhouse_setting_max_execution_time";
    private static final String CLICKHOUSE_SETTING_LOG_COMMENT = "clickhouse_setting_log_comment";
    private static final String LOG_COMMENT_PREFIX = "health_check:";

    private final Client clickHouseClient = mock(Client.class);

    @AfterEach
    void afterEach() {
        // The interrupt-path test leaves the thread's interrupt flag set; clear so it doesn't leak
        // into subsequent tests on the same JUnit worker thread.
        Thread.interrupted();
    }

    private static Stream<Arguments> checks() {
        return Stream.of(
                arguments("clickhouse-cluster",
                        "SELECT count() AS cnt FROM system.clusters WHERE cluster = 'cluster'",
                        "ClickHouse cluster 'cluster' is not visible from this node",
                        "Distributed cluster health check disabled",
                        (BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck>) (client,
                                enabled) -> new ClickHouseClusterHealthCheck(client, HEALTH_CHECK_TIMEOUT,
                                        factory(databaseAnalytics -> databaseAnalytics
                                                .setClusterHealthCheckEnabled(enabled)))),
                arguments("clickhouse-cold-storage-disk",
                        "SELECT count() AS cnt FROM system.disks WHERE name = 'cold_s3'",
                        "ClickHouse disk 'cold_s3' is not reachable from this node",
                        "Cold storage disk health check disabled",
                        (BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck>) (client,
                                enabled) -> new ClickHouseColdStorageDiskHealthCheck(client, HEALTH_CHECK_TIMEOUT,
                                        factory(databaseAnalytics -> databaseAnalytics
                                                .setColdStorageDiskHealthCheckEnabled(enabled)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void check__whenDisabled__thenHealthyWithoutTouchingClient(String name, String query, String notFoundMessage,
            String disabledMessage, BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck> factory) {
        var healthCheck = factory.apply(clickHouseClient, false);

        var actualResult = healthCheck.execute();

        assertResult(actualResult, HealthCheck.Result.healthy(disabledMessage));
        verifyNoInteractions(clickHouseClient);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void check__whenEnabledAndResourcePresent__thenHealthy(String name, String query, String notFoundMessage,
            String disabledMessage, BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck> factory) {
        var records = records(1L);
        when(clickHouseClient.queryRecords(eq(query), argThat(probeServerSettings())))
                .thenReturn(CompletableFuture.completedFuture(records));

        var actualResult = factory.apply(clickHouseClient, true).execute();

        assertResult(actualResult, HealthCheck.Result.healthy());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void check__whenEnabledAndCountIsZero__thenUnhealthy(String name, String query, String notFoundMessage,
            String disabledMessage, BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck> factory) {
        var records = records(0L);
        when(clickHouseClient.queryRecords(eq(query), argThat(probeServerSettings())))
                .thenReturn(CompletableFuture.completedFuture(records));

        var actualResult = factory.apply(clickHouseClient, true).execute();

        assertResult(actualResult, HealthCheck.Result.unhealthy(notFoundMessage));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void check__whenEnabledAndNoRows__thenUnhealthy(String name, String query, String notFoundMessage,
            String disabledMessage, BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck> factory) {
        var records = mock(Records.class);
        when(records.iterator()).thenReturn(Collections.emptyIterator());
        when(clickHouseClient.queryRecords(eq(query), argThat(probeServerSettings())))
                .thenReturn(CompletableFuture.completedFuture(records));

        var actualResult = factory.apply(clickHouseClient, true).execute();

        assertResult(actualResult, HealthCheck.Result.unhealthy(notFoundMessage));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void check__whenEnabledAndQueryFails__thenUnhealthyAndCancelsQuery(String name, String query,
            String notFoundMessage, String disabledMessage,
            BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck> factory) throws Exception {
        var causeException = new RuntimeException("ClickHouse unavailable");
        var executionException = new ExecutionException(causeException);
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                .thenThrow(executionException);
        when(clickHouseClient.queryRecords(eq(query), argThat(probeServerSettings())))
                .thenReturn(failingFuture);

        var actualResult = factory.apply(clickHouseClient, true).execute();

        assertResult(actualResult, HealthCheck.Result.unhealthy(executionException));
        verify(failingFuture).cancel(true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void check__whenEnabledAndInterrupted__thenUnhealthyAndRestoresInterruptFlag(String name, String query,
            String notFoundMessage, String disabledMessage,
            BiFunction<Client, Boolean, AbstractClickHouseExistenceHealthCheck> factory) throws Exception {
        var interruptedException = new InterruptedException("Interrupted call unavailable");
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                .thenThrow(interruptedException);
        when(clickHouseClient.queryRecords(eq(query), argThat(probeServerSettings())))
                .thenReturn(failingFuture);

        var actualResult = factory.apply(clickHouseClient, true).execute();

        assertResult(actualResult, HealthCheck.Result.unhealthy(interruptedException));
        verify(failingFuture).cancel(true);
        assertThat(Thread.interrupted()).isTrue();
    }

    private Records records(long count) {
        var record = mock(GenericRecord.class);
        when(record.getLong("cnt")).thenReturn(count);
        var records = mock(Records.class);
        when(records.iterator()).thenReturn(List.of(record).iterator());
        return records;
    }

    private static DatabaseAnalyticsFactory factory(Consumer<DatabaseAnalyticsFactory> customizer) {
        var databaseAnalytics = new DatabaseAnalyticsFactory();
        customizer.accept(databaseAnalytics);
        return databaseAnalytics;
    }

    private ArgumentMatcher<QuerySettings> probeServerSettings() {
        return settings -> {
            var allSettings = settings.getAllSettings();
            var logComment = allSettings.get(CLICKHOUSE_SETTING_LOG_COMMENT);
            return String.valueOf(HEALTH_CHECK_TIMEOUT_SECONDS)
                    .equals(allSettings.get(CLICKHOUSE_SETTING_MAX_EXECUTION_TIME))
                    && logComment instanceof String value && value.startsWith(LOG_COMMENT_PREFIX);
        };
    }

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
