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
 * Behaviour of {@link ClickHouseSpansTopologyHealthCheck}: the flag↔topology assertion in both directions, plus the
 * timeout and cancellation contract inherited from {@link AbstractClickHouseHealthCheck}. The spans mirror of
 * {@link ClickHouseTracesTopologyHealthCheckTest}.
 *
 * <p>The mismatch cases are the point of the check, so each asserts the actual message rather than merely that the
 * probe went unhealthy: the message is all an operator gets — the readiness {@code /health-check} serves the verdict
 * alone, so the flag, the observed engine and the fix reach them only through the message, on the admin connector's
 * {@code /healthcheck}. Every expectation is spelled out here rather than derived from the probe's own constants —
 * the two probes share one implementation, so this file and its traces counterpart are what hold the shared wording
 * to what each side needs.
 *
 * <p>The query text is shared too, with the table names bound as parameters, so the stub matches on those parameters:
 * a probe binding the wrong pair finds no stub and fails here. {@link ClickHouseSpansTopologyReadinessTest} covers the
 * same matrix end-to-end against a real ClickHouse.
 */
class ClickHouseSpansTopologyHealthCheckTest {

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 1;
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.seconds(HEALTH_CHECK_TIMEOUT_SECONDS);
    private static final String CLICKHOUSE_SETTING_MAX_EXECUTION_TIME = "clickhouse_setting_max_execution_time";
    private static final String CLICKHOUSE_SETTING_LOG_COMMENT = "clickhouse_setting_log_comment";
    private static final String EXPECTED_LOG_COMMENT = "health_check:clickhouse-spans-topology";

    private static final String TOPOLOGY_QUERY = """
            SELECT name, engine FROM system.tables \
            WHERE database = currentDatabase() AND name IN ({table:String}, {localTable:String})\
            """;

    private static final Map<String, Object> QUERY_PARAMS = Map.of("table", "spans", "localTable", "spans_local");

    private static final String FLAG = "databaseAnalyticsDataModel.spansDistributedWrapEnabled";

    private final Client clickHouseClient = mock(Client.class);

    @AfterEach
    void afterEach() {
        // The interrupt-path test leaves the thread's interrupt flag set; clear so it doesn't leak into subsequent
        // tests on the same JUnit worker thread.
        Thread.interrupted();
    }

