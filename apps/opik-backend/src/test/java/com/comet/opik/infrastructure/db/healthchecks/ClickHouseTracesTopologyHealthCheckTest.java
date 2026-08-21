package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.api.query.Records;
import com.codahale.metrics.health.HealthCheck;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatcher;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour of {@link ClickHouseTracesTopologyHealthCheck}: the flag↔topology assertion in both directions, plus the
 * shared timeout/cancellation contract inherited from {@link AbstractClickHouseHealthCheck}.
 *
 * <p>The mismatch cases are the point of the check, so each one asserts the actual message, not merely that the probe
 * went unhealthy: the message is the only thing an operator sees on {@code /health-check}, and it has to name the flag,
 * the observed engine and the fix. {@code ClickHouseTracesTopologyReadinessTest} covers the same matrix end-to-end
 * against a real ClickHouse.
 */
class ClickHouseTracesTopologyHealthCheckTest {

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 1;
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.seconds(HEALTH_CHECK_TIMEOUT_SECONDS);
    private static final String CLICKHOUSE_SETTING_MAX_EXECUTION_TIME = "clickhouse_setting_max_execution_time";
    private static final String CLICKHOUSE_SETTING_LOG_COMMENT = "clickhouse_setting_log_comment";
    private static final String EXPECTED_LOG_COMMENT = "health_check:clickhouse-traces-topology";

    private static final String TOPOLOGY_QUERY = "SELECT name, engine FROM system.tables "
            + "WHERE database = currentDatabase() AND name IN ('traces', 'traces_local')";

    private static final String FLAG = "databaseAnalyticsDataModel.tracesDistributedWrapEnabled";

    private final Client clickHouseClient = mock(Client.class);

    @AfterEach
    void afterEach() {
        // The interrupt-path test leaves the thread's interrupt flag set; clear so it doesn't leak into subsequent
        // tests on the same JUnit worker thread.
        Thread.interrupted();
    }

