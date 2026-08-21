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

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.db.TracesSchemaParity.SHADOW;
import static com.comet.opik.db.TracesSchemaParity.TRACES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-cutover half of the trace DDL topology guard: applies the real changelog the way a fresh install does, then
 * asserts the {@link TracesSchemaParity} invariant across {@code traces}, the {@code traces_local_v2} shadow, and the
 * shipped cutover backfill's column list.
 *
 * <p><b>This is the branch that catches a forgotten shadow alter.</b> A migration that alters {@code traces} alone
 * applies cleanly and every existing test stays green — the shadow is empty, so nothing reads it until the cutover
 * copies into it, at which point the mismatch surfaces as an operator-facing failure (or, worse, as a column silently
 * dropped from the copy). The three parity legs here turn that into a merge-blocking CI failure instead.
 *
 * <p><b>The negative tests are the point.</b> A parity assertion that never fires is indistinguishable from one that
 * cannot fire, so each leg is followed by a test that injects the exact drift a careless migration would produce and
 * asserts this guard rejects it. They run after the positive assertions ({@code @Order}) because they deliberately
 * leave and then remove schema drift on a live table.
 *
 * <p><b>Dedicated, non-reused containers</b> because those negative tests mutate {@code traces}: a container reused
 * across suites (CI sets {@code TESTCONTAINERS_REUSE_ENABLE}) would hand the drift to whatever ran next. Mirrors
 * {@link ChangelogRebaselineTest}, which mutates the changelog ledger for the same reason.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Traces Schema Parity - Pre-cutover")
class TracesSchemaParityPreCutoverTest {

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private Connection connection;

    {
        Startables.deepStart(zookeeper, clickHouse).join();
    }

    @BeforeAll
    void migrate() throws Exception {
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        connection = clickHouse.createConnection("");
    }

    @AfterAll
    void tearDown() throws Exception {
        connection.close();
        clickHouse.stop();
        zookeeper.stop();
    }

    @Test
    @Order(1)
    @DisplayName("a fresh changelog apply leaves the pre-cutover topology")
    void freshApplyLeavesPreCutoverTopology() throws Exception {
        assertThat(tableExists(TRACES)).as("`%s` must exist", TRACES).isTrue();
        assertThat(tableExists(SHADOW)).as("the `%s` shadow must exist", SHADOW).isTrue();

        // A fresh install has never run the runbook, so neither post-cutover table may be present. If one is, the
        // post-cutover assertions below would be measuring the wrong topology.
        assertThat(tableExists(TracesSchemaParity.SHARD))
                .as("`%s` is created by the cutover runbook, never by the changelog", TracesSchemaParity.SHARD)
                .isFalse();
        assertThat(tableExists(TracesSchemaParity.BACKUP))
                .as("`%s` is created by the cutover runbook, never by the changelog", TracesSchemaParity.BACKUP)
                .isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("traces, the shadow, and the cutover backfill column list all agree")
    void tracesShadowAndBackfillAgree() throws Exception {
        TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    @Test
    @Order(10)
    @DisplayName("drift is caught: a column added to traces but not to the shadow")
    void columnAddedToTracesAloneIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_on_traces String".formatted(DATABASE_NAME, TRACES));

        assertThatThrownBy(() -> TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("drift_on_traces");

        execute("ALTER TABLE %s.%s DROP COLUMN drift_on_traces".formatted(DATABASE_NAME, TRACES));
        TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    @Test
    @Order(11)
    @DisplayName("drift is caught: a column added to the shadow but not to traces")
    void columnAddedToShadowAloneIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_on_shadow String".formatted(DATABASE_NAME, SHADOW));

        assertThatThrownBy(() -> TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("drift_on_shadow");

        execute("ALTER TABLE %s.%s DROP COLUMN drift_on_shadow".formatted(DATABASE_NAME, SHADOW));
        TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * The third leg, and the one no table-to-table comparison can catch: a preserved column added correctly to both
     * tables but never added to the cutover backfill's column list is copied as its default, so the cutover silently
     * loses the data.
     */
    @Test
    @Order(12)
    @DisplayName("drift is caught: a preserved column added to both tables but not to the backfill column list")
    void columnMissingFromBackfillListIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_unbackfilled String".formatted(DATABASE_NAME, TRACES));
        execute("ALTER TABLE %s.%s ADD COLUMN drift_unbackfilled String".formatted(DATABASE_NAME, SHADOW));

        assertThat(TracesSchemaParity.backfillColumnList())
                .as("the injected column is deliberately absent from the shipped backfill list")
                .doesNotContain("drift_unbackfilled");

        assertThatThrownBy(() -> TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("backfill")
                .hasMessageContaining("drift_unbackfilled");

        execute("ALTER TABLE %s.%s DROP COLUMN drift_unbackfilled".formatted(DATABASE_NAME, TRACES));
        execute("ALTER TABLE %s.%s DROP COLUMN drift_unbackfilled".formatted(DATABASE_NAME, SHADOW));
        TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    @Test
    @Order(13)
    @DisplayName("drift is caught: a skip index added to traces but not to the shadow")
    void skipIndexAddedToTracesAloneIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD INDEX idx_drift_name name TYPE set(0) GRANULARITY 1"
                .formatted(DATABASE_NAME, TRACES));

        assertThatThrownBy(() -> TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("idx_drift_name");

        execute("ALTER TABLE %s.%s DROP INDEX idx_drift_name".formatted(DATABASE_NAME, TRACES));
        TracesSchemaParity.assertPreCutoverParity(connection, DATABASE_NAME);
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
