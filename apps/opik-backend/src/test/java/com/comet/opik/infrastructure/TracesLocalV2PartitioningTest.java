package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the traces_local_v2 partition design (migration 000114) end to end: the {@code id_at DateTime64(0)
 * MATERIALIZED UUIDv7ToDateTime(toUUID(id))} → {@code PARTITION BY toYYYYMMDD(toDate32(id_at) -
 * toIntervalDay(toDayOfWeek(id_at, 1)))} chain. The key computes the honest Monday of {@code id_at}'s week in
 * {@code Date32}, so it never wraps a far-future id the way a 16-bit {@code toMonday} {@code Date} would. Behaviors
 * pinned as permanent regression guards:
 *
 * <ul>
 *   <li><b>Partition stability across upserts.</b> {@code id_at} is computed by ClickHouse from the immutable {@code id},
 *   so two versions of the same logical row (differing only in {@code last_updated_at}) must land in one weekly
 *   partition — the property {@code ReplacingMergeTree}'s in-partition dedup depends on. Regresses if the
 *   {@code id_at} expression or the partition key stops deriving from the immutable {@code id}.</li>
 *   <li><b>Pruning with the read predicates.</b> The read path emits the same {@code Date32} week-start bounds as the
 *   partition key, paired with its id-range. They prune because {@code id_at} is a column of the partition key, so
 *   ClickHouse keeps a {@code MinMax} over {@code id_at} per part and the expression is monotonic over a part's narrow
 *   {@code id_at} range. An id-range predicate alone does not prune (the planner doesn't infer {@code id → id_at}
 *   monotonicity through {@code UUIDv7ToDateTime}). Read via {@code EXPLAIN indexes = 1}: the {@code MinMax} block's
 *   selected count drops below the total exactly when pruning engages. These bounds were {@code toMonday(id_at)} until
 *   OPIK-7456's read-path fix — that wrapped far-future ids into a past week and dropped rows the id-range admits, so
 *   the bound acted as a filter rather than a hint.</li>
 *   <li><b>Honest far-future isolation.</b> A legitimate row whose UUIDv7 carries a far-future timestamp lands in its own
 *   distinct, honest weekly partition, never mixed with a real recent week.</li>
 *   <li><b>Week-expression correctness.</b> The {@code Date32} Monday equals {@code toMonday} across the in-range
 *   calendar and stays honest where {@code toMonday} wraps — both far-future ids and the epoch week a non-v7 id lands
 *   in — independent of the datetime setting.</li>
 * </ul>
 *
 * <p>Runs directly against ClickHouse via {@link TransactionTemplateAsync} over the test container's connection factory
 * — no Dropwizard app — mirroring the raw column-level access of {@link TracesLocalV2TableTest}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracesLocalV2PartitioningTest {

    /**
     * A fixed historical Monday that weekly ids are minted at week offsets from. Fixed (not {@code now}-derived) so the
     * partition math is deterministic and the seeded weeks never overlap the wall-clock (now-based) rows other suites
     * insert into this reused container — a {@code now}-derived anchor could also drift across a week boundary
     * mid-suite. It is an actual Monday, so {@code toMonday} of it is itself; its exact value is otherwise immaterial,
     * since every assertion here is relative (part counts, distinct-partition counts), never a literal partition name.
     * The table has no TTL, so a far-past anchor is never evicted.
     */
    private static final LocalDate ANCHOR_MONDAY = LocalDate.of(2025, 3, 3);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final TransactionTemplateAsync transactionTemplateAsync;

    {
        Startables.deepStart(zookeeperContainer, clickHouseContainer).join();
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        transactionTemplateAsync = TransactionTemplateAsync.create(
                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME).build());
    }

    /**
     * The two versions of one id (an upsert) must occupy exactly one weekly partition — the property
     * {@code ReplacingMergeTree}'s in-partition dedup depends on. Their {@code last_updated_at} values straddle a week
     * boundary on purpose: placement must follow the id-derived {@code id_at} (week 0 for both), so a regression that
     * repartitioned on the version column would split them into two partitions and fail here. Asserts the distinct
     * partition count rather than the physical row count: the count is invariant under merges (a merge only collapses
     * rows within a partition, never moves them across one), so the guard needs no merge coordination.
     */
    @Test
    void bothVersionsOfSameIdLandInOneWeeklyPartition() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var weekInstant0 = weekInstant(0);
        var id = ID_GENERATOR.generateId(weekInstant0);
        insert(List.of(id), workspaceId, projectId, weekInstant0);
        insert(List.of(id), workspaceId, projectId, weekInstant(1));

        var actualDistinctPartitions = distinctPartitionsFor(workspaceId, projectId, id);
        assertThat(actualDistinctPartitions).isEqualTo(1L);
    }

    @Test
    void idRangePredicateAloneDoesNotPrunePartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        var actualParts = minMaxParts("""
                SELECT
                    id
                FROM traces_local_v2
                WHERE workspace_id = :workspace_id
                    AND id >= :id_lo
                    AND id <= :id_hi
                """, statement -> statement
                .bind("workspace_id", seed.workspaceId())
                .bind("id_lo", seed.ids().get(1))
                .bind("id_hi", seed.ids().get(2)));

        // Queries the same inner id range (weeks 1..2 of the four seeded) as idRangeWithWeekStartBoundPrunesPartitions,
        // so the two are a controlled pair whose only difference is the added week-start bound. With no id_at
        // predicate the id_at MinMax has nothing to constrain (the planner doesn't infer id -> id_at monotonicity through
        // UUIDv7ToDateTime), so every part is read. Should the target LTS start inferring that, this fails — the signal
        // to revisit whether the read path still needs its explicit id_at predicate.
        assertThat(actualParts.selected()).isEqualTo(actualParts.total());
    }

    @Test
    void idRangeWithWeekStartBoundPrunesPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        // The exact predicate the TraceDAO read path emits: each id-range bound carries a parallel Date32 week-start bound
        // derived from the same UUIDv7. id_at is a column of the partition-key expression, so ClickHouse keeps a MinMax
        // over id_at per part; toMonday is monotonic over a part's narrow id_at range, so these bounds prune parts via
        // that MinMax even though the key expression itself does not mention toMonday.
        var actualParts = minMaxParts(
                """
                        SELECT
                            id
                        FROM traces_local_v2
                        WHERE workspace_id = :workspace_id
                            AND id >= :id_lo
                            AND id <= :id_hi
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) >= toMonday(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC'))
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) <= toMonday(UUIDv7ToDateTime(toUUID(:id_hi), 'UTC'))
                        """,
                statement -> statement
                        .bind("workspace_id", seed.workspaceId())
                        .bind("id_lo", seed.ids().get(1))
                        .bind("id_hi", seed.ids().get(2)));

        // ids 1..2 are the inner two of the four seeded weeks, so week 0 sits below the range and week 3 above; both
        // prune away, demonstrating pruning on each bound. The week-start bounds prune via the id_at MinMax (id_at
        // being a partition-key column), so the MinMax entry's selected count drops below the total.
        assertThat(actualParts.selected()).isLessThan(actualParts.total());
    }

    @Test
    void idPointLookupWithToMondayEqualityPrunesPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        // The point-lookup shape the TraceDAO read path emits (e.g. SELECT_DETAILS_BY_ID): a single id paired with a
        // week-start equality on that same UUIDv7, the equality counterpart of the range bound above.
        var actualParts = minMaxParts(
                """
                        SELECT
                            id
                        FROM traces_local_v2
                        WHERE workspace_id = :workspace_id
                            AND id = :id
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) = toMonday(UUIDv7ToDateTime(toUUID(:id), 'UTC'))
                        """,
                statement -> statement
                        .bind("workspace_id", seed.workspaceId())
                        .bind("id", seed.ids().get(1)));

        // Equality on the week-start expression prunes via the id_at MinMax to the single week id 1 lands in; the other three
        // seeded weeks (and every out-of-window part) fall away, so selected drops below total.
        assertThat(actualParts.selected()).isLessThan(actualParts.total());
    }

    /**
     * The partition key wraps the honest {@code Date32} week in {@code toYYYYMMDD}, so it resolves to {@code UInt32}: the
     * partition id stays a human-readable {@code YYYYMMDD} (e.g. 20250303), legible in ZooKeeper paths, part directory
     * names and system.parts, rather than the opaque days-since-epoch integer a bare {@code Date32} key would produce.
     * Pinning the type makes a revert to that bare key fail here.
     */
    @Test
    void partitionKeyResolvesToUInt32ForReadableIds() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var id = ID_GENERATOR.generateId(weekInstant(0));
        insert(List.of(id), workspaceId, projectId, weekInstant(0));

        assertThat(partitionKeyTypeFor(workspaceId, projectId, id)).isEqualTo("Tuple(UInt32)");
    }

    /**
     * The correctness guarantee for legitimate rows whose UUIDv7 carries a far-future timestamp (the litellm ~2201 bug):
     * they must occupy their own honest weekly partition, never mixed into a real recent week — otherwise a per-week
     * {@code DROP PARTITION} / retention / tiering operation on that real week would also touch these rows, and vice
     * versa. Seeds a present-day row and a ~2201 row under one (workspace, project) and asserts they land in different
     * partitions and that the far-future row's partition is its honest ~2201 week (not the ~2021 that a 16-bit {@code toMonday}
     * would wrap it into).
     */
    @Test
    void farFutureRowIsolatesIntoItsOwnHonestWeeklyPartition() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var presentId = ID_GENERATOR.generateId(weekInstant(0));
        var farFutureId = ID_GENERATOR.generateId(Instant.parse("2201-06-01T00:00:00Z"));
        insert(List.of(presentId, farFutureId), workspaceId, projectId, weekInstant(0));

        var presentPartition = partitionIdFor(workspaceId, projectId, presentId);
        var farFuturePartition = partitionIdFor(workspaceId, projectId, farFutureId);

        assertThat(farFuturePartition).isNotEqualTo(presentPartition).startsWith("2201");
    }

    /**
     * The read-path counterpart of {@link #farFutureRowIsolatesIntoItsOwnHonestWeeklyPartition()}, and the regression
     * that actually shipped (OPIK-7456): honest partitioning is worthless if the read predicate then filters those rows
     * back out. Every {@code id}-range bound in the DAOs carries a parallel week-start bound documented as "a strict
     * consequence of the id-range" — so it must never exclude a row the id-range admits. Under {@code toMonday} it did:
     * a ~2201 id clears {@code id >= :id_lo} but its 16-bit {@code Date} wraps to a ~2021 Monday and fails the week
     * bound, so the row vanishes from a result it belongs in. Seeds a present-day row and a far-future row, applies the
     * exact predicate the read path emits with the present-day id as the lower bound, and asserts BOTH come back.
     * Fails against {@code toMonday(id_at)}; passes against the {@code Date32} expression.
     */
    @Test
    void weekStartLowerBoundKeepsFarFutureRowsThatTheIdRangeAdmits() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var presentId = ID_GENERATOR.generateId(weekInstant(0));
        var farFutureId = ID_GENERATOR.generateId(Instant.parse("2201-06-01T00:00:00Z"));
        insert(List.of(presentId, farFutureId), workspaceId, projectId, weekInstant(0));

        var returnedIds = transactionTemplateAsync.stream(connection -> {
            var statement = connection.createStatement("""
                    SELECT id
                    FROM traces_local_v2
                    WHERE workspace_id = :workspace_id
                        AND id >= :id_lo
                        AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                            >= toMonday(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC'))
                    """);
            statement.bind("workspace_id", workspaceId).bind("id_lo", presentId);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, ignored) -> row.get("id", String.class)));
        }).collectList().block();

        assertThat(returnedIds).containsExactlyInAnyOrder(presentId.toString(), farFutureId.toString());
    }

    /**
     * Pins the far-future-safe weekly-Monday expression {@code toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))}
     * (OPIK-7456). {@code toMonday} returns a 16-bit {@code Date} that wraps past year 2149, so a legitimate row whose
     * UUIDv7 carries a far-future timestamp partitions into a plausible recent week and mixes with real data. The
     * {@code Date32} expression computes the same Monday as {@code toMonday} across the normal range without ever
     * wrapping. Asserts the equivalence day-by-day across a full week and across a year boundary.
     */
    @ParameterizedTest(name = "honest week == toMonday for {0}")
    @ValueSource(strings = {
            "2025-03-03", "2025-03-04", "2025-03-05", "2025-03-06", "2025-03-07", "2025-03-08", "2025-03-09",
            "2024-12-30", "2024-12-31", "2025-01-01", "2025-01-05"})
    void honestWeekExpressionMatchesToMondayInRange(String date) {
        assertThat(weekProbe(date, "toMonday(d) = hw")).isEqualTo(1L);
    }

    /**
     * Where {@code toMonday}'s 16-bit {@code Date} wraps, the {@code Date32} expression stays honest — at both extremes:
     * far-future ids (litellm ~2201) that {@code toMonday} folds into a recent week, and the epoch week it underflows to
     * ~2149, reachable by any non-v7 {@code id} (a v4 or nil UUID), for which {@code UUIDv7ToDateTime} returns
     * {@code 1970-01-01}. Asserts the <em>exact</em> expected Monday as {@code YYYYMMDD} against a Java oracle
     * ({@code toMonday} can't be the oracle — it wraps), pinning both ends of the {@code Date32} window and catching an
     * off-by-one-week regression that would still land on some Monday in the right year.
     */
    @ParameterizedTest(name = "honest week is the exact Monday of {0}''s week")
    @ValueSource(strings = {"1970-01-01", "2160-06-01", "2201-06-01", "2250-06-01", "2298-06-01"})
    void honestWeekExpressionStaysHonestWhereToMondayWraps(String date) {
        var expectedMonday = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(weekProbe(date, "toYYYYMMDD(hw)"))
                .isEqualTo(Long.parseLong(expectedMonday.format(DateTimeFormatter.BASIC_ISO_DATE)));
    }

    /**
     * Seeds four consecutive weekly partitions in one INSERT: the four ids fall in four distinct weeks, so ClickHouse
     * writes one part per partition. Returns the ids so the reads target the same rows.
     */
    private Seed seedConsecutiveWeeklyPartitions() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var ids = List.of(
                ID_GENERATOR.generateId(weekInstant(0)),
                ID_GENERATOR.generateId(weekInstant(1)),
                ID_GENERATOR.generateId(weekInstant(2)),
                ID_GENERATOR.generateId(weekInstant(3)));
        insert(ids, workspaceId, projectId, Instant.now());
        return Seed.builder().workspaceId(workspaceId).projectId(projectId).ids(ids).build();
    }

    /**
     * Multi-row batch insert following the TraceDAO idiom: the {@code <items>} StringTemplate expands one tuple per row
     * (id bound per row; workspace/project/last_updated_at shared across the batch), and only the columns the tests
     * exercise are bound — the rest take their DDL defaults.
     */
    private void insert(List<UUID> ids, String workspaceId, UUID projectId, Instant lastUpdatedAt) {
        var sql = TemplateUtils.getBatchSql("""
                INSERT INTO traces_local_v2 (
                    id,
                    workspace_id,
                    project_id,
                    last_updated_at
                )
                FORMAT Values
                    <items:{item |
                        (
                            :id<item.index>,
                            :workspace_id,
                            :project_id,
                            :last_updated_at
                        )
                        <if(item.hasNext)>,<endif>
                    }>
                ;
                """, ids.size()).render();
        transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("project_id", projectId)
                    .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt));
            for (int index = 0; index < ids.size(); index++) {
                statement.bind("id" + index, ids.get(index));
            }
            return Mono.from(statement.execute());
        }).block();
    }

    private long distinctPartitionsFor(String workspaceId, UUID projectId, UUID id) {
        return transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
                SELECT
                    uniqExact(_partition_id) AS distinct_partitions
                FROM traces_local_v2
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("id", id)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("distinct_partitions", Long.class)))))
                .block();
    }

    private String partitionKeyTypeFor(String workspaceId, UUID projectId, UUID id) {
        return partitionInfoFor(workspaceId, projectId, id).getRight();
    }

    private String partitionIdFor(String workspaceId, UUID projectId, UUID id) {
        return partitionInfoFor(workspaceId, projectId, id).getLeft();
    }

    private Pair<String, String> partitionInfoFor(String workspaceId, UUID projectId, UUID id) {
        return transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
                SELECT
                    _partition_id AS partition_id,
                    toTypeName(_partition_value) AS key_type
                FROM traces_local_v2
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                LIMIT 1
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("id", id)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> Pair.of(
                        row.get("partition_id", String.class), row.get("key_type", String.class))))))
                .block();
    }

    /**
     * Evaluates {@code expr} against a single date bound to {@code d} (a {@code DateTime64} at noon UTC, as {@code id_at}
     * is), with {@code hw} pre-bound to the honest weekly-Monday expression. The date is a value, so it is a bind
     * parameter; {@code expr} is a SQL fragment the test supplies (a bind can't stand in for a fragment), so it is
     * interpolated. Returns the scalar as a long ({@code toInt64} normalizes booleans/dates for a uniform read).
     */
    private long weekProbe(String date, String expr) {
        return transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
                WITH toDateTime64(:date, 0, 'UTC') AS d,
                     toDate32(d) - toIntervalDay(toDayOfWeek(d, 1)) AS hw
                SELECT toInt64(%s) AS v
                """.formatted(expr))
                .bind("date", date + " 12:00:00")
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("v", Long.class)))))
                .block();
    }

    /**
     * Runs {@code EXPLAIN indexes = 1, json = 1} for the query and returns its {@code MinMax} index entry. That entry
     * reflects part-level pruning on the partition-expression column ({@code id_at}): {@code Initial Parts} is every
     * active part in the (reused) table, {@code Selected Parts} is what survives partition pruning.
     */
    private MinMaxParts minMaxParts(String selectSql, Consumer<Statement> binder) {
        var explainRows = transactionTemplateAsync.stream(connection -> {
            var statement = connection.createStatement("EXPLAIN indexes = 1, json = 1 %s".formatted(selectSql));
            binder.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, ignored) -> row.get("explain", String.class)));
        }).collectList().block();

        var explain = String.join("\n", explainRows);
        var indexes = JsonUtils.getJsonNodeFromString(explain).findValue("Indexes");
        if (indexes != null) {
            for (JsonNode index : indexes) {
                if ("MinMax".equals(index.path("Type").asText())) {
                    return JsonUtils.treeToValue(index, MinMaxParts.class);
                }
            }
        }
        throw new AssertionError("No MinMax index in EXPLAIN output:\n" + explain);
    }

    private Instant weekInstant(int weekOffset) {
        return ANCHOR_MONDAY.plusWeeks(weekOffset).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    @Builder(toBuilder = true)
    private record Seed(String workspaceId, UUID projectId, List<UUID> ids) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MinMaxParts(
            @JsonProperty("Selected Parts") int selected,
            @JsonProperty("Initial Parts") int total) {
    }
}
