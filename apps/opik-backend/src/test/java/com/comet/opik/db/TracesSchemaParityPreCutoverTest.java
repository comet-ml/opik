package com.comet.opik.db;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-cutover half of the trace DDL topology guard: applies the real changelog the way a fresh install does, then
 * asserts the {@link CutoverSchemaParity} invariant across {@code traces}, the {@code traces_local_v2} shadow, and the
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
class TracesSchemaParityPreCutoverTest {

    private static final CutoverSchemaParity PARITY = CutoverSchemaParity.TRACES;
    private static final CutoverDdlReferenceFixture FIXTURE = CutoverDdlReferenceFixture.TRACES;

    private static final String TRACES = PARITY.getLive();
    private static final String SHADOW = PARITY.getShadow();

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
        try {
            if (connection != null) {
                connection.close();
            }
        } finally {
            clickHouse.stop();
            zookeeper.stop();
        }
    }

    @Test
    @Order(1)
    void freshApplyLeavesPreCutoverTopology() throws Exception {
        assertThat(tableExists(TRACES)).as("`%s` must exist", TRACES).isTrue();
        assertThat(tableExists(SHADOW)).as("the `%s` shadow must exist", SHADOW).isTrue();

        // A fresh install has never run the runbook, so neither post-cutover table may be present. If one is, the
        // post-cutover assertions below would be measuring the wrong topology.
        assertThat(tableExists(PARITY.getShard()))
                .as("`%s` is created by the cutover runbook, never by the changelog", PARITY.getShard())
                .isFalse();
        assertThat(tableExists(PARITY.getBackup()))
                .as("`%s` is created by the cutover runbook, never by the changelog", PARITY.getBackup())
                .isFalse();
    }

    @Test
    @Order(2)
    void tracesShadowAndBackfillAgree() throws Exception {
        PARITY.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * The reference migration's pre-cutover branch, applied to the topology it is written for. Proves the two facts the
     * pattern rests on: {@code liquibase-clickhouse} honours a formatted-SQL {@code sqlCheck} precondition with
     * {@code onFail:MARK_RAN}, so one file can serve both topologies; and the branch that does run reaches both the live
     * table and the shadow, leaving parity intact.
     */
    @Test
    @Order(5)
    void referenceMigrationTakesThePreCutoverBranchAndMarksTheOtherRan() throws Exception {
        MigrationUtils.runClickhouseChangelog(clickHouse, FIXTURE.getChangelog());

        assertThat(FIXTURE.execType(connection, FIXTURE.getPreCutoverChangeSet()))
                .as("on a pre-cutover install the pre-cutover branch must run")
                .isEqualTo(CutoverDdlReferenceFixture.EXECUTED);
        assertThat(FIXTURE.execType(connection, FIXTURE.getPostCutoverChangeSet()))
                .as("""
                        the post-cutover branch must be recorded MARK_RAN, not left unrun: it is marked applied without \
                        executing, so a later startup never retries it against the wrong topology\
                        """)
                .isEqualTo(CutoverDdlReferenceFixture.MARK_RAN);

        // The read-facing field and the storage-only index both reach both tables pre-cutover.
        var traces = TableSchema.read(connection, DATABASE_NAME, TRACES);
        var shadow = TableSchema.read(connection, DATABASE_NAME, SHADOW);
        assertReferenceFieldContract(traces);
        assertReferenceFieldContract(shadow);
        assertReferenceIndexContract(traces);
        assertReferenceIndexContract(shadow);

        PARITY.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * Idempotency: the guarded branches are all {@code IF [NOT] EXISTS} and the skipped one is recorded {@code MARK_RAN},
     * so a second apply — a restarting replica, a re-run after a partial failure — must be a no-op rather than an error.
     */
    @Test
    @Order(6)
    void reApplyingTheReferenceMigrationIsANoOp() throws Exception {
        MigrationUtils.runClickhouseChangelog(clickHouse, FIXTURE.getChangelog());

        assertThat(MigrationUtils.unrunClickhouseChangeSetIds(clickHouse, FIXTURE.getChangelog()))
                .as("both branches stay recorded as applied, so nothing is left to run")
                .isEmpty();

        PARITY.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * The negative control that makes the pattern load-bearing: the same change written the ordinary unguarded way
     * applies without error and leaves the shadow behind, and this gate rejects it. A pull request shaped like this
     * cannot merge.
     */
    @Test
    @Order(7)
    void unguardedMigrationAppliesCleanlyAndIsRejected() throws Exception {
        MigrationUtils.runClickhouseChangelog(clickHouse, FIXTURE.getUnguardedChangelog());

        assertThat(TableSchema.read(connection, DATABASE_NAME, TRACES).columnNames())
                .as("the unguarded ALTER does reach the live table — it fails silently, which is the problem")
                .contains(CutoverDdlReferenceFixture.UNGUARDED_COLUMN);
        assertThat(TableSchema.read(connection, DATABASE_NAME, SHADOW).columnNames())
                .as("...and never reaches the shadow the cutover will promote")
                .doesNotContain(CutoverDdlReferenceFixture.UNGUARDED_COLUMN);

        assertThatThrownBy(() -> PARITY.assertPreCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(CutoverDdlReferenceFixture.UNGUARDED_COLUMN);

        execute("ALTER TABLE %s.%s DROP COLUMN %s"
                .formatted(DATABASE_NAME, TRACES, CutoverDdlReferenceFixture.UNGUARDED_COLUMN));
        PARITY.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * The fixtures do write Liquibase ledger rows, so this pins that they stay additive: the shipped changelog is still
     * fully applied afterwards. Listing its unrun changesets also revalidates every recorded checksum, so a fixture
     * that had disturbed a shipped ledger row would fail here rather than in some unrelated suite later.
     */
    @Test
    @Order(8)
    void applyingTheFixturesLeavesTheShippedChangelogIntact() {
        assertThat(MigrationUtils.unrunClickhouseChangeSetIds(clickHouse))
                .as("the shipped changelog must remain fully applied, with nothing pending or invalidated")
                .isEmpty();
    }

    /**
     * Every way a careless migration leaves the two tables inconsistent, as one table: each row injects the drift, the
     * shared body asserts the guard rejects it naming the given fragments, and the schema is restored either way. One
     * parameterized test rather than seven near-identical ones, so adding a scenario is a row rather than a method.
     *
     * <p>Injection SQL is deliberately raw rather than routed through a migration. These assert what the guard does
     * with a drifted <i>schema</i>, whatever produced it; that a non-conforming <i>migration</i> produces such a schema
     * is what {@link #unguardedMigrationAppliesCleanlyAndIsRejected} covers. The backfill leg is the one scenario left
     * on its own, because it asserts something extra — see {@link #columnMissingFromBackfillListIsCaught}.
     */
    Stream<Arguments> schemaDrift() {
        return Stream.of(
                // Both directions deliberately: a migration writer is likelier to forget the shadow, but a shadow-only
                // change is equally broken.
                Arguments.of("a column added to `traces` alone",
                        SchemaDrift.builder()
                                .inject(addColumn(TRACES, "drift_on_traces"))
                                .restore(dropColumn(TRACES, "drift_on_traces"))
                                .expectedInMessage(List.of("drift_on_traces"))
                                .build()),
                Arguments.of("a column added to the shadow alone",
                        SchemaDrift.builder()
                                .inject(addColumn(SHADOW, "drift_on_shadow"))
                                .restore(dropColumn(SHADOW, "drift_on_shadow"))
                                .expectedInMessage(List.of("drift_on_shadow"))
                                .build()),

                Arguments.of("a skip index added to `traces` but not to the shadow",
                        SchemaDrift.builder()
                                .inject(List
                                        .of("ALTER TABLE %s.%s ADD INDEX idx_drift_name name TYPE set(0) GRANULARITY 1"
                                                .formatted(DATABASE_NAME, TRACES)))
                                .restore(List.of(
                                        "ALTER TABLE %s.%s DROP INDEX idx_drift_name".formatted(DATABASE_NAME, TRACES)))
                                .expectedInMessage(List.of("idx_drift_name"))
                                .build()),

                // Present on both under one name but not the same index. Every name-set comparison passes, and the
                // successor silently keeps a differently-tuned index — granularity alone is the subtlest form.
                Arguments.of("a skip index defined differently on each table",
                        SchemaDrift.builder()
                                .inject(List.of(
                                        "ALTER TABLE %s.%s ADD INDEX idx_drift_def name TYPE set(0) GRANULARITY 1"
                                                .formatted(DATABASE_NAME, TRACES),
                                        "ALTER TABLE %s.%s ADD INDEX idx_drift_def name TYPE set(0) GRANULARITY 4"
                                                .formatted(DATABASE_NAME, SHADOW)))
                                .restore(List.of(
                                        "ALTER TABLE %s.%s DROP INDEX idx_drift_def".formatted(DATABASE_NAME, TRACES),
                                        "ALTER TABLE %s.%s DROP INDEX idx_drift_def"
                                                .formatted(DATABASE_NAME, SHADOW)))
                                .expectedInMessage(List.of("defined identically", "idx_drift_def"))
                                .build()),

                // Projections are compared by definition, so same name + different query is drift: the successor would
                // keep the name and lose the meaning. Both trace tables are ReplacingMergeTree, which refuses
                // ADD PROJECTION while deduplicate_merge_projection_mode is at its default `throw` (code 344) — the
                // setting is relaxed only to make this leg reachable, and reset afterwards. That a real projection
                // would need the same decision is itself worth knowing.
                Arguments.of("same-named projections with different queries",
                        SchemaDrift.builder()
                                .inject(List.of(
                                        "ALTER TABLE %s.%s MODIFY SETTING deduplicate_merge_projection_mode = 'rebuild'"
                                                .formatted(DATABASE_NAME, TRACES),
                                        "ALTER TABLE %s.%s MODIFY SETTING deduplicate_merge_projection_mode = 'rebuild'"
                                                .formatted(DATABASE_NAME, SHADOW),
                                        "ALTER TABLE %s.%s ADD PROJECTION proj_drift (SELECT id, name ORDER BY name)"
                                                .formatted(DATABASE_NAME, TRACES),
                                        "ALTER TABLE %s.%s ADD PROJECTION proj_drift (SELECT id, thread_id ORDER BY thread_id)"
                                                .formatted(DATABASE_NAME, SHADOW)))
                                .restore(List.of("ALTER TABLE %s.%s DROP PROJECTION proj_drift"
                                        .formatted(DATABASE_NAME, TRACES),
                                        "ALTER TABLE %s.%s DROP PROJECTION proj_drift"
                                                .formatted(DATABASE_NAME, SHADOW),
                                        "ALTER TABLE %s.%s RESET SETTING deduplicate_merge_projection_mode"
                                                .formatted(DATABASE_NAME, TRACES),
                                        "ALTER TABLE %s.%s RESET SETTING deduplicate_merge_projection_mode"
                                                .formatted(DATABASE_NAME, SHADOW)))
                                .expectedInMessage(List.of("proj_drift"))
                                .build()),

                // Both tables still list `name`, so every column-name comparison passes while the cutover would convert
                // String to LowCardinality(String) on the way across. This is the leg the documented allowlist
                // deliberately does not cover.
                Arguments.of("a shared column whose type changed on one table only",
                        SchemaDrift.builder()
                                .inject(List.of("ALTER TABLE %s.%s MODIFY COLUMN name LowCardinality(String)"
                                        .formatted(DATABASE_NAME, SHADOW)))
                                .restore(List.of(
                                        "ALTER TABLE %s.%s MODIFY COLUMN name String".formatted(DATABASE_NAME, SHADOW)))
                                .expectedInMessage(List.of("name", "LowCardinality"))
                                .build()),

                // The allowlist is pinned on both sides, so an allowlisted column that leaves its documented type fails
                // even though it still "differs" from its counterpart. Making the shadow's ttft Nullable again
                // exercises both halves: the entry no longer describes reality, and the difference it excused has gone.
                Arguments.of("an allowlisted column that left its documented type on the shadow",
                        SchemaDrift.builder()
                                .inject(List.of("ALTER TABLE %s.%s MODIFY COLUMN ttft Nullable(Float64)"
                                        .formatted(DATABASE_NAME, SHADOW)))
                                .restore(List.of("ALTER TABLE %s.%s MODIFY COLUMN ttft Float64 DEFAULT toFloat64('nan')"
                                        .formatted(DATABASE_NAME, SHADOW)))
                                .expectedInMessage(List.of("allowlisted column", "ttft"))
                                .build()),
                Arguments.of("an allowlisted column that changed on `traces`",
                        SchemaDrift.builder()
                                .inject(List.of("ALTER TABLE %s.%s MODIFY COLUMN start_time DateTime64(3, 'UTC')"
                                        .formatted(DATABASE_NAME, TRACES)))
                                .restore(List.of(
                                        "ALTER TABLE %s.%s MODIFY COLUMN start_time DateTime64(9, 'UTC') DEFAULT now64(9)"
                                                .formatted(DATABASE_NAME, TRACES)))
                                .expectedInMessage(List.of("allowlisted column", "start_time"))
                                .build()),

                // The one column the type comparison structurally cannot reach — it exists on the shadow with no
                // counterpart on `traces` — and the one where the default is the whole contract. The backfill omits
                // is_deleted so it takes its default; flip that to 1 and every copied row materialises as a
                // ReplacingMergeTree tombstone, which is silent, total data loss at swap time.
                Arguments.of("the shadow's is_deleted default flipped to 1",
                        SchemaDrift.builder()
                                .inject(List.of("ALTER TABLE %s.%s MODIFY COLUMN is_deleted UInt8 DEFAULT 1"
                                        .formatted(DATABASE_NAME, SHADOW)))
                                .restore(List.of("ALTER TABLE %s.%s MODIFY COLUMN is_deleted UInt8 DEFAULT 0"
                                        .formatted(DATABASE_NAME, SHADOW)))
                                .expectedInMessage(List.of("is_deleted"))
                                .build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaDrift")
    @Order(10)
    void driftIsCaught(String description, SchemaDrift scenario) throws Exception {
        assertDriftIsCaught(scenario);
    }

    private List<String> addColumn(String table, String column) {
        return List.of("ALTER TABLE %s.%s ADD COLUMN %s String".formatted(DATABASE_NAME, table, column));
    }

    private List<String> dropColumn(String table, String column) {
        return List.of("ALTER TABLE %s.%s DROP COLUMN %s".formatted(DATABASE_NAME, table, column));
    }

    /**
     * The third leg, and the one no table-to-table comparison can catch: a preserved column added correctly to both
     * tables but never added to the cutover backfill's column list is copied as its default, so the cutover silently
     * loses the data.
     */
    @Test
    @Order(12)
    void columnMissingFromBackfillListIsCaught() throws Exception {
        execute("ALTER TABLE %s.%s ADD COLUMN drift_unbackfilled String".formatted(DATABASE_NAME, TRACES));
        execute("ALTER TABLE %s.%s ADD COLUMN drift_unbackfilled String".formatted(DATABASE_NAME, SHADOW));

        assertThat(PARITY.backfillColumnList())
                .as("the injected column is deliberately absent from the shipped backfill list")
                .doesNotContain("drift_unbackfilled");

        assertThatThrownBy(() -> PARITY.assertPreCutoverParity(connection, DATABASE_NAME))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("backfill")
                .hasMessageContaining("drift_unbackfilled");

        execute("ALTER TABLE %s.%s DROP COLUMN drift_unbackfilled".formatted(DATABASE_NAME, TRACES));
        execute("ALTER TABLE %s.%s DROP COLUMN drift_unbackfilled".formatted(DATABASE_NAME, SHADOW));
        PARITY.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * The reference field must arrive as the exact column the migration declares. Presence alone would be satisfied by
     * a {@code UInt32}, or by an {@code ALIAS} where a {@code MATERIALIZED} was intended — both of which change the
     * read contract while passing a name check.
     */
    private void assertReferenceFieldContract(TableSchema schema) {
        var column = schema.columnsByName().get(CutoverDdlReferenceFixture.DERIVED_COLUMN);
        assertThat(column)
                .as("`%s` must carry the reference field on `%s`", CutoverDdlReferenceFixture.DERIVED_COLUMN,
                        schema.table())
                .isNotNull();
        assertThat(column.type()).as("reference field type on `%s`", schema.table())
                .isEqualTo(CutoverDdlReferenceFixture.DERIVED_COLUMN_TYPE);
        assertThat(column.defaultKind()).as("reference field default kind on `%s`", schema.table())
                .isEqualTo(CutoverDdlReferenceFixture.DERIVED_COLUMN_DEFAULT_KIND);
        assertThat(column.defaultExpression()).as("reference field expression on `%s`", schema.table())
                .isEqualTo(CutoverDdlReferenceFixture.DERIVED_COLUMN_EXPRESSION);
    }

    /** Likewise the index: the same name with a different type, expression or granularity is a different index. */
    private void assertReferenceIndexContract(TableSchema schema) {
        assertThat(schema.skipIndicesByName().get(CutoverDdlReferenceFixture.STORAGE_INDEX))
                .as("`%s` must carry the reference index, defined as declared", schema.table())
                .isEqualTo(CutoverDdlReferenceFixture.EXPECTED_STORAGE_INDEX);
    }

    /**
     * Injects the drift, asserts the guard rejects it naming the scenario's fragments, and restores the schema
     * <b>whether or not the assertion held</b>.
     * <p>
     * The finally is the point. These negative tests share one container with every later {@code @Order}ed test, so a
     * failing assertion that left its drift in place would cascade: the next tests fail for a reason unrelated to what
     * they assert, and the real failure is buried. Restoring first, then re-asserting parity, also proves the cleanup
     * actually worked rather than assuming it.
     */
    private void assertDriftIsCaught(SchemaDrift scenario) throws Exception {
        // Injection sits inside the protected region: a multi-statement injection that fails partway used to skip
        // cleanup entirely, leaving the statements that did apply behind — the exact contamination this helper exists
        // to prevent, reintroduced by where the loop sat.
        Throwable primary = null;
        try {
            for (var sql : scenario.inject()) {
                execute(sql);
            }
            var thrown = assertThatThrownBy(
                    () -> PARITY.assertPreCutoverParity(connection, DATABASE_NAME))
                    .isInstanceOf(AssertionError.class);
            for (var expected : scenario.expectedInMessage()) {
                thrown.hasMessageContaining(expected);
            }
        } catch (Throwable t) {
            primary = t;
        }

        try {
            restoreAll(scenario.restore());
        } catch (Exception e) {
            // Never let a cleanup failure replace the real one: the assertion result is what the reader needs.
            if (primary == null) {
                primary = e;
            } else {
                primary.addSuppressed(e);
            }
        }

        if (primary instanceof Error error) {
            throw error;
        }
        if (primary != null) {
            throw (Exception) primary;
        }

        PARITY.assertPreCutoverParity(connection, DATABASE_NAME);
    }

    /**
     * Runs every restore even if an earlier one fails, so a partially applied injection is undone as far as it can be —
     * a statement that never applied simply has nothing to undo. Failures are collected and rethrown rather than
     * swallowed: a cleanup that quietly did not happen is how the next test fails for the wrong reason.
     */
    private void restoreAll(List<String> restore) throws Exception {
        Exception failure = null;
        for (var sql : restore) {
            try {
                execute(sql);
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
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

    /**
     * One row of the drift table: the statements that introduce the inconsistency, the statements that undo it, and the
     * fragments the guard's rejection must name so a case cannot pass on an unrelated failure.
     */
    @Builder(toBuilder = true)
    private record SchemaDrift(List<String> inject, List<String> restore, List<String> expectedInMessage) {
    }
}
