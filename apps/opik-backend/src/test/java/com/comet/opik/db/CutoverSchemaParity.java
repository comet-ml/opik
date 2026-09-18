package com.comet.opik.db;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.SetUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The physical-layer schema invariant of one cutover table family, for both topologies the mixed fleet runs.
 *
 * <p><b>Why this exists.</b> The cutover to a partitioned, sharding-ready successor is produced by the operator runbook
 * ({@code data-migrations/<family>-local-v2-cutover}), not by Liquibase, so the changelog and the runtime topology
 * diverge the moment an install cuts over — and stay diverged for as long as the fleet is mixed, since installs cut over
 * on their own cadence and fresh ones still start pre-cutover. A single schema change must therefore be correct against
 * <b>two</b> different physical layouts, and both failure modes are silent: a shard-only {@code ADD COLUMN} applies
 * without error post-cutover but is <i>not readable</i> through the {@code Distributed} wrapper, and a migration that
 * alters the live table but forgets the shadow leaves the next cutover copying a table that no longer matches.
 *
 * <p><b>The invariant.</b> Every physical table of the family stays schema-consistent for any change:
 * <ul>
 *   <li><b>Pre-cutover</b> — the live table and the shadow the cutover will promote carry the same read-facing columns
 *   and the same storage-only attributes, and the cutover backfill's explicit column list carries every column that
 *   must survive the copy. Each family's deliberate extras are enumerated in {@link #shadowOnlyColumns},
 *   {@link #shadowOnlySkipIndices} and {@link #liveOnlySkipIndices}; nothing else may differ.</li>
 *   <li><b>Post-cutover</b> — the {@code Distributed} wrapper exposes exactly the columns its shard holds, so every
 *   read-facing change reached both.</li>
 * </ul>
 *
 * <p><b>One mechanism, one constant per family</b>, as {@code MutationSql} is for the routing guards. Every assertion
 * below is identical for traces and spans; only the table names and each successor's enumerated baseline differences
 * vary. A per-family copy would be a second place for a comparison to be dropped from — and a comparison that stops
 * running does not fail, it silently stops guarding.
 *
 * <p><b>Baseline differences are enumerated, not tolerated in bulk.</b> Each successor deliberately differs from its
 * live table in ways a schema-change guard must not flag — narrower timestamp precision, sentinel-based non-nullable
 * columns instead of {@code Nullable}, explicit codecs throughout, a weekly partition key, a different column order. So
 * this guard compares what a <i>schema change</i> moves — the column name set, the insertable column set, skip indices,
 * projections and the sorting/primary keys — and leaves the per-column codec differences to the suites that own them
 * ({@code TracesLocalV2TableTest} / {@code SpansLocalV2TableTest} round-trip the types and sentinels, the benchmark
 * suites pin the codecs, the partitioning suites pin the partition expression). Comparing codecs here would re-assert
 * those baseline differences as failures on every run.
 *
 * <p><b>What parity covers, and what it does not.</b> Column <b>names</b> and <b>types</b>, and the <b>select and
 * expression definitions</b> built on them — the backfill's {@code INSERT}/{@code SELECT} column mapping, projection
 * queries, and {@code DEFAULT}/{@code MATERIALIZED} expressions where a baseline permits comparing them. It is
 * deliberately <b>not</b> about the data: no row counts, checksums or value comparisons live here, because the
 * cutover's data fidelity belongs to the {@code *LocalV2CutoverTest} suites. Nor does it cover data <i>lifecycle</i> —
 * table TTL and storage policy are neither names, types nor selects, and the changelog sets neither on these tables.
 *
 * <p><b>Why DEFAULT/MATERIALIZED expressions are compared post-cutover but not pre-cutover.</b> The asymmetry is not an
 * oversight. Pre-cutover the two tables differ in expression by design and in most columns: {@code end_time} defaults to
 * an epoch sentinel on the shadow and is {@code Nullable} with no default on the live table; {@code ttft} likewise uses
 * a {@code NaN} sentinel; {@code duration} is materialized from a sentinel comparison rather than a null check; several
 * columns gained an explicit {@code ''} / {@code []} default only on the successor. Requiring equality there would need
 * an allowlist covering most of the table — exactly the bulk tolerance this guard avoids — and those semantics are
 * already round-tripped by the {@code *LocalV2TableTest} suites. Post-cutover there is no such baseline: the
 * {@code Distributed} wrapper is created {@code AS} the shard, so any expression divergence is drift, and
 * {@link #assertPostCutoverParity} compares expressions strictly.
 */
@Getter
@RequiredArgsConstructor
enum CutoverSchemaParity {

    /**
     * The trace family: six of thirty-one shared columns carry a documented type difference, the shadow adds the
     * {@code is_deleted} meta-column and two id-oriented skip indices, and the cutover leaves the sorting key alone.
     */
    TRACES("traces", "traces_local_v2", "traces_local", "traces_pre_cutover_backup",
            Map.of("is_deleted", ShadowOnlyColumn.builder()
                    .type("UInt8")
                    .defaultKind("DEFAULT")
                    .defaultExpression("0")
                    .reason("""
                            the ReplacingMergeTree delete meta-column (000101); the cutover backfill omits it so every \
                            copied row defaults to alive""")
                    .build()),
            Set.of("idx_traces_id_at", "idx_traces_id_minmax"),
            Map.of(),
            Map.ofEntries(
                    Map.entry("start_time", BaselineTypeDifference.builder()
                            .liveType("DateTime64(9, 'UTC')")
                            .shadowType("DateTime64(6, 'UTC')")
                            .reason("nanosecond -> microsecond precision; nothing ingested needs finer (000101)")
                            .build()),
                    Map.entry("created_at", BaselineTypeDifference.builder()
                            .liveType("DateTime64(9, 'UTC')")
                            .shadowType("DateTime64(6, 'UTC')")
                            .reason("nanosecond -> microsecond precision; nothing ingested needs finer (000101)")
                            .build()),
                    Map.entry("end_time", BaselineTypeDifference.builder()
                            .liveType("Nullable(DateTime64(9, 'UTC'))")
                            .shadowType("DateTime64(6, 'UTC')")
                            .reason("""
                                    Nullable -> non-nullable with an epoch sentinel, dropping the null-mask overhead \
                                    (000101)""")
                            .build()),
                    Map.entry("ttft", BaselineTypeDifference.builder()
                            .liveType("Nullable(Float64)")
                            .shadowType("Float64")
                            .reason("Nullable -> non-nullable with a NaN sentinel (000101)")
                            .build()),
                    Map.entry("duration", BaselineTypeDifference.builder()
                            .liveType("Nullable(Float64)")
                            .shadowType("Float64")
                            .reason("""
                                    Nullable -> non-nullable, materialized from the sentinels rather than a null check \
                                    (000101)""")
                            .build()),
                    Map.entry("id_at", BaselineTypeDifference.builder()
                            .liveType("DateTime('UTC')")
                            .shadowType("DateTime64(0, 'UTC')")
                            .reason("""
                                    DateTime -> DateTime64(0), honest past 2106 so a far-future UUIDv7 partitions \
                                    correctly (000114)""")
                            .build())),
            null,
            Path.of("data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000001_backfill_traces_local_v2.sql"),
            null),

    /**
     * The span family: eleven of thirty-eight shared columns carry a documented type difference — the trace six plus
     * the five conversions the wider span row made worth taking — and, unlike traces, the cutover also <b>changes the
     * sorting key</b> and <b>renames an index</b>. Both are pinned rather than tolerated; see
     * {@link #baselineKeyDifference} and {@link #liveOnlySkipIndices}.
     */
    SPANS("spans", "spans_local_v2", "spans_local", "spans_pre_cutover_backup",
            Map.of("is_deleted", ShadowOnlyColumn.builder()
                    .type("UInt8")
                    .defaultKind("DEFAULT")
                    .defaultExpression("0")
                    .reason("""
                            the ReplacingMergeTree delete meta-column (000115); the cutover backfill omits it so every \
                            copied row defaults to alive""")
                    .build()),
            Set.of("idx_spans_id_at", "idx_spans_id_minmax", "idx_spans_id_bf", "idx_spans_parent_span_id_bf"),
            Map.of("idx_spans_id", """
                    superseded on the successor by the idx_spans_id_minmax / idx_spans_id_bf pair (000115): the minmax \
                    half is the same index under the successor's naming, and the bloom filter is the exact-match \
                    pruning minmax cannot do within a week's shared UUIDv7 prefix"""),
            Map.ofEntries(
                    Map.entry("start_time", BaselineTypeDifference.builder()
                            .liveType("DateTime64(9, 'UTC')")
                            .shadowType("DateTime64(6, 'UTC')")
                            .reason("nanosecond -> microsecond precision; nothing ingested needs finer (000115)")
                            .build()),
                    Map.entry("created_at", BaselineTypeDifference.builder()
                            .liveType("DateTime64(9, 'UTC')")
                            .shadowType("DateTime64(6, 'UTC')")
                            .reason("nanosecond -> microsecond precision; nothing ingested needs finer (000115)")
                            .build()),
                    Map.entry("end_time", BaselineTypeDifference.builder()
                            .liveType("Nullable(DateTime64(9, 'UTC'))")
                            .shadowType("DateTime64(6, 'UTC')")
                            .reason("""
                                    Nullable -> non-nullable with an epoch sentinel, dropping the null-mask overhead \
                                    (000115)""")
                            .build()),
                    Map.entry("ttft", BaselineTypeDifference.builder()
                            .liveType("Nullable(Float64)")
                            .shadowType("Float64")
                            .reason("Nullable -> non-nullable with a NaN sentinel (000115)")
                            .build()),
                    Map.entry("duration", BaselineTypeDifference.builder()
                            .liveType("Nullable(Float64)")
                            .shadowType("Float64")
                            .reason("""
                                    Nullable -> non-nullable, materialized from the sentinels rather than a null check \
                                    (000115)""")
                            .build()),
                    Map.entry("id_at", BaselineTypeDifference.builder()
                            .liveType("DateTime('UTC')")
                            .shadowType("DateTime64(0, 'UTC')")
                            .reason("""
                                    DateTime -> DateTime64(0), honest past 2106 so a far-future UUIDv7 partitions \
                                    correctly (000115)""")
                            .build()),
                    Map.entry("parent_span_id", BaselineTypeDifference.builder()
                            .liveType("String")
                            .shadowType("FixedString(36)")
                            .reason("""
                                    a UUID or the empty sentinel, so the fixed width drops the per-value offset \
                                    (000115); reads must go through SentinelTranslation, which recognises the all-NUL \
                                    padded form""")
                            .build()),
                    Map.entry("usage", BaselineTypeDifference.builder()
                            .liveType("Map(String, Int32)")
                            .shadowType("Map(String, Int64)")
                            .reason("a lossless widening matching what SpanDAO already binds (000115)")
                            .build()),
                    Map.entry("model", BaselineTypeDifference.builder()
                            .liveType("String")
                            .shadowType("LowCardinality(String)")
                            .reason("""
                                    few enough distinct values for the dictionary to pay off, measured before the \
                                    successor was designed (000115)""")
                            .build()),
                    Map.entry("provider", BaselineTypeDifference.builder()
                            .liveType("String")
                            .shadowType("LowCardinality(String)")
                            .reason("""
                                    few enough distinct values for the dictionary to pay off, measured before the \
                                    successor was designed (000115)""")
                            .build()),
                    Map.entry("total_estimated_cost_version", BaselineTypeDifference.builder()
                            .liveType("String")
                            .shadowType("LowCardinality(String)")
                            .reason("""
                                    few enough distinct values for the dictionary to pay off, measured before the \
                                    successor was designed (000115)""")
                            .build())),
            BaselineKeyDifference.builder()
                    .liveKey("workspace_id, project_id, trace_id, parent_span_id, id")
                    .shadowKey("workspace_id, project_id, trace_id, id")
                    .reason("""
                            parent_span_id is dropped from the sort key (OPIK-7750, 000115): nothing filters on it, id \
                            alone is unique per span so dedup identity is unchanged, and being mutable it broke \
                            ReplacingMergeTree dedup while it stayed in the key. The children-of-a-span access path \
                            moves to idx_spans_parent_span_id_bf""")
                    .build(),
            Path.of("data-migrations/spans-local-v2-cutover/scripts/db-app-analytics/000001_backfill_spans_local_v2.sql"),
            null);

    private static final String COLUMN_NAME_PATTERN = "[a-z_][a-z0-9_]*";

    /** A trailing {@code AS <column>} alias on a SELECT projection entry, naming that entry's destination column. */
    private static final Pattern SELECT_ALIAS = Pattern.compile("(?i)\\bAS\\s+([a-z_][a-z0-9_]*)\\s*$");

    /**
     * The live read/insert-facing table in both topologies: a {@code MergeTree} before the cutover, a
     * {@code Distributed} wrapper after.
     */
    private final String live;

    /** The empty successor the cutover promotes; exists only pre-cutover, since the cutover renames it away. */
    private final String shadow;

    /** The shard the {@code Distributed} wrapper fronts; exists only post-cutover. */
    private final String shard;

    /** The parked pre-cutover data, retained through the soak; exists only post-cutover. */
    private final String backup;

    /**
     * The columns that exist on the shadow with no counterpart on the live table, pinned rather than merely named.
     *
     * <p>These are the one blind spot of the type comparison, which iterates the live table's columns and looks each up
     * on the shadow, so a shadow-only column is never reached. Left unpinned, {@code is_deleted} could become
     * {@code DEFAULT 1} and every row the cutover copies would materialise as a tombstone — the backfill omits the
     * column precisely so it takes its default, which makes the default the whole contract.
     */
    private final Map<String, ShadowOnlyColumn> shadowOnlyColumns;

    /**
     * Skip indices the successor adds because its layout needs them and the live table cannot use them: the
     * {@code id_at} index covers the partition-input column the live table does not partition on, and the id minmax and
     * bloom pair prunes the id-range and exact-match predicates the retention and read paths issue against the weekly
     * partitions. All are storage-only, so post-cutover they live on the shard alone.
     */
    private final Set<String> shadowOnlySkipIndices;

    /**
     * Skip indices on the live table with no same-named counterpart on the successor, each mapped to the reason it has
     * none.
     *
     * <p>A missing index is normally exactly the drift this guard exists to catch, so the exemption is narrow and
     * carries its justification: its only member today is the spans id minmax index, which the successor renamed and
     * complemented rather than dropped. {@link #assertPreCutoverParity} also asserts each entry still describes reality
     * in both directions, so an exemption that has gone stale is deleted rather than left covering an index nothing
     * checks.
     */
    private final Map<String, String> liveOnlySkipIndices;

    /**
     * The <b>only</b> columns whose type may differ between the live table and the shadow, each with the reason it does:
     * six of thirty-one shared columns for traces, eleven of thirty-eight for spans. Small enough to enumerate, which is
     * why type parity is asserted for every other column rather than skipped wholesale.
     *
     * <p>An entry claims the difference is deliberate and that the cutover converts it safely — the backfill carries the
     * corresponding {@code coalesce(...)} or cast where one is needed. {@link #assertPreCutoverParity} also asserts the
     * map is not stale, since an entry whose columns no longer differ must be removed, so the allowlist cannot quietly
     * grow into a blanket exemption.
     */
    private final Map<String, BaselineTypeDifference> baselineTypeDifferences;

    /**
     * The sorting and primary key difference the cutover introduces, or {@code null} when the successor keeps the live
     * key. Pinned on <b>both</b> sides for the same reason a type difference is, with more at stake: {@code ORDER BY} is
     * immutable on {@code MergeTree}, so a key that is wrong at creation can only be fixed by recreating the table.
     */
    private final BaselineKeyDifference baselineKeyDifference;

    /**
     * The shipped cutover backfill, read rather than restated: the whole point is that its column list cannot drift from
     * the tables, so asserting against a copy of it here would assert nothing. Relative to the Maven module directory
     * ({@code apps/opik-backend}), which is the working directory both locally and in CI.
     */
    private final Path backfillSql;

    /**
     * The ticket that ships {@link #backfillSql} while it has not landed yet; {@code null} once a family's backfill is
     * on main, which makes its absence a hard failure.
     *
     * <p>A guard that silently skips a leg is worse than one that is absent, so this is not a quiet fallback: each
     * family's pre-cutover gate asserts this field's current value against the filesystem. The day the backfill lands,
     * that assertion fails and forces whoever merged it to clear the marker, at which point the two backfill legs start
     * asserting on their own.
     */
    private final String backfillPendingTicket;

    /**
     * Asserts the pre-cutover invariant: the live table, the shadow it will be replaced by, and the backfill column
     * list that moves the data between them all agree.
     */
    void assertPreCutoverParity(Connection connection, String database) throws SQLException, IOException {
        var liveTable = TableSchema.read(connection, database, live);
        var shadowTable = TableSchema.read(connection, database, shadow);

        assertThat(liveTable.isDistributed())
                .as("pre-cutover `%s` must still be the local MergeTree, not a Distributed wrapper", live)
                .isFalse();

        assertThat(shadowTable.columnNames())
                .as("""
                        read-facing column parity: every column on `%s` must exist on the `%s` shadow (and vice versa, \
                        beyond the documented engine meta-columns %s). A change that adds or drops a column on one \
                        without the other leaves the cutover promoting a table that does not match the live one.\
                        """, live, shadow, shadowOnlyColumns.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        SetUtils.union(new LinkedHashSet<>(liveTable.columnNames()), shadowOnlyColumns.keySet()));

        assertColumnTypeParity(liveTable, shadowTable);
        assertShadowOnlyColumns(shadowTable);
        assertBaselineTypeDifferencesAreCurrent(liveTable, shadowTable);
        assertBackfillParity(liveTable, shadowTable);
        assertSkipIndexParity(liveTable, shadowTable);

        // Compared by full definition, not just by name: two projections sharing a name but not a query would leave the
        // successor computing something different after the swap, and a name-only check cannot see that.
        assertThat(shadowTable.projections())
                .as("""
                        projection parity: a projection is storage-only, but pre-cutover both tables must carry it — \
                        with the same query — so the successor keeps it, and keeps it meaning the same thing, after the \
                        swap\
                        """)
                .containsExactlyInAnyOrderElementsOf(liveTable.projections());

        assertKeyParity(liveTable, shadowTable);

        // The partition keys differ by design — that is the point of the successor — and the expression itself is
        // pinned by the family's partitioning suite, so this only holds the shapes apart.
        assertThat(liveTable.partitionKey()).as("`%s` is unpartitioned pre-cutover", live).isEmpty();
        assertThat(shadowTable.partitionKey()).as("the `%s` successor is weekly-partitioned", shadow).isNotEmpty();
    }

    /**
     * Type parity for every shared column outside the documented baseline. This is the leg that catches a precision
     * narrowed on one table only, or a {@code String} quietly becoming {@code LowCardinality(String)} on one side: the
     * name sets still match, so nothing else would notice.
     */
    private void assertColumnTypeParity(TableSchema liveTable, TableSchema shadowTable) {
        var shadowColumns = shadowTable.columnsByName();
        liveTable.columnsByName().forEach((name, column) -> {
            var shadowColumn = shadowColumns.get(name);
            if (shadowColumn == null || baselineTypeDifferences.containsKey(name)) {
                return;
            }
            assertThat(shadowColumn.type())
                    .as("""
                            column type parity: `%s` must have the same type on `%s` and the `%s` shadow. A type that \
                            differs on one side only is converted at the cutover — silently truncating, or changing the \
                            read/write contract. If the difference is deliberate, add it to the family's \
                            baselineTypeDifferences with its reason.\
                            """,
                            name, live, shadow)
                    .isEqualTo(column.type());
        });
    }

    /**
     * The shadow-only columns, which {@link #assertColumnTypeParity} cannot reach because they have no counterpart on
     * the live table. Nothing else checks them at all, so their declared contract is pinned here in full.
     */
    private void assertShadowOnlyColumns(TableSchema shadowTable) {
        var shadowColumns = shadowTable.columnsByName();
        shadowOnlyColumns.forEach((name, expected) -> {
            var column = shadowColumns.get(name);
            assertThat(column)
                    .as("the `%s` shadow must carry `%s` — %s", shadow, name, expected.reason())
                    .isNotNull();
            assertThat(column.type()).as("shadow-only column `%s` type (%s)", name, expected.reason())
                    .isEqualTo(expected.type());
            assertThat(column.defaultKind()).as("shadow-only column `%s` default kind (%s)", name, expected.reason())
                    .isEqualTo(expected.defaultKind());
            assertThat(column.defaultExpression())
                    .as("""
                            shadow-only column `%s` default expression (%s). The cutover backfill omits this column so \
                            it takes its default, which makes the default the entire contract: change it and every \
                            copied row is written with the wrong value.\
                            """, name, expected.reason())
                    .isEqualTo(expected.defaultExpression());
        });
    }

    /**
     * Each allowlisted difference, pinned on both sides. Merely requiring the types to differ would excuse an unrelated
     * drift on either table, and an entry whose columns have converged is a dead exemption that must be removed rather
     * than left covering a column nothing checks.
     */
    private void assertBaselineTypeDifferencesAreCurrent(TableSchema liveTable, TableSchema shadowTable) {
        var shadowColumns = shadowTable.columnsByName();
        baselineTypeDifferences.forEach((name, expected) -> {
            var liveColumn = liveTable.columnsByName().get(name);
            var shadowColumn = shadowColumns.get(name);
            assertThat(liveColumn).as("baselineTypeDifferences names `%s`, which must exist on `%s`", name, live)
                    .isNotNull();
            assertThat(shadowColumn).as("baselineTypeDifferences names `%s`, which must exist on `%s`", name, shadow)
                    .isNotNull();
            assertThat(liveColumn.type())
                    .as("""
                            allowlisted column `%s` must still be exactly the documented type on `%s` (%s). If it \
                            changed, either the change is wrong or the allowlist entry needs updating — and if the two \
                            have converged, delete the entry so the column is type-checked like every other.\
                            """, name, live, expected.reason())
                    .isEqualTo(expected.liveType());
            assertThat(shadowColumn.type())
                    .as("""
                            allowlisted column `%s` must still be exactly the documented type on the `%s` shadow (%s)\
                            """, name, shadow, expected.reason())
                    .isEqualTo(expected.shadowType());
        });
    }

    /**
     * The leg no table-to-table comparison can make: a column carried correctly by both tables is still lost at the
     * cutover if the backfill's explicit column list does not name it.
     *
     * <p>Skipped, on the record, while a family's backfill has not been written yet — see
     * {@link #backfillPendingTicket}, whose value each family's gate asserts against the filesystem so the skip cannot
     * outlive the reason for it.
     */
    private void assertBackfillParity(TableSchema liveTable, TableSchema shadowTable) throws IOException {
        if (backfillIsPending()) {
            return;
        }

        var backfillColumns = backfillColumnList();

        assertThat(liveTable.storedColumnNames())
                .as("""
                        cutover backfill parity: the backfill's explicit INSERT column list must name exactly the \
                        insertable columns of `%s`. A new preserved column added to the tables but not to %s is \
                        silently dropped at the cutover; a column removed from the tables but left in the list fails \
                        the backfill.\
                        """, live, backfillSql.getFileName())
                .containsExactlyInAnyOrderElementsOf(backfillColumns);

        assertThat(shadowTable.storedColumnNames())
                .as("""
                        the `%s` shadow must accept exactly the backfilled columns plus its engine meta-columns %s \
                        (which the backfill deliberately omits so they take their defaults)\
                        """, shadow, shadowOnlyColumns.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        SetUtils.union(new LinkedHashSet<>(backfillColumns), shadowOnlyColumns.keySet()));

        // The column sets above say the right columns are carried; this says they are carried to the right places.
        assertBackfillInsertMatchesSelect();
    }

    private void assertSkipIndexParity(TableSchema liveTable, TableSchema shadowTable) {
        // Checked before the set comparison because both fire on the same drift and only this one names the real fix: a
        // stale exemption also makes the shadow carry an index the expected set excludes, so the set comparison would
        // report it as an unexpected element, which reads as "drop it from the shadow" — the opposite of what a
        // converged pair calls for.
        assertLiveOnlyIndexExemptionsAreCurrent(liveTable, shadowTable);

        var expectedShadowIndices = SetUtils.union(
                SetUtils.difference(liveTable.skipIndexNames(), liveOnlySkipIndices.keySet()), shadowOnlySkipIndices);

        assertThat(shadowTable.skipIndexNames())
                .as("""
                        skip-index parity: an index added to `%s` must also be added to the `%s` shadow, or the \
                        successor silently loses the pruning the read path was tuned for. The shadow's own extras are \
                        %s, and the live-only exemptions are %s.\
                        """, live, shadow, shadowOnlySkipIndices, liveOnlySkipIndices.keySet())
                .containsExactlyInAnyOrderElementsOf(expectedShadowIndices);

        var shadowIndices = shadowTable.skipIndicesByName();
        liveTable.skipIndicesByName().forEach((name, index) -> {
            if (liveOnlySkipIndices.containsKey(name)) {
                return;
            }
            assertThat(shadowIndices.get(name))
                    .as("skip index `%s` must be defined identically on `%s` and the `%s` shadow", name, live, shadow)
                    .isEqualTo(index);
        });
    }

    /**
     * Same discipline as the type allowlist: an exemption whose index has gone, or whose counterpart has appeared on the
     * shadow after all, is dead and must be deleted rather than left excusing nothing.
     */
    private void assertLiveOnlyIndexExemptionsAreCurrent(TableSchema liveTable, TableSchema shadowTable) {
        liveOnlySkipIndices.forEach((name, reason) -> {
            assertThat(liveTable.skipIndexNames())
                    .as("""
                            the live-only skip-index exemption for `%s` (%s) must still describe reality: `%s` no \
                            longer carries that index, so delete the entry\
                            """, name, reason, live)
                    .contains(name);
            assertThat(shadowTable.skipIndexNames())
                    .as("""
                            the live-only skip-index exemption for `%s` (%s) is stale: the `%s` shadow now carries an \
                            index of that name, so delete the entry and let it be compared like every other\
                            """, name, reason, shadow)
                    .doesNotContain(name);
        });
    }

    private void assertKeyParity(TableSchema liveTable, TableSchema shadowTable) {
        if (baselineKeyDifference == null) {
            assertThat(shadowTable.sortingKey())
                    .as("the successor's sorting key is the dedup key the backfill relies on; it must match `%s`", live)
                    .isEqualTo(liveTable.sortingKey());
            assertThat(shadowTable.primaryKey())
                    .as("the successor's primary key must match `%s`", live)
                    .isEqualTo(liveTable.primaryKey());
            return;
        }

        // Pinned on both sides rather than merely asserted to differ, for the same reason a type difference is — and
        // with more at stake, since a successor whose key drifted from the documented one cannot be corrected by an
        // ALTER at all.
        assertThat(List.of(liveTable.sortingKey(), liveTable.primaryKey()))
                .as("""
                        allowlisted key difference: `%s` must still sort by exactly the documented key (%s). If it \
                        changed, either the change is wrong or the entry needs updating — and if the two keys have \
                        converged, delete the entry so they are compared like every other family's.\
                        """, live, baselineKeyDifference.reason())
                .containsOnly(baselineKeyDifference.liveKey());
        assertThat(List.of(shadowTable.sortingKey(), shadowTable.primaryKey()))
                .as("""
                        allowlisted key difference: the `%s` shadow must still sort by exactly the documented key (%s). \
                        ORDER BY is immutable on MergeTree, so this is only fixable by recreating the table.\
                        """,
                        shadow, baselineKeyDifference.reason())
                .containsOnly(baselineKeyDifference.shadowKey());
    }

    /**
     * Asserts the post-cutover invariant: the {@code Distributed} wrapper exposes exactly the shard's columns.
     *
     * <p>This is the assertion that catches the silent failure — a shard-only {@code ADD COLUMN} succeeds, but the
     * column is unreadable through the wrapper (ClickHouse code 47), so the feature that added it is broken on every
     * cut-over install while CI stays green.
     */
    void assertPostCutoverParity(Connection connection, String database) throws SQLException {
        var wrapper = TableSchema.read(connection, database, live);
        var shardTable = TableSchema.read(connection, database, shard);

        assertThat(wrapper.isDistributed())
                .as("post-cutover `%s` must be the Distributed wrapper; found engine `%s`", live, wrapper.engine())
                .isTrue();

        // Being *a* Distributed table is not enough: one pointed at another cluster, database, shard table or sharding
        // key would expose the same column list and pass every assertion below. Pinning the parameters also keeps the
        // spliced statements honest against the shipped 000003_exchange_and_wrap.sql they mirror.
        assertThat(wrapper.engine())
                .as("""
                        the Distributed `%s` must front `%s` on the '{cluster}' cluster in the same database, sharded on \
                        sipHash64(project_id) — the wrap applied by the runbook. A wrapper over a different target reads \
                        the wrong data while looking structurally identical.\
                        """,
                        live, shard)
                .contains("Distributed")
                .contains("'" + database + "'")
                .contains("'" + shard + "'")
                .contains("sipHash64(project_id)");
        assertThat(shardTable.isDistributed())
                .as("`%s` must be the local MergeTree shard; found engine `%s`", shard, shardTable.engine())
                .isFalse();

        // The shard must be the promoted successor, not the original table under a new name. Wrapping without having
        // exchanged first leaves a topology that satisfies every column comparison below — the wrapper is created AS
        // whatever it fronts — while the data underneath is still unpartitioned, so retention and tiering silently have
        // nothing to work on.
        assertThat(shardTable.partitionKey())
                .as("""
                        post-cutover `%s` must be the weekly-partitioned successor the cutover promoted; an unpartitioned \
                        shard means the wrap ran without the EXCHANGE that should precede it\
                        """,
                        shard)
                .isNotEmpty();

        assertThat(wrapper.columnNames())
                .as("""
                        wrapper column parity: the Distributed `%s` must expose exactly the columns `%s` holds, in the \
                        same order. A read-facing change applied only to the shard leaves the wrapper unable to see it \
                        (code 47); one applied only to the wrapper leaves reads referencing a column no shard stores.\
                        """, live, shard)
                .isEqualTo(shardTable.columnNames());

        // Strict per column here — type, default kind AND the DEFAULT/MATERIALIZED expression — because the wrapper is
        // created `AS` the shard, so it starts as an exact copy and has no legitimate reason to diverge. (The opposite
        // of pre-cutover, where the shadow deliberately differs; see the class Javadoc.) Without the expression check, a
        // MATERIALIZED column added to the shard with one expression and to the wrapper with another would satisfy
        // every name and type assertion while computing something different on each side.
        var shardColumns = shardTable.columnsByName();
        wrapper.columnsByName().forEach((name, column) -> {
            var shardColumn = shardColumns.get(name);
            assertThat(column.type())
                    .as("column `%s` must have the same type on the Distributed `%s` and on `%s`", name, live, shard)
                    .isEqualTo(shardColumn.type());
            assertThat(column.defaultKind())
                    .as("column `%s` must have the same default kind on the Distributed `%s` and on `%s`", name, live,
                            shard)
                    .isEqualTo(shardColumn.defaultKind());
            assertThat(column.defaultExpression())
                    .as("""
                            column `%s` must have the same DEFAULT/MATERIALIZED expression on the Distributed `%s` and \
                            on `%s`; the wrapper is created AS the shard, so a divergence here means one side was \
                            altered on its own\
                            """, name, live, shard)
                    .isEqualTo(shardColumn.defaultExpression());
        });
    }

    /** Whether this family's cutover backfill has yet to be written, in which case the two backfill legs are skipped. */
    boolean backfillIsPending() {
        return backfillPendingTicket != null && !Files.isRegularFile(backfillSql);
    }

    /**
     * Every column the shipped cutover backfill names in its {@code INSERT INTO ... (...)} list, <b>in order</b>, with
     * duplicates rejected.
     *
     * <p>Order and uniqueness matter even though the parity comparisons that consume this are set-based: ClickHouse maps
     * {@code INSERT (...) SELECT ...} by <i>position</i>, so a duplicated or reordered entry changes which destination
     * column a value lands in, and a set would hide both. {@link #assertBackfillInsertMatchesSelect} uses the order this
     * preserves.
     *
     * <p>Line comments are stripped before the statement is located because the file's header prose mentions
     * {@code INSERT} and carries parentheses; the reference SQL contains no string literal holding {@code --}, so the
     * naive strip is safe here. Each parsed entry is checked to be a bare column name, so a future edit that puts an
     * expression or a nested parenthesis in the list fails loudly instead of being silently mis-parsed.
     */
    List<String> backfillColumnList() throws IOException {
        return columnListIn(readBackfillSql());
    }

    /**
     * {@link #backfillColumnList()} over already-read SQL, so {@code CutoverBackfillParityTest} can exercise the parse
     * against crafted statements. The shipped backfill is append-only and correct, so the only way to prove these
     * assertions fire is to hand the parser SQL that breaks them.
     */
    List<String> columnListIn(String sql) {
        int insertAt = sql.indexOf("INSERT INTO");
        assertThat(insertAt).as("no INSERT INTO statement found in %s", backfillSql).isNotNegative();

        int open = sql.indexOf('(', insertAt);
        int close = sql.indexOf(')', open);
        assertThat(open).as("no column list found after INSERT INTO in %s", backfillSql).isNotNegative();
        assertThat(close).as("unterminated column list in %s", backfillSql).isNotNegative();

        var columns = Arrays.stream(sql.substring(open + 1, close).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();

        assertThat(columns)
                .as("""
                        the backfill column list must hold bare column names; an expression here means this parse is \
                        reading the wrong parentheses\
                        """)
                .isNotEmpty()
                .allMatch(column -> column.matches(COLUMN_NAME_PATTERN));
        assertThat(columns)
                .as("""
                        the backfill column list must not repeat a column: ClickHouse maps INSERT (...) SELECT ... by \
                        position, so a duplicate silently shifts every later value into the wrong destination column\
                        """)
                .doesNotHaveDuplicates();

        return columns;
    }

    /**
     * Asserts the backfill's {@code INSERT} column list and its {@code SELECT} projection line up position by position.
     *
     * <p>ClickHouse pairs the two by position, not by name, so a column added to one list and not the other — or added
     * at a different offset — sends every subsequent value to the wrong destination column. Nothing about that is a
     * syntax error, and both tables stay perfectly consistent with each other, so no amount of table-to-table
     * comparison can see it: the mapping between them has to be checked directly.
     *
     * <p>Each projection entry must therefore name its destination — a bare column, or an expression carrying an
     * {@code AS <name>} alias, as {@code coalesce(end_time, ...) AS end_time} does. An unaliased expression is legal SQL
     * and would still map positionally, but it leaves the mapping unverifiable, so one fails here. The shipped SQL
     * already aliases every expression, so this pins an existing convention rather than demanding a change.
     */
    void assertBackfillInsertMatchesSelect() throws IOException {
        assertInsertMatchesSelectIn(readBackfillSql());
    }

    /** {@link #assertBackfillInsertMatchesSelect()} over already-read SQL; see {@link #columnListIn}. */
    void assertInsertMatchesSelectIn(String sql) {
        var insertColumns = columnListIn(sql);
        var selectTargets = selectTargetsIn(sql);

        assertThat(selectTargets)
                .as("""
                        cutover backfill select parity: the SELECT projection of %s must line up with its INSERT column \
                        list position by position, because ClickHouse pairs them by position and not by name. A \
                        mismatch here writes values into the wrong destination columns at cutover time, without any \
                        error.\
                        """,
                        backfillSql.getFileName())
                .containsExactlyElementsOf(insertColumns);
    }

    /**
     * The destination column each entry of the backfill's {@code SELECT} projection targets: the alias when the entry is
     * an expression, the column name when it is bare.
     */
    private List<String> selectTargetsIn(String sql) {
        int selectAt = sql.indexOf("SELECT", sql.indexOf("INSERT INTO"));
        assertThat(selectAt).as("no SELECT found after the INSERT column list in %s", backfillSql).isNotNegative();

        int fromAt = indexOfTopLevelFrom(sql, selectAt + "SELECT".length());
        assertThat(fromAt).as("no top-level FROM found after SELECT in %s", backfillSql).isNotNegative();

        return splitTopLevel(sql.substring(selectAt + "SELECT".length(), fromAt)).stream()
                .map(this::destinationColumnOf)
                .toList();
    }

    private String destinationColumnOf(String projectionEntry) {
        var aliased = SELECT_ALIAS.matcher(projectionEntry);
        if (aliased.find()) {
            return aliased.group(1);
        }
        assertThat(projectionEntry)
                .as("""
                        every entry of the backfill SELECT projection must name its destination — a bare column, or an \
                        expression with an `AS <column>` alias — so its positional mapping to the INSERT column list \
                        can be verified\
                        """)
                .matches(COLUMN_NAME_PATTERN);
        return projectionEntry;
    }

    /** Index of the {@code FROM} keyword at paren depth 0 and outside a string literal, or {@code -1}. */
    private int indexOfTopLevelFrom(String sql, int from) {
        int depth = 0;
        boolean inString = false;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
            } else
                if (!inString && depth == 0 && sql.startsWith("FROM", i)
                        && !Character.isLetterOrDigit(sql.charAt(i - 1))) {
                            return i;
                        }
        }
        return -1;
    }

    /** Splits on commas at paren depth 0 and outside a string literal, so nested call arguments stay intact. */
    private List<String> splitTopLevel(String projection) {
        var entries = new ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < projection.length(); i++) {
            char c = projection.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
            } else if (!inString && depth == 0 && c == ',') {
                entries.add(normalizeWhitespace(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            entries.add(normalizeWhitespace(current.toString()));
        }
        return entries;
    }

    private String normalizeWhitespace(String entry) {
        return entry.trim().replaceAll("\\s+", " ");
    }

    private String readBackfillSql() throws IOException {
        assertThat(backfillSql)
                .as("""
                        the shipped cutover backfill must be readable at %s (relative to apps/opik-backend); if it \
                        moved, update this guard rather than dropping the assertion. If it has not been written yet, \
                        that is what backfillPendingTicket is for — %s.\
                        """, backfillSql,
                        backfillPendingTicket == null
                                ? "this family declares none, so the file is expected to exist"
                                : "this family names " + backfillPendingTicket)
                .isRegularFile();
        return stripLineComments(Files.readString(backfillSql));
    }

    private String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }

    /** A shadow-only column's full declared contract. */
    @Builder(toBuilder = true)
    record ShadowOnlyColumn(String type, String defaultKind, String defaultExpression, String reason) {
    }

    /**
     * One allowlisted type difference, pinned on <b>both</b> sides rather than merely asserted to exist. Recording only
     * that the types differ would let either side drift to an unrelated type — {@code start_time} becoming
     * {@code String}, say — while still "differing" and so still being excused.
     */
    @Builder(toBuilder = true)
    record BaselineTypeDifference(String liveType, String shadowType, String reason) {
    }

    /** One allowlisted sorting and primary key difference, pinned on both sides for the same reason as a type one. */
    @Builder(toBuilder = true)
    record BaselineKeyDifference(String liveKey, String shadowKey, String reason) {
    }
}