    /**
     * Parameterised over the shard engine because the probe accepts the whole MergeTree family there, and the wrap
     * leaves {@code spans_local} as whatever {@code spans} was — {@code ReplicatedReplacingMergeTree} on every install
     * the migrations build. The healthy message names no engine, so the expectation is the same for all of them.
     */
    @ParameterizedTest(name = "spans_local engine={0}")
    @ValueSource(strings = {"MergeTree", "ReplicatedMergeTree", "ReplicatedReplacingMergeTree", "SharedMergeTree"})
    void reportsHealthyWhenWrapEnabledAndSpansIsDistributedOverSpansLocal(String spansLocalEngine) {
        var actualResult = check(true, Map.of("spans", "Distributed", "spans_local", spansLocalEngine));

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage())
                .isEqualTo("'spans' is Distributed over 'spans_local', matching %s=true".formatted(FLAG));
    }

    @ParameterizedTest(name = "engine={0}")
    @ValueSource(strings = {"MergeTree", "ReplicatedMergeTree", "ReplicatedReplacingMergeTree", "SharedMergeTree"})
    void reportsHealthyWhenWrapDisabledAndSpansIsAMergeTree(String engine) {
        var actualResult = check(false, Map.of("spans", engine));

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage()).isEqualTo("'spans' is a %s, matching %s=false".formatted(engine, FLAG));
    }

    /**
     * {@code spans_local} is present here: after the EXCHANGE but before the wrap, the shard table exists while
     * {@code spans} is a plain MergeTree — so the probe cannot infer the wrap from its presence, only from the engine
     * of {@code spans} itself. Which is also why the message must not promise UNKNOWN_TABLE: in this state the routed
     * delete succeeds against the stale shard and the live rows in {@code spans} are never touched.
     */
    @Test
    void reportsUnhealthyWhenWrapEnabledButSpansIsNotWrapped() {
        var actualResult = check(true, Map.of("spans", "ReplicatedMergeTree", "spans_local", "ReplicatedMergeTree"));

        assertUnhealthy(actualResult, """
                %s=true routes span mutations at 'spans_local', but 'spans' is a ReplicatedMergeTree, not \
                Distributed: the Distributed wrap has not been applied (or has been rolled back). Apply it or set the \
                flag back to false — otherwise span deletes either fail with UNKNOWN_TABLE (60) when 'spans_local' is \
                absent, or silently delete from a stale 'spans_local' while the live rows in 'spans' are left \
                untouched.\
                """.formatted(FLAG));
    }

    /**
     * Presence of {@code spans_local} is not enough — it is where SpanDAO sends its DELETEs, so a same-named table
     * that cannot take mutations fails exactly like an absent one.
     */
    @ParameterizedTest(name = "spans_local engine={0}")
    @ValueSource(strings = {"Distributed", "View", "Log"})
    void reportsUnhealthyWhenWrapEnabledButSpansLocalCannotTakeMutations(String spansLocalEngine) {
        var actualResult = check(true, Map.of("spans", "Distributed", "spans_local", spansLocalEngine));

        assertUnhealthy(actualResult, """
                %s=true routes span mutations at 'spans_local', which exists but is a %s rather than a \
                (Replicated)MergeTree. Span deletes cannot run against that engine, so the wrap is pointing at the \
                wrong table.\
                """.formatted(FLAG, spansLocalEngine));
    }

    @Test
    void reportsUnhealthyWhenWrapEnabledAndSpansIsDistributedButSpansLocalIsMissing() {
        var actualResult = check(true, Map.of("spans", "Distributed"));

        assertUnhealthy(actualResult, """
                %s=true routes span mutations at 'spans_local' and 'spans' is Distributed as expected, but table \
                'spans_local' does not exist. Span deletes fail with UNKNOWN_TABLE (60); the Distributed wrap points \
                at a shard table that is absent from this node.\
                """.formatted(FLAG));
    }

    @Test
    void reportsUnhealthyWhenWrapDisabledButSpansIsDistributed() {
        var actualResult = check(false, Map.of("spans", "Distributed", "spans_local", "ReplicatedMergeTree"));

        assertUnhealthy(actualResult, """
                %s=false routes span mutations directly at 'spans', but 'spans' is a Distributed table, which rejects \
                mutations: the Distributed wrap has been applied. Set the flag to true and restart — otherwise span \
                deletes fail with BAD_ARGUMENTS (36) / NOT_IMPLEMENTED (48).\
                """.formatted(FLAG));
    }

    @Test
    void reportsUnhealthyWhenWrapDisabledAndSpansIsNeitherDistributedNorAMergeTree() {
        var actualResult = check(false, Map.of("spans", "Log"));

        assertUnhealthy(actualResult, """
                %s=false expects 'spans' to be a (Replicated)MergeTree that takes mutations directly, but it is a \
                Log. Span deletes are not guaranteed to work against this engine.\
                """.formatted(FLAG));
    }

    @ParameterizedTest(name = "wrapEnabled={0}")
    @ValueSource(booleans = {true, false})
    void reportsUnhealthyWhenSpansIsMissingEntirelyRegardlessOfTheFlag(boolean wrapEnabled) {
        var actualResult = check(wrapEnabled, Map.of());

        assertUnhealthy(actualResult, """
                %s=%b, but table 'spans' does not exist in the analytics database. Span reads and writes cannot work \
                at all; check that the analytics migrations ran.\
                """.formatted(FLAG, wrapEnabled));
    }

    /**
     * A wrapped {@code traces} says nothing about spans: the two cutovers flip independently, so that is a legitimate
     * steady state and must not trip this probe. Guards the one hazard of sharing an implementation — a probe reading
     * its sibling's rows.
     */
    @Test
    void judgesSpansOnItsOwnRowsWhenOnlyTheTracesTablesAreWrapped() {
        var actualResult = check(false, Map.of("spans", "ReplicatedMergeTree", "traces", "Distributed",
                "traces_local", "ReplicatedMergeTree"));

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage())
                .isEqualTo("'spans' is a ReplicatedMergeTree, matching %s=false".formatted(FLAG));
    }

    private static Stream<Arguments> failureModes() {
        return Stream.of(
                arguments("execution", new ExecutionException(new RuntimeException("ClickHouse unavailable"))),
                arguments("interrupt", new InterruptedException("Interrupted call")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    void reportsUnhealthyAndCancelsTheQueryWhenItFails(String name, Exception failure) throws Exception {
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS)).thenThrow(failure);
        when(clickHouseClient.queryRecords(eq(TOPOLOGY_QUERY), eq(QUERY_PARAMS), argThat(probeServerSettings())))
                .thenReturn(failingFuture);

        var actualResult = newHealthCheck(true).execute();

        assertThat(actualResult.isHealthy()).isFalse();
        assertThat(actualResult.getError()).isSameAs(failure);
        verify(failingFuture).cancel(true);
    }

    @Test
    void restoresTheInterruptFlagWhenInterrupted() throws Exception {
        var failingFuture = mock(CompletableFuture.class);
        when(failingFuture.get(HEALTH_CHECK_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS))
                .thenThrow(new InterruptedException("Interrupted call"));
        when(clickHouseClient.queryRecords(eq(TOPOLOGY_QUERY), eq(QUERY_PARAMS), argThat(probeServerSettings())))
                .thenReturn(failingFuture);

        newHealthCheck(true).execute();

        assertThat(Thread.interrupted()).isTrue();
    }

    private HealthCheck.Result check(boolean wrapEnabled, Map<String, String> tables) {
        // Built before when(...) opens: the row mocks are stubbed themselves, and Mockito rejects that mid-stubbing.
        var records = records(tables);
        when(clickHouseClient.queryRecords(eq(TOPOLOGY_QUERY), eq(QUERY_PARAMS), argThat(probeServerSettings())))
                .thenReturn(CompletableFuture.completedFuture(records));

        return newHealthCheck(wrapEnabled).execute();
    }

    /**
     * The trace flag is left at its default {@code false} throughout: the spans probe must read the spans flag, and a
     * builder setting both would not show it.
     */
    private ClickHouseSpansTopologyHealthCheck newHealthCheck(boolean wrapEnabled) {
        var dataModel = DatabaseAnalyticsDataModelConfig.builder()
                .spansDistributedWrapEnabled(wrapEnabled)
                .build();
        return new ClickHouseSpansTopologyHealthCheck(clickHouseClient, HEALTH_CHECK_TIMEOUT, dataModel);
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
