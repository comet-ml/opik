package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import lombok.Builder;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Executable guard, run on the target ClickHouse image against synthetic data, for the {@code Nullable}-removal of the
 * {@code Float64} analytics columns: it pins how {@code NaN} sentinels behave under the aggregates Opik metrics use,
 * and that the guarded {@code duration} MATERIALIZED formula emits {@code NaN} at the epoch sentinel. It reads results
 * through the R2DBC client the DAOs use, so it also confirms R2DBC marshals a {@code NaN} {@code Float64} to Java
 * {@code Double.NaN} — the exact operation the read path depends on once the columns are non-nullable.
 *
 * <p>ClickHouse does <em>not</em> treat {@code NaN} uniformly the way it treats {@code NULL} on a
 * {@code Nullable(Float64)} column:</p>
 * <ul>
 *     <li>{@code min}, {@code max}, {@code quantile} skip {@code NaN} — matching {@code NULL} semantics.</li>
 *     <li>{@code sum} and {@code avg} propagate {@code NaN} and {@code count} counts the {@code NaN} row — deviating
 *     from {@code NULL} semantics.</li>
 *     <li>The {@code <agg>If(x, NOT isNaN(x))} form reproduces {@code NULL}-skipping exactly, so non-nullable
 *     {@code sum}/{@code avg}/{@code count} call sites must use it.</li>
 * </ul>
 *
 * <p>The guarded formula must use the bare {@code nan} constant ({@code nan()} fails to parse on this ClickHouse
 * version) and preserves sub-millisecond resolution.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NaNAwareAggregateIntegrationTest {

    /**
     * {@code sample_id} 1..4 carry real measurements; the 5th is the absent-value sentinel — {@code NaN} on the
     * non-nullable {@code Float64} column, {@code NULL} on the legacy {@code Nullable(Float64)} column.
     */
    private static final String SYNTHETIC_TABLE = "nan_agg_synthetic_test";

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private ClickHouseContainer clickHouseContainer;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUp() {
        clickHouseContainer = ClickHouseContainerUtils.newClickHouseContainer(false);
        Startables.deepStart(clickHouseContainer).join();

        // "default" (rather than the app database) because this lightweight test does not run the app migrations that
        // create the app database; it only needs a place for its own synthetic table.
        connectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, "default")
                .build();

        execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    sample_id             UInt8,
                    value_non_nullable    Float64,
                    value_nullable        Nullable(Float64)
                ) ENGINE = Memory
                """.formatted(SYNTHETIC_TABLE));
        execute("TRUNCATE TABLE %s".formatted(SYNTHETIC_TABLE));
        // INSERT ... SELECT so NaN reaches the column without round-tripping through JSON (which has no NaN literal).
        execute("""
                INSERT INTO %s SELECT
                    toUInt8(number)                           AS sample_id,
                    if(number = 5, nan, toFloat64(number))    AS value_non_nullable,
                    if(number = 5, NULL, toFloat64(number))   AS value_nullable
                FROM numbers(1, 5)
                """.formatted(SYNTHETIC_TABLE));
    }

    @AfterAll
    void tearDown() {
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    @Test
    void rangeAndQuantileAggregatesSkipNaNLikeNull() {
        var aggregates = queryOne("""
                SELECT min(value_nullable)                  AS min_nullable,
                       max(value_nullable)                  AS max_nullable,
                       quantile(0.9)(value_nullable)        AS quantile_nullable,
                       min(value_non_nullable)              AS min_non_nullable,
                       max(value_non_nullable)              AS max_non_nullable,
                       quantile(0.9)(value_non_nullable)    AS quantile_non_nullable
                FROM %s
                """.formatted(SYNTHETIC_TABLE),
                row -> RangeQuantileAggregates.builder()
                        .minNullable(row.get("min_nullable", Double.class))
                        .maxNullable(row.get("max_nullable", Double.class))
                        .quantileNullable(row.get("quantile_nullable", Double.class))
                        .minNonNullable(row.get("min_non_nullable", Double.class))
                        .maxNonNullable(row.get("max_non_nullable", Double.class))
                        .quantileNonNullable(row.get("quantile_non_nullable", Double.class))
                        .build());

        assertThat(aggregates.minNonNullable()).isEqualTo(aggregates.minNullable()).isEqualTo(1.0d);
        assertThat(aggregates.maxNonNullable()).isEqualTo(aggregates.maxNullable()).isEqualTo(4.0d);
        assertThat(aggregates.quantileNonNullable()).isEqualTo(aggregates.quantileNullable());
    }

    @Test
    void wrappedAggregatesRestoreNullSemantics() {
        var aggregates = queryOne("""
                SELECT countIf(NOT isNaN(value_non_nullable))                               AS count_wrapped,
                       sumIf(value_non_nullable, NOT isNaN(value_non_nullable))             AS sum_wrapped,
                       avgIf(value_non_nullable, NOT isNaN(value_non_nullable))             AS avg_wrapped,
                       minIf(value_non_nullable, NOT isNaN(value_non_nullable))             AS min_wrapped,
                       maxIf(value_non_nullable, NOT isNaN(value_non_nullable))             AS max_wrapped,
                       quantileIf(0.9)(value_non_nullable, NOT isNaN(value_non_nullable))   AS quantile_wrapped,
                       quantile(0.9)(value_nullable)                                        AS quantile_nullable
                FROM %s
                """.formatted(SYNTHETIC_TABLE),
                row -> WrappedAggregates.builder()
                        .count(row.get("count_wrapped", Long.class))
                        .sum(row.get("sum_wrapped", Double.class))
                        .avg(row.get("avg_wrapped", Double.class))
                        .min(row.get("min_wrapped", Double.class))
                        .max(row.get("max_wrapped", Double.class))
                        .quantile(row.get("quantile_wrapped", Double.class))
                        .quantileNullable(row.get("quantile_nullable", Double.class))
                        .build());

        assertThat(aggregates.count()).isEqualTo(4L);
        assertThat(aggregates.sum()).isEqualTo(10.0d);
        assertThat(aggregates.avg()).isEqualTo(2.5d);
        assertThat(aggregates.min()).isEqualTo(1.0d);
        assertThat(aggregates.max()).isEqualTo(4.0d);
        assertThat(aggregates.quantile()).isEqualTo(aggregates.quantileNullable());
    }

    /**
     * Post-cutover, a brand-new trace's dedup merge LEFT JOINs an absent old row. A non-nullable Float64 fills the
     * join miss with 0.0 (the type zero — not the NaN default), so a bare NOT isNaN(old.ttft) guard wrongly keeps
     * 0.0 and discards the new value. The id-presence guard (old.id != '') falls through to the new value. This
     * pins why the ttft merge needs the id guard (end_time is safe only because its type zero is the epoch).
     */
    @Test
    void nonNullableTtftMergeUsesNewValueOnJoinMiss() {
        var id = ID_GENERATOR.generateId();
        var newTtft = RandomUtils.secure().randomDouble(1.0, 1_000.0);
        var expected = MergeMissResult.builder()
                .missFill(0.0d)
                .unguardedResult(0.0d)
                .guardedResult(newTtft)
                .build();
        var actual = queryOne(
                """
                        WITH new_trace AS (
                          SELECT toFixedString('%s', 36) AS id,
                          %s AS ttft
                        )
                        SELECT old_trace.ttft                                                                            AS miss_fill,
                               multiIf(NOT isNaN(old_trace.ttft), old_trace.ttft, new_trace.ttft)                        AS unguarded_result,
                               multiIf(old_trace.id != '' AND NOT isNaN(old_trace.ttft), old_trace.ttft, new_trace.ttft) AS guarded_result
                        FROM new_trace
                        LEFT JOIN (
                            SELECT toFixedString('', 36) AS id,
                            CAST(0 AS Float64) AS ttft WHERE 1 = 0
                        ) AS old_trace
                        ON new_trace.id = old_trace.id
                        """
                        .formatted(id, newTtft),
                row -> MergeMissResult.builder()
                        .missFill(row.get("miss_fill", Double.class))
                        .unguardedResult(row.get("unguarded_result", Double.class))
                        .guardedResult(row.get("guarded_result", Double.class))
                        .build());

        assertThat(actual).isEqualTo(expected);
    }

    Stream<Arguments> guardedDurationFormula() {
        return Stream.of(
                arguments("1.5s span -> 1500ms", "2026-01-01 00:00:00.000000", "2026-01-01 00:00:01.500000", 1500.0d),
                arguments("333 microseconds span -> 0.333ms", "2026-01-01 00:00:00.000123",
                        "2026-01-01 00:00:00.000456", 0.333d),
                arguments("epoch end_time -> NaN", "2026-01-01 00:00:00.000000", "1970-01-01 00:00:00.000000", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void guardedDurationFormula(String label, String startTime, String endTime, Double expectedMs) {
        var actual = durationMs(startTime, endTime);

        if (expectedMs == null) {
            assertThat(actual).isNaN();
        } else {
            assertThat(actual).isEqualTo(expectedMs);
        }
    }

    /**
     * Mirrors the guarded non-nullable duration MATERIALIZED expression (bare `nan`, since `nan()` does not parse
     * on the target ClickHouse version).
     */
    private double durationMs(String startTime, String endTime) {
        return queryOne("""
                WITH toDateTime64('%s', 6)                                                               AS start_time,
                     toDateTime64('%s', 6)                                                               AS end_time,
                     toDateTime64('1970-01-01 00:00:00', 6)                                              AS epoch
                SELECT if(end_time = epoch, nan, dateDiff('microsecond', start_time, end_time) / 1000.0) AS duration_ms
                """.formatted(startTime, endTime),
                row -> row.get("duration_ms", Double.class));
    }

    private <T> T queryOne(String sql, Function<Row, T> mapper) {
        return Mono.usingWhen(
                connectionFactory.create(),
                connection -> Mono.from(connection.createStatement(sql).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> mapper.apply(row)))),
                Connection::close)
                .block();
    }

    private void execute(String sql) {
        Mono.usingWhen(
                connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(sql).execute())
                        .flatMap(Result::getRowsUpdated)
                        .then(),
                Connection::close)
                .block();
    }

    @Builder(toBuilder = true)
    record RangeQuantileAggregates(
            Double minNullable,
            Double maxNullable,
            Double quantileNullable,
            Double minNonNullable,
            Double maxNonNullable,
            Double quantileNonNullable) {
    }

    @Builder(toBuilder = true)
    record WrappedAggregates(Long count,
            Double sum,
            Double avg,
            Double min,
            Double max,
            Double quantile,
            Double quantileNullable) {
    }

    @Builder(toBuilder = true)
    record MergeMissResult(Double missFill, Double unguardedResult, Double guardedResult) {
    }
}