    @Test
    void check__whenWrapEnabledAndTracesIsDistributedOverTracesLocal__thenHealthy() {
        var actualResult = check(true, Map.of("traces", "Distributed", "traces_local", "ReplicatedMergeTree"));

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage())
                .isEqualTo("'traces' is Distributed over 'traces_local', matching %s=true".formatted(FLAG));
    }

    @ParameterizedTest(name = "engine={0}")
    @ValueSource(strings = {"MergeTree", "ReplicatedMergeTree", "SharedMergeTree"})
    void check__whenWrapDisabledAndTracesIsAMergeTree__thenHealthy(String engine) {
        var actualResult = check(false, Map.of("traces", engine));

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage())
                .isEqualTo("'traces' is a %s, matching %s=false".formatted(engine, FLAG));
    }

    /**
     * {@code traces_local} is still present here: after the EXCHANGE but before the wrap, the shard table exists while
     * {@code traces} is a plain MergeTree — so the probe cannot infer the wrap from its presence, only from the engine
     * of {@code traces} itself.
     */
    @Test
    void check__whenWrapEnabledButTracesIsNotWrapped__thenUnhealthyNamingTheObservedEngine() {
        var actualResult = check(true, Map.of("traces", "ReplicatedMergeTree", "traces_local", "ReplicatedMergeTree"));

        assertUnhealthy(actualResult, "%s=true routes trace mutations at 'traces_local', but 'traces' is a "
                .formatted(FLAG) + "ReplicatedMergeTree, not Distributed: the Distributed wrap has not been applied. "
                + "Apply it (exchange_and_wrap.sh --wrap-only) or set the flag back to false — otherwise trace deletes "
                + "fail with UNKNOWN_TABLE (60).");
    }

    /**
     * Presence of {@code traces_local} is not enough — it is where TraceDAO sends its DELETEs, so a same-named table
     * that cannot take mutations fails exactly like an absent one.
     */
    @ParameterizedTest(name = "traces_local engine={0}")
    @ValueSource(strings = {"Distributed", "View", "Log"})
    void check__whenWrapEnabledButTracesLocalCannotTakeMutations__thenUnhealthy(String tracesLocalEngine) {
        var actualResult = check(true, Map.of("traces", "Distributed", "traces_local", tracesLocalEngine));

        assertUnhealthy(actualResult, ("%s=true routes trace mutations at 'traces_local', which exists but is a %s "
                + "rather than a (Replicated)MergeTree. Trace deletes cannot run against that engine, so the wrap is "
                + "pointing at the wrong table.").formatted(FLAG, tracesLocalEngine));
    }

    @Test
    void check__whenWrapEnabledAndTracesIsDistributedButTracesLocalIsMissing__thenUnhealthy() {
        var actualResult = check(true, Map.of("traces", "Distributed"));

        assertUnhealthy(actualResult, ("%s=true routes trace mutations at 'traces_local' and 'traces' is Distributed "
                + "as expected, but table 'traces_local' does not exist. Trace deletes fail with UNKNOWN_TABLE (60); "
                + "the Distributed wrap points at a shard table that is absent from this node.").formatted(FLAG));
    }

    @Test
    void check__whenWrapDisabledButTracesIsDistributed__thenUnhealthy() {
        var actualResult = check(false, Map.of("traces", "Distributed", "traces_local", "ReplicatedMergeTree"));

        assertUnhealthy(actualResult, ("%s=false routes trace mutations directly at 'traces', but 'traces' is a "
                + "Distributed table, which rejects mutations: the Distributed wrap has been applied. Set the flag to "
                + "true and restart — otherwise trace deletes fail with BAD_ARGUMENTS (36) / NOT_IMPLEMENTED (48).")
                .formatted(FLAG));
    }

    @Test
    void check__whenWrapDisabledAndTracesIsNeitherDistributedNorAMergeTree__thenUnhealthy() {
        var actualResult = check(false, Map.of("traces", "Log"));

        assertUnhealthy(actualResult, ("%s=false expects 'traces' to be a (Replicated)MergeTree that takes mutations "
                + "directly, but it is a Log. Trace deletes are not guaranteed to work against this engine.")
                .formatted(FLAG));
    }

    @ParameterizedTest(name = "wrapEnabled={0}")
    @ValueSource(booleans = {true, false})
    void check__whenTracesIsMissingEntirely__thenUnhealthyRegardlessOfTheFlag(boolean wrapEnabled) {
        var actualResult = check(wrapEnabled, Map.of());

        assertUnhealthy(actualResult, ("%s=%b, but table 'traces' does not exist in the analytics database. Trace "
                + "reads and writes cannot work at all; check that the analytics migrations ran.")
                .formatted(FLAG, wrapEnabled));
    }

    private static Stream<Arguments> failureModes() {
        return Stream.of(
                arguments("execution", new ExecutionException(new RuntimeException("ClickHouse unavailable"))),
                arguments("interrupt", new InterruptedException("Interrupted call")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    void check__whenQueryFails__thenUnhealthyAndCancelsQuery(String name, Exception failure) throws Exception {
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS)).thenThrow(failure);
        when(clickHouseClient.queryRecords(eq(TOPOLOGY_QUERY), argThat(probeServerSettings())))
                .thenReturn(failingFuture);

        var actualResult = newHealthCheck(true).execute();

        assertThat(actualResult.isHealthy()).isFalse();
        assertThat(actualResult.getError()).isSameAs(failure);
        verify(failingFuture).cancel(true);
    }

    @Test
    void check__whenInterrupted__thenRestoresTheInterruptFlag() throws Exception {
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                .thenThrow(new InterruptedException("Interrupted call"));
        when(clickHouseClient.queryRecords(eq(TOPOLOGY_QUERY), argThat(probeServerSettings())))
                .thenReturn(failingFuture);

        newHealthCheck(true).execute();

        assertThat(Thread.interrupted()).isTrue();
    }

    private HealthCheck.Result check(boolean wrapEnabled, Map<String, String> tables) {
        // Built before when(...) opens: the row mocks are stubbed themselves, and Mockito rejects that mid-stubbing.
        var records = records(tables);
        when(clickHouseClient.queryRecords(eq(TOPOLOGY_QUERY), argThat(probeServerSettings())))
                .thenReturn(CompletableFuture.completedFuture(records));

        return newHealthCheck(wrapEnabled).execute();
    }

    private ClickHouseTracesTopologyHealthCheck newHealthCheck(boolean wrapEnabled) {
        var dataModel = DatabaseAnalyticsDataModelConfig.builder()
                .tracesDistributedWrapEnabled(wrapEnabled)
                .build();
        return new ClickHouseTracesTopologyHealthCheck(clickHouseClient, HEALTH_CHECK_TIMEOUT, dataModel);
    }

    /**
     * One row per {@code name -> engine} entry, in the shape {@code system.tables} returns. Iteration order is
     * irrelevant to the probe, which indexes by name.
     */
    private Records records(Map<String, String> tables) {
        var rows = tables.entrySet().stream()
                .map(entry -> {
                    var row = mock(GenericRecord.class);
                    when(row.getString("name")).thenReturn(entry.getKey());
                    when(row.getString("engine")).thenReturn(entry.getValue());
                    return row;
                })
                .toList();
        var records = mock(Records.class);
        when(records.iterator()).thenReturn(rows.iterator());
        return records;
    }

    private void assertUnhealthy(HealthCheck.Result actual, String expectedMessage) {
        assertThat(actual.isHealthy()).isFalse();
        assertThat(actual.getMessage()).isEqualTo(expectedMessage);
        assertThat(actual.getError()).isNull();
    }

    private ArgumentMatcher<QuerySettings> probeServerSettings() {
        return settings -> {
            var allSettings = settings.getAllSettings();
            return String.valueOf(HEALTH_CHECK_TIMEOUT_SECONDS)
                    .equals(allSettings.get(CLICKHOUSE_SETTING_MAX_EXECUTION_TIME))
                    && EXPECTED_LOG_COMMENT.equals(allSettings.get(CLICKHOUSE_SETTING_LOG_COMMENT));
        };
    }
}
