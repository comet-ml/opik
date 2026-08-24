package com.comet.opik.db;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.sql.Connection;
import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.db.TracesSchemaParity.BACKUP;
import static com.comet.opik.db.TracesSchemaParity.SHADOW;
import static com.comet.opik.db.TracesSchemaParity.SHARD;
import static com.comet.opik.db.TracesSchemaParity.TRACES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The post-cutover half of the trace DDL topology guard, and the reason the guard exists: it applies the real changelog
 * against the topology a cut-over install actually runs.
 *
 * <p><b>How the topology is reached.</b> The cutover is operator work, not a migration, so no changelog apply can
 * produce it. This test stops the changelog after the shadow-table migration ({@link #CUTOVER_SPLICE_POINT}), splices
 * in the runbook's {@code EXCHANGE} + {@code Distributed} wrap, then resumes the changelog. Every migration after the
 * splice point — including whichever one a pull request is adding — therefore runs against the live post-cutover
 * layout: {@code traces} a {@code Distributed} wrapper, {@code traces_local} the partitioned shard beneath it.
 *
 * <p><b>The failure this catches is silent.</b> Post-cutover, a shard-only {@code ADD COLUMN} succeeds — ClickHouse
 * raises nothing — but the column is not readable through the wrapper, so the feature that added it is broken on every
 * cut-over install while the migration, and every test that does not look at the wrapper, stays green.
 * {@link #shardOnlyColumnIsUnreadableUntilTheWrapperIsAlteredToo()} pins both that behaviour and its remedy, so the
 * playbook's "read-facing changes go to both" rule is backed by an executable demonstration rather than by assertion.
 *
 * <p><b>Splice, not a rewritten changelog.</b> The spliced statements mirror the shipped reference SQL
 * ({@code data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000003_exchange_and_wrap.sql}) and the
 * inline statements {@code TracesLocalV2CutoverTest} already validates end to end; this test asserts what the resulting
 * <i>schema</i> looks like to later migrations, not that the cutover moves data correctly, which is that test's job.
 *
 * <p><b>Dedicated, non-reused containers</b> because the splice destructively swaps the live {@code traces} table, and
 * the negative tests then drift it further. A container reused across suites (CI sets
 * {@code TESTCONTAINERS_REUSE_ENABLE}) would hand a post-cutover {@code traces} to whatever ran next.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Traces Schema Parity - Post-cutover")
class TracesSchemaParityPostCutoverTest {

    /**
     * The migration the changelog stops after so the cutover transform lands where it does in production: the last
     * migration that shapes the shadow table, and therefore the last one that runs pre-cutover on an install that then
     * cuts over. Everything after it must tolerate both topologies.
     */
    private static final String CUTOVER_SPLICE_POINT = "000114_recreate_traces_local_v2_id_at_datetime64.sql";

    /** The temp name the gapless wrap builds the wrapper under before the atomic rotation. */
    private static final String TEMP_WRAPPER = "traces_dist";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private Connection connection;

    {
        Startables.deepStart(zookeeper, clickHouse).join();
    }

    @BeforeAll
    void migrateAcrossTheCutover() throws Exception {
        MigrationUtils.runClickhouseDbMigrationThrough(clickHouse, CUTOVER_SPLICE_POINT);
        connection = clickHouse.createConnection("");

        exchangeTables();
        wrapInDistributed();

        // Resume: every remaining migration now runs against the post-cutover topology.
        MigrationUtils.runClickhouseDbMigration(clickHouse);
    }

    @AfterAll
    void tearDown() throws Exception {
        connection.close();
        clickHouse.stop();
        zookeeper.stop();
    }

    @Test
    @Order(1)
    @DisplayName("the spliced cutover leaves the post-cutover topology")
    void spliceLeavesPostCutoverTopology() throws Exception {
        assertThat(tableExists(TRACES)).as("`%s` must exist as the wrapper", TRACES).isTrue();
        assertThat(tableExists(SHARD)).as("`%s` must exist as the shard", SHARD).isTrue();
        assertThat(tableExists(BACKUP)).as("the parked pre-cutover data `%s` must be retained", BACKUP).isTrue();

        // The shadow name is consumed by the cutover: EXCHANGE moves the old data under it and the follow-up RENAME
        // parks it as the backup. A shadow still present here means the splice did not complete.
        assertThat(tableExists(SHADOW)).as("`%s` is renamed away by the cutover", SHADOW).isFalse();
        assertThat(tableExists(TEMP_WRAPPER)).as("the temp wrapper `%s` must be rotated away", TEMP_WRAPPER).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("the whole changelog applies cleanly on the post-cutover topology")
    void changelogAppliesCleanlyOnPostCutoverTopology() {
        assertThat(MigrationUtils.unrunClickhouseChangeSetIds(clickHouse))
                .as("""
                        every changeset must be applied after resuming across the cutover; an unrun one means a \
                        migration cannot run on the post-cutover topology and a cut-over install would be left behind\
                        """)
                .isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("the Distributed wrapper exposes exactly the shard's columns")
    void wrapperExposesShardColumns() throws Exception {
        TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * Column-list parity read from {@code system.columns} is necessary but not sufficient: it is the wrapper's ability
     * to actually resolve each column that the product depends on. Selecting every column the shard holds through the
     * wrapper proves the parity the previous test measures is the parity that matters.
     */
    @Test
    @Order(4)
    @DisplayName("every shard column is readable through the wrapper")
    void everyShardColumnIsReadableThroughTheWrapper() throws Exception {
        var shardColumns = TableSchema.read(connection, DATABASE_NAME, SHARD).columnNames();

        assertThat(shardColumns).as("the shard must have columns to read").isNotEmpty();
        selectColumns(shardColumns);
    }

    /**
     * The spike's central finding and the playbook's remedy, in one test: post-cutover a shard-only {@code ADD COLUMN}
     * applies without error and is then invisible through the wrapper, and altering the wrapper too — which it accepts as
     * a metadata-only change — makes it readable. This is the exact shape of a migration that forgets the wrapper branch,
     * followed by the fix.
     *
     * <p>Kept as one test rather than an ordered pair on purpose: the "after" half only means anything on the state the
     * "before" half leaves behind, and handing mutated schema between two {@code @Order}ed tests would let a failure in
     * the first leave the shard drifted for everything after it. One test owns the column from {@code ADD} to
     * {@code DROP}.
     */
    @Test
    @Order(10)
    @DisplayName("drift is caught: a shard-only column is unreadable until the wrapper is altered too")
    void shardOnlyColumnIsUnreadableUntilTheWrapperIsAlteredToo() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_shard_only String".formatted(DATABASE_NAME, SHARD));

        assertThatThrownBy(() -> TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("drift_shard_only");

        // Not merely absent from the wrapper's metadata — unresolvable, so any read referencing it fails at runtime.
        assertThatThrownBy(() -> selectColumns(List.of("drift_shard_only")))
                .hasMessageContaining("drift_shard_only");

        // The remedy: the wrapper takes the same ADD COLUMN as metadata only, and the column then reads.
        execute("ALTER TABLE %s.%s ADD COLUMN drift_shard_only String".formatted(DATABASE_NAME, TRACES));

        TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME);
        selectColumns(List.of("drift_shard_only"));

        execute("ALTER TABLE %s.%s DROP COLUMN drift_shard_only".formatted(DATABASE_NAME, TRACES));
        execute("ALTER TABLE %s.%s DROP COLUMN drift_shard_only".formatted(DATABASE_NAME, SHARD));
        TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * A subtler drift that names and types alone cannot see: the same materialized column present on both sides, with
     * the same type, computing something different. The wrapper is created {@code AS} the shard, so the expressions start
     * identical and can only diverge by one side being altered on its own.
     */
    @Test
    @Order(11)
    @DisplayName("drift is caught: a materialized column whose expression differs between shard and wrapper")
    void materializedExpressionDriftIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_expression UInt64 MATERIALIZED length(name)"
                .formatted(DATABASE_NAME, SHARD));
        execute("ALTER TABLE %s.%s ADD COLUMN drift_expression UInt64 MATERIALIZED length(thread_id)"
                .formatted(DATABASE_NAME, TRACES));

        assertThatThrownBy(() -> TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("drift_expression");

        execute("ALTER TABLE %s.%s DROP COLUMN drift_expression".formatted(DATABASE_NAME, TRACES));
        execute("ALTER TABLE %s.%s DROP COLUMN drift_expression".formatted(DATABASE_NAME, SHARD));
        TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * The mirror-image drift: a read-facing change applied to the wrapper alone. The wrapper accepts it, so reads
     * resolve the column and then fail at the shard, which stores nothing under that name.
     */
    @Test
    @Order(12)
    @DisplayName("drift is caught: a column added to the wrapper alone")
    void wrapperOnlyColumnIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_wrapper_only String".formatted(DATABASE_NAME, TRACES));

        assertThatThrownBy(() -> TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("drift_wrapper_only");

        execute("ALTER TABLE %s.%s DROP COLUMN drift_wrapper_only".formatted(DATABASE_NAME, TRACES));
        TracesSchemaParity.assertPostCutoverParity(connection, DATABASE_NAME);
    }

    /** Cutover step 3, exchange block — mirrors 000003_exchange_and_wrap.sql. */
    private void exchangeTables() throws Exception {
        execute("EXCHANGE TABLES %s.%s AND %s.%s ON CLUSTER '{cluster}'"
                .formatted(DATABASE_NAME, TRACES, DATABASE_NAME, SHADOW));
        execute("RENAME TABLE %s.%s TO %s.%s ON CLUSTER '{cluster}'"
                .formatted(DATABASE_NAME, SHADOW, DATABASE_NAME, BACKUP));
    }

    /**
     * Cutover step 3, wrap block — mirrors 000003_exchange_and_wrap.sql. Gapless: the wrapper is built under a temp
     * name first (its {@code traces_local} target need not exist yet, as Distributed resolves it lazily), then a single
     * atomic multi-target RENAME rotates the data to {@code traces_local} and the wrapper into {@code traces}.
     */
    private void wrapInDistributed() throws Exception {
        execute("""
                CREATE TABLE %s.%s ON CLUSTER '{cluster}' AS %s.%s
                ENGINE = Distributed('{cluster}', '%s', '%s', sipHash64(project_id))
                """.formatted(DATABASE_NAME, TEMP_WRAPPER, DATABASE_NAME, TRACES, DATABASE_NAME, SHARD));
        execute("""
                RENAME TABLE
                    %s.%s TO %s.%s,
                    %s.%s TO %s.%s
                    ON CLUSTER '{cluster}'
                """.formatted(DATABASE_NAME, TRACES, DATABASE_NAME, SHARD,
                DATABASE_NAME, TEMP_WRAPPER, DATABASE_NAME, TRACES));
    }

    /**
     * Reads the given columns through the wrapper. {@code LIMIT 0} keeps it a pure name-resolution check — the point is
     * whether the wrapper can resolve every column, not what the (empty) table holds.
     */
    private void selectColumns(List<String> columns) throws Exception {
        var sql = "SELECT %s FROM %s.%s LIMIT 0".formatted(String.join(", ", columns), DATABASE_NAME, TRACES);
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as("LIMIT 0 returns no rows").isFalse();
        }
    }

    private boolean tableExists(String table) throws Exception {
        var sql = "SELECT count() FROM system.tables WHERE database = '%s' AND name = '%s'"
                .formatted(DATABASE_NAME, table);
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1) > 0;
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
