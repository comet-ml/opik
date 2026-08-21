package com.comet.opik.db;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace physical-layer schema invariant, in one place, for both topologies the mixed fleet runs.
 *
 * <p><b>Why this exists.</b> The cutover to the partitioned, sharding-ready trace table is produced by the operator
 * runbook ({@code data-migrations/traces-local-v2-cutover}), not by Liquibase, so the changelog and the runtime
 * topology diverge the moment an install cuts over — and they stay diverged for as long as the fleet is mixed (SaaS cut
 * over; self-hosted on their own cadence; fresh installs still pre-cutover). A single {@code traces} schema change
 * must therefore be correct against <b>two</b> different physical layouts, and the failure mode is silent: a shard-only
 * {@code ADD COLUMN} applies without error post-cutover but is <i>not readable</i> through the {@code Distributed}
 * wrapper, and a migration that alters {@code traces} but forgets the shadow leaves the next cutover copying a table
 * that no longer matches. Both are invisible until a customer hits them.
 *
 * <p><b>The invariant.</b> Every trace physical table stays schema-consistent for any change:
 * <ul>
 *   <li><b>Pre-cutover</b> — {@code traces} (live) and {@code traces_local_v2} (the shadow the cutover will promote)
 *   carry the same read-facing columns and the same storage-only attributes, and the cutover backfill's explicit
 *   column list carries every column that must survive the copy. The shadow's documented, deliberate extras are
 *   enumerated in {@link #SHADOW_ONLY_COLUMNS} / {@link #SHADOW_ONLY_SKIP_INDICES} and nothing else may differ.</li>
 *   <li><b>Post-cutover</b> — the {@code Distributed} {@code traces} wrapper exposes exactly the columns its
 *   {@code traces_local} shard holds, so every read-facing change reached both.</li>
 * </ul>
 *
 * <p><b>Where a change lands.</b> Anything that changes the read-facing column list (columns, including
 * materialized/alias) must be applied to the shard <b>and</b> the {@code Distributed} wrapper; everything storage-only
 * (skip indexes, codecs, TTL, projections) is shard-only, because the wrapper stores nothing. Pre-cutover the same
 * change applies to {@code traces} and the shadow, and a <i>preserved</i> column must additionally be added to the
 * backfill column list or the cutover drops it.
 *
 * <p><b>Baseline differences are enumerated, not tolerated in bulk.</b> The shadow deliberately differs from
 * {@code traces} in ways a schema-change guard must not flag — narrower timestamp precision, sentinel-based
 * non-nullable columns instead of {@code Nullable}, explicit codecs throughout, a weekly partition key, and a
 * different column order. So this guard compares the aspects a <i>schema change</i> moves — the column name set, the
 * insertable column set, skip indices, projections, and the sorting/primary keys — and leaves the per-column type,
 * default and codec differences to the suites that own them ({@code TracesLocalV2TableTest} round-trips the types and
 * sentinels, {@code TracesLocalV2BenchmarkTest} pins the codecs, {@code TracesLocalV2PartitioningTest} pins the
 * partition expression). Comparing types here would re-assert those baseline differences as failures on every run.
 */
@UtilityClass
class TracesSchemaParity {

    /** The live read/insert-facing table name in both topologies: a {@code MergeTree} before the cutover, a {@code Distributed} wrapper after. */
    static final String TRACES = "traces";

    /** The empty successor the cutover promotes; exists only pre-cutover (the cutover renames it away). */
    static final String SHADOW = "traces_local_v2";

    /** The shard the {@code Distributed} wrapper fronts; exists only post-cutover. */
    static final String SHARD = "traces_local";

    /** The parked pre-cutover data, retained through the soak; exists only post-cutover. */
    static final String BACKUP = "traces_pre_cutover_backup";

    /**
     * {@code is_deleted} is the successor's {@code ReplacingMergeTree} delete meta-column (000101). It is engine
     * bookkeeping rather than trace data, which is why it has no counterpart on {@code traces} and why the cutover
     * backfill deliberately omits it so it defaults to 0.
     */
    static final Set<String> SHADOW_ONLY_COLUMNS = Set.of("is_deleted");

    /**
     * Skip indices the successor adds because its layout needs them and {@code traces} cannot use them:
     * {@code idx_traces_id_at} indexes the partition-input column {@code id_at}, which {@code traces} does not
     * partition on, and {@code idx_traces_id_minmax} prunes the id-range predicates the retention path issues against
     * the weekly partitions. Both are storage-only, so post-cutover they live on the shard alone.
     */
    static final Set<String> SHADOW_ONLY_SKIP_INDICES = Set.of("idx_traces_id_at", "idx_traces_id_minmax");

    /**
     * The shipped cutover backfill, read rather than restated: this guard's whole point is that the backfill column
     * list cannot drift from the tables, so asserting against a copy of it here would assert nothing. The path is
     * relative to the Maven module directory ({@code apps/opik-backend}), which is the working directory both locally
     * and in CI.
     */
    private static final Path BACKFILL_SQL = Path
            .of("data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000001_backfill_traces_local_v2.sql");

    private static final String COLUMN_NAME_PATTERN = "[a-z_][a-z0-9_]*";

    /**
     * Asserts the pre-cutover invariant: the live table, the shadow it will be replaced by, and the backfill column
     * list that moves the data between them all agree.
     */
    static void assertPreCutoverParity(Connection connection, String database) throws SQLException, IOException {
        var traces = TableSchema.read(connection, database, TRACES);
        var shadow = TableSchema.read(connection, database, SHADOW);

        assertThat(traces.isDistributed())
                .as("pre-cutover `%s` must still be the local MergeTree, not a Distributed wrapper", TRACES)
                .isFalse();

        assertThat(shadow.columnNames())
                .as("""
                        read-facing column parity: every column on `%s` must exist on the `%s` shadow (and vice versa, \
                        beyond the documented engine meta-columns %s). A change that adds or drops a column on one \
                        without the other leaves the cutover promoting a table that does not match the live one.\
                        """, TRACES, SHADOW, SHADOW_ONLY_COLUMNS)
                .containsExactlyInAnyOrderElementsOf(union(traces.columnNames(), SHADOW_ONLY_COLUMNS));

        var backfillColumns = backfillColumnList();

        assertThat(traces.storedColumnNames())
                .as("""
                        cutover backfill parity: the backfill's explicit INSERT column list must name exactly the \
                        insertable columns of `%s`. A new preserved column added to the tables but not to %s is \
                        silently dropped at the cutover; a column removed from the tables but left in the list fails \
                        the backfill.\
                        """, TRACES, BACKFILL_SQL.getFileName())
                .containsExactlyInAnyOrderElementsOf(backfillColumns);

        assertThat(shadow.storedColumnNames())
                .as("""
                        the `%s` shadow must accept exactly the backfilled columns plus its engine meta-columns %s \
                        (which the backfill deliberately omits so they take their defaults)\
                        """, SHADOW, SHADOW_ONLY_COLUMNS)
                .containsExactlyInAnyOrderElementsOf(union(backfillColumns, SHADOW_ONLY_COLUMNS));

        assertThat(shadow.skipIndexNames())
                .as("""
                        skip-index parity: an index added to `%s` must also be added to the `%s` shadow, or the \
                        successor silently loses the pruning the read path was tuned for. The shadow's own extras are \
                        %s.\
                        """, TRACES, SHADOW, SHADOW_ONLY_SKIP_INDICES)
                .containsExactlyInAnyOrderElementsOf(union(traces.skipIndexNames(), SHADOW_ONLY_SKIP_INDICES));

        var shadowIndices = shadow.skipIndicesByName();
        traces.skipIndicesByName().forEach((name, index) -> assertThat(shadowIndices.get(name))
                .as("skip index `%s` must be defined identically on `%s` and the `%s` shadow", name, TRACES, SHADOW)
                .isEqualTo(index));

        assertThat(shadow.projectionNames())
                .as("projection parity: a projection is storage-only, but pre-cutover both tables must carry it so "
                        + "the successor keeps it after the swap")
                .containsExactlyInAnyOrderElementsOf(traces.projectionNames());

        assertThat(shadow.sortingKey())
                .as("the successor's sorting key is the dedup key the backfill relies on; it must match `%s`", TRACES)
                .isEqualTo(traces.sortingKey());
        assertThat(shadow.primaryKey())
                .as("the successor's primary key must match `%s`", TRACES)
                .isEqualTo(traces.primaryKey());

        // The partition keys differ by design (that is the point of the successor); the expression itself is pinned by
        // TracesLocalV2PartitioningTest, so this only holds the shapes apart.
        assertThat(traces.partitionKey()).as("`%s` is unpartitioned pre-cutover", TRACES).isEmpty();
        assertThat(shadow.partitionKey()).as("the `%s` successor is weekly-partitioned", SHADOW).isNotEmpty();
    }

    /**
     * Asserts the post-cutover invariant: the {@code Distributed} wrapper exposes exactly the shard's columns.
     *
     * <p>This is the assertion that catches the silent failure the spike measured — a shard-only {@code ADD COLUMN}
     * succeeds, but the column is unreadable through the wrapper (ClickHouse code 47), so the feature that added it is
     * broken on every cut-over install while CI stays green.
     */
    static void assertPostCutoverParity(Connection connection, String database) throws SQLException {
        var wrapper = TableSchema.read(connection, database, TRACES);
        var shard = TableSchema.read(connection, database, SHARD);

        assertThat(wrapper.isDistributed())
                .as("post-cutover `%s` must be the Distributed wrapper; found engine `%s`", TRACES, wrapper.engine())
                .isTrue();
        assertThat(shard.isDistributed())
                .as("`%s` must be the local MergeTree shard; found engine `%s`", SHARD, shard.engine())
                .isFalse();

        assertThat(wrapper.columnNames())
                .as("""
                        wrapper column parity: the Distributed `%s` must expose exactly the columns `%s` holds, in the \
                        same order. A read-facing change applied only to the shard leaves the wrapper unable to see it \
                        (code 47); one applied only to the wrapper leaves reads referencing a column no shard stores.\
                        """, TRACES, SHARD)
                .isEqualTo(shard.columnNames());

        var shardColumns = shard.columnsByName();
        wrapper.columnsByName().forEach((name, column) -> {
            var shardColumn = shardColumns.get(name);
            assertThat(column.type())
                    .as("column `%s` must have the same type on the Distributed `%s` and on `%s`", name, TRACES, SHARD)
                    .isEqualTo(shardColumn.type());
            assertThat(column.defaultKind())
                    .as("column `%s` must have the same default kind on the Distributed `%s` and on `%s`", name,
                            TRACES, SHARD)
                    .isEqualTo(shardColumn.defaultKind());
        });
    }

    /**
     * Every column the shipped cutover backfill names in its {@code INSERT INTO ... (...)} list.
     *
     * <p>Line comments are stripped before the statement is located because the file's header prose mentions
     * {@code INSERT} and carries parentheses; the reference SQL contains no string literal holding {@code --}, so the
     * naive strip is safe here. Each parsed entry is checked to be a bare column name, so a future edit that puts an
     * expression or a nested parenthesis in the list fails loudly instead of being silently mis-parsed.
     */
    static Set<String> backfillColumnList() throws IOException {
        assertThat(BACKFILL_SQL)
                .as("the shipped cutover backfill must be readable at %s (relative to apps/opik-backend); if it moved, "
                        + "update this guard rather than dropping the assertion", BACKFILL_SQL)
                .isRegularFile();

        var sql = stripLineComments(Files.readString(BACKFILL_SQL));

        int insertAt = sql.indexOf("INSERT INTO");
        assertThat(insertAt).as("no INSERT INTO statement found in %s", BACKFILL_SQL).isNotNegative();

        int open = sql.indexOf('(', insertAt);
        int close = sql.indexOf(')', open);
        assertThat(open).as("no column list found after INSERT INTO in %s", BACKFILL_SQL).isNotNegative();
        assertThat(close).as("unterminated column list in %s", BACKFILL_SQL).isNotNegative();

        var columns = Arrays.stream(sql.substring(open + 1, close).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(columns)
                .as("the backfill column list must hold bare column names; an expression here means this parse is "
                        + "reading the wrong parentheses")
                .isNotEmpty()
                .allMatch(column -> column.matches(COLUMN_NAME_PATTERN));

        return columns;
    }

    private static String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }

    private static Set<String> union(Iterable<String> first, Set<String> second) {
        var union = new LinkedHashSet<String>();
        first.forEach(union::add);
        union.addAll(second);
        return union;
    }
}
