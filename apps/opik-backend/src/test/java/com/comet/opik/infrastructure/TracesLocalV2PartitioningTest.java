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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the traces_local_v2 partition design (migration 000101) end to end: the {@code id_at DateTime MATERIALIZED
 * UUIDv7ToDateTime(toUUID(id))} → {@code PARTITION BY toMonday(id_at)} chain. Two behaviors are pinned as permanent
 * regression guards:
 *
 * <ul>
 *   <li><b>Partition stability across upserts.</b> {@code id_at} is computed by ClickHouse from the immutable {@code id},
 *   so two versions of the same logical row (differing only in {@code last_updated_at}) must land in one weekly
 *   partition — the property {@code ReplacingMergeTree}'s in-partition dedup depends on. Regresses if the
 *   {@code id_at} expression or the partition key stops deriving from the immutable {@code id}.</li>
 *   <li><b>Partition pruning.</b> The planner does not infer that {@code id} is monotonic through
 *   {@code UUIDv7ToDateTime} in the partition expression, so an {@code id}-range predicate alone prunes only via the
 *   primary key (every partition is still read). Adding the parallel {@code toMonday(id_at)} bound the {@code TraceDAO}
 *   read path emits — derived from the same UUIDv7 as the id-range bound — is what prunes partitions. Read via
 *   {@code EXPLAIN indexes = 1}: the {@code MinMax} index block reflects part-level pruning on the partition-expression
 *   column, so its selected count drops below the total exactly when partition pruning engages.</li>
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

        // Queries the same inner id range (weeks 1..2 of the four seeded) as idRangeWithToMondayBoundPrunesPartitions,
        // so the two are a controlled pair whose only difference is the added toMonday(id_at) bound. No id_at predicate:
        // the MinMax index on the partition column has nothing to prune by, so every part is read. Should the target
        // LTS start inferring id monotonicity through UUIDv7ToDateTime, this fails — the signal to revisit whether the
        // read path still needs its explicit id_at predicate.
        assertThat(actualParts.selected()).isEqualTo(actualParts.total());
    }

    @Test
    void idRangeWithToMondayBoundPrunesPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        // The exact predicate the TraceDAO read path emits: each id-range bound carries a parallel toMonday(id_at)
        // bound derived from the same UUIDv7, expressed on the partition expression itself.
        var actualParts = minMaxParts("""
                SELECT
                    id
                FROM traces_local_v2
                WHERE workspace_id = :workspace_id
                    AND id >= :id_lo
                    AND id <= :id_hi
                    AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC'))
                    AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_hi), 'UTC'))
                """, statement -> statement
                .bind("workspace_id", seed.workspaceId())
                .bind("id_lo", seed.ids().get(1))
                .bind("id_hi", seed.ids().get(2)));

        // ids 1..2 are the inner two of the four seeded weeks, so week 0 sits below the range and week 3 above; both
        // prune away, demonstrating pruning on each bound. ClickHouse folds the toMonday(id_at) bound back onto the
        // id_at MinMax via toMonday's monotonicity, so the pruning shows on the MinMax entry (the Partition entry then
        // reports the already-pruned set).
        assertThat(actualParts.selected()).isLessThan(actualParts.total());
    }

    @Test
    void idPointLookupWithToMondayEqualityPrunesPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        // The point-lookup shape the TraceDAO read path emits (e.g. SELECT_DETAILS_BY_ID): a single id paired with a
        // toMonday(id_at) equality on that same UUIDv7, the equality counterpart of the range bound above.
        var actualParts = minMaxParts("""
                SELECT
                    id
                FROM traces_local_v2
                WHERE workspace_id = :workspace_id
                    AND id = :id
                    AND toMonday(id_at) = toMonday(UUIDv7ToDateTime(toUUID(:id), 'UTC'))
                """, statement -> statement
                .bind("workspace_id", seed.workspaceId())
                .bind("id", seed.ids().get(1)));

        // Equality on the partition expression pins the scan to the single week id 1 lands in; the other three seeded
        // weeks (and every out-of-window part) fall away, so the selected count drops below the total.
        assertThat(actualParts.selected()).isLessThan(actualParts.total());
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
