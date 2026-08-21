package com.comet.opik.db;

import lombok.experimental.UtilityClass;

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
 *
 * <p><b>What "parity" covers, and what it does not.</b> Column <b>names</b> and <b>types</b>, and the <b>select and
 * expression definitions</b> built on them — the backfill's {@code INSERT}/{@code SELECT} column mapping, projection
 * queries, and {@code DEFAULT}/{@code MATERIALIZED} expressions where a baseline permits comparing them. It is
 * deliberately <b>not</b> about the data: no row counts, checksums or value comparisons live here (the cutover's data
 * fidelity is {@code TracesLocalV2CutoverTest}'s job, and its full-volume rehearsal the QA gate's). Nor does it cover
 * data <i>lifecycle</i> — table TTL and storage policy are neither names, types nor selects, and the changelog sets
 * neither on the trace tables; the tiered-storage policy is attached by an environment-gated migration outside this
 * changelog, so a guard over the changelog could not meaningfully assert it.
 *
 * <p><b>Why DEFAULT/MATERIALIZED expressions are compared post-cutover but not pre-cutover.</b> The asymmetry is not an
 * oversight. Pre-cutover the two tables differ in expression by design and in most columns: {@code end_time} defaults to
 * an epoch sentinel on the shadow and is {@code Nullable} with no default on {@code traces}; {@code ttft} likewise uses
 * a {@code NaN} sentinel; {@code duration} is materialized from a sentinel comparison rather than a null check; several
 * columns gained an explicit {@code ''} / {@code []} default only on the successor. Requiring equality there would mean
 * an allowlist covering most of the table — exactly the bulk tolerance this guard avoids — and those semantics are
 * already round-tripped by {@code TracesLocalV2TableTest}. Post-cutover there is no such baseline: the
 * {@code Distributed} wrapper is created {@code AS} the shard, so any expression divergence is drift, and
 * {@link #assertPostCutoverParity} compares expressions strictly.
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
     * The <b>only</b> columns whose type may differ between {@code traces} and the shadow, each with the reason it does.
     * Six of thirty-one shared columns — a small, enumerable set, which is why type parity is asserted for every other
     * column rather than skipped wholesale.
     * <p>
     * An entry is a claim that the difference is deliberate and that the cutover converts it safely (the backfill
     * carries the corresponding {@code coalesce(...)} where one is needed). {@link #assertPreCutoverParity} also asserts
     * this map is not stale — an entry whose columns no longer differ must be removed — so the allowlist cannot quietly
     * grow into a blanket exemption.
     */
    static final Map<String, BaselineTypeDifference> BASELINE_TYPE_DIFFERENCES = Map.of(
            "start_time", new BaselineTypeDifference("DateTime64(9, 'UTC')", "DateTime64(6, 'UTC')",
                    "nanosecond -> microsecond precision; nothing ingested needs finer (000101)"),
            "created_at", new BaselineTypeDifference("DateTime64(9, 'UTC')", "DateTime64(6, 'UTC')",
                    "nanosecond -> microsecond precision; nothing ingested needs finer (000101)"),
            "end_time", new BaselineTypeDifference("Nullable(DateTime64(9, 'UTC'))", "DateTime64(6, 'UTC')",
                    "Nullable -> non-nullable with an epoch sentinel, dropping the null-mask overhead (000101)"),
            "ttft", new BaselineTypeDifference("Nullable(Float64)", "Float64",
                    "Nullable -> non-nullable with a NaN sentinel (000101)"),
            "duration", new BaselineTypeDifference("Nullable(Float64)", "Float64",
                    "Nullable -> non-nullable, materialized from the sentinels rather than a null check (000101)"),
            "id_at", new BaselineTypeDifference("DateTime('UTC')", "DateTime64(0, 'UTC')",
                    "DateTime -> DateTime64(0), honest past 2106 so a far-future UUIDv7 partitions correctly (000114)"));

    /**
     * One allowlisted difference, pinned on <b>both</b> sides rather than merely asserted to exist. Recording only that
     * the types differ would let either side drift to an unrelated type — {@code traces.start_time} becoming
     * {@code String}, say — while still "differing" and so still being excused.
     */
    record BaselineTypeDifference(String tracesType, String shadowType, String reason) {
    }

    /**
     * The shipped cutover backfill, read rather than restated: this guard's whole point is that the backfill column
     * list cannot drift from the tables, so asserting against a copy of it here would assert nothing. The path is
     * relative to the Maven module directory ({@code apps/opik-backend}), which is the working directory both locally
     * and in CI.
     */
    private static final Path BACKFILL_SQL = Path
            .of("data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000001_backfill_traces_local_v2.sql");

    private static final String COLUMN_NAME_PATTERN = "[a-z_][a-z0-9_]*";

    /** A trailing {@code AS <column>} alias on a SELECT projection entry, naming that entry's destination column. */
    private static final Pattern SELECT_ALIAS = Pattern.compile("(?i)\\bAS\\s+([a-z_][a-z0-9_]*)\\s*$");

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

        // Type parity for every shared column outside the documented baseline. This is the leg that catches a
        // precision narrowed on one table only, or a String quietly becoming LowCardinality(String) on one side: the
        // name sets still match, so nothing above would notice.
        var shadowColumns = shadow.columnsByName();
        traces.columnsByName().forEach((name, column) -> {
            var shadowColumn = shadowColumns.get(name);
            if (shadowColumn == null || BASELINE_TYPE_DIFFERENCES.containsKey(name)) {
                return;
            }
            assertThat(shadowColumn.type())
                    .as("""
                            column type parity: `%s` must have the same type on `%s` and the `%s` shadow. A type that \
                            differs on one side only is converted at the cutover — silently truncating, or changing the \
                            read/write contract. If the difference is deliberate, add it to BASELINE_TYPE_DIFFERENCES \
                            with its reason.\
                            """,
                            name, TRACES, SHADOW)
                    .isEqualTo(column.type());
        });

        // Each allowlisted difference is pinned on both sides. Merely requiring the types to differ would excuse an
        // unrelated drift on either table, and an entry whose columns have converged is a dead exemption that must be
        // removed rather than left covering a column nothing checks.
        BASELINE_TYPE_DIFFERENCES.forEach((name, expected) -> {
            var tracesColumn = traces.columnsByName().get(name);
            var shadowColumn = shadowColumns.get(name);
            assertThat(tracesColumn).as("BASELINE_TYPE_DIFFERENCES names `%s`, which must exist on `%s`", name, TRACES)
                    .isNotNull();
            assertThat(shadowColumn).as("BASELINE_TYPE_DIFFERENCES names `%s`, which must exist on `%s`", name, SHADOW)
                    .isNotNull();
            assertThat(tracesColumn.type())
                    .as("""
                            allowlisted column `%s` must still be exactly the documented type on `%s` (%s). If it \
                            changed, either the change is wrong or the allowlist entry needs updating — and if the two \
                            have converged, delete the entry so the column is type-checked like every other.\
                            """, name, TRACES, expected.reason())
                    .isEqualTo(expected.tracesType());
            assertThat(shadowColumn.type())
                    .as("""
                            allowlisted column `%s` must still be exactly the documented type on the `%s` shadow (%s)\
                            """, name, SHADOW, expected.reason())
                    .isEqualTo(expected.shadowType());
        });

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

        // The column sets above say the right columns are carried; this says they are carried to the right places.
        assertBackfillInsertMatchesSelect();

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

        // Compared by full definition, not just by name: two projections sharing a name but not a query would leave the
        // successor computing something different after the swap, and a name-only check cannot see that.
        assertThat(shadow.projections())
                .as("""
                        projection parity: a projection is storage-only, but pre-cutover both tables must carry it — \
                        with the same query — so the successor keeps it, and keeps it meaning the same thing, after the \
                        swap\
                        """)
                .containsExactlyInAnyOrderElementsOf(traces.projections());

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

        // Being *a* Distributed table is not enough: one pointed at another cluster, database, shard table or sharding
        // key would expose the same column list and pass every assertion below. Pinning the parameters also keeps the
        // spliced statements honest against the shipped 000003_exchange_and_wrap.sql they mirror.
        assertThat(wrapper.engine())
                .as("""
                        the Distributed `%s` must front `%s` on the '{cluster}' cluster in the same database, sharded on \
                        sipHash64(project_id) — the wrap applied by the runbook. A wrapper over a different target reads \
                        the wrong data while looking structurally identical.\
                        """,
                        TRACES, SHARD)
                .contains("Distributed")
                .contains("'" + database + "'")
                .contains("'" + SHARD + "'")
                .contains("sipHash64(project_id)");
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

        // Strict per column here — type, default kind AND the DEFAULT/MATERIALIZED expression — because the wrapper is
        // created `AS` the shard, so it starts as an exact copy and has no legitimate reason to diverge. (The opposite of
        // pre-cutover, where the shadow deliberately differs; see the class Javadoc.) Without the expression check, a
        // MATERIALIZED column added to the shard with one expression and to the wrapper with another would satisfy every
        // name and type assertion while computing something different on each side.
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
            assertThat(column.defaultExpression())
                    .as("""
                            column `%s` must have the same DEFAULT/MATERIALIZED expression on the Distributed `%s` and \
                            on `%s`; the wrapper is created AS the shard, so a divergence here means one side was \
                            altered on its own\
                            """, name, TRACES, SHARD)
                    .isEqualTo(shardColumn.defaultExpression());
        });
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
    static List<String> backfillColumnList() throws IOException {
        var sql = readBackfillSql();

        int insertAt = sql.indexOf("INSERT INTO");
        assertThat(insertAt).as("no INSERT INTO statement found in %s", BACKFILL_SQL).isNotNegative();

        int open = sql.indexOf('(', insertAt);
        int close = sql.indexOf(')', open);
        assertThat(open).as("no column list found after INSERT INTO in %s", BACKFILL_SQL).isNotNegative();
        assertThat(close).as("unterminated column list in %s", BACKFILL_SQL).isNotNegative();

        var columns = Arrays.stream(sql.substring(open + 1, close).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();

        assertThat(columns)
                .as("the backfill column list must hold bare column names; an expression here means this parse is "
                        + "reading the wrong parentheses")
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
     * <p>ClickHouse pairs the two by position, not by name, so a column added to one list and not the other — or added at
     * a different offset — sends every subsequent value to the wrong destination column. Nothing about that is a syntax
     * error, and both tables stay perfectly consistent with each other, so no amount of table-to-table comparison can
     * see it: the mapping between them has to be checked directly.
     *
     * <p>Each projection entry must therefore name its destination — a bare column, or an expression carrying an
     * {@code AS <name>} alias, as {@code coalesce(end_time, ...) AS end_time} does. An unaliased expression is legal SQL
     * and would still map positionally, but it leaves the mapping unverifiable, so one fails here. The shipped SQL
     * already aliases every expression, so this pins an existing convention rather than demanding a change.
     */
    static void assertBackfillInsertMatchesSelect() throws IOException {
        var insertColumns = backfillColumnList();
        var selectTargets = backfillSelectTargets();

        assertThat(selectTargets)
                .as("""
                        cutover backfill select parity: the SELECT projection of %s must line up with its INSERT column \
                        list position by position, because ClickHouse pairs them by position and not by name. A mismatch \
                        here writes values into the wrong destination columns at cutover time, without any error.\
                        """,
                        BACKFILL_SQL.getFileName())
                .containsExactlyElementsOf(insertColumns);
    }

    /**
     * The destination column each entry of the backfill's {@code SELECT} projection targets: the alias when the entry is
     * an expression, the column name when it is bare.
     */
    private static List<String> backfillSelectTargets() throws IOException {
        var sql = readBackfillSql();

        int selectAt = sql.indexOf("SELECT", sql.indexOf("INSERT INTO"));
        assertThat(selectAt).as("no SELECT found after the INSERT column list in %s", BACKFILL_SQL).isNotNegative();

        int fromAt = indexOfTopLevelFrom(sql, selectAt + "SELECT".length());
        assertThat(fromAt).as("no top-level FROM found after SELECT in %s", BACKFILL_SQL).isNotNegative();

        return splitTopLevel(sql.substring(selectAt + "SELECT".length(), fromAt)).stream()
                .map(TracesSchemaParity::destinationColumnOf)
                .toList();
    }

    private static String destinationColumnOf(String projectionEntry) {
        var aliased = SELECT_ALIAS.matcher(projectionEntry);
        if (aliased.find()) {
            return aliased.group(1);
        }
        assertThat(projectionEntry)
                .as("""
                        every entry of the backfill SELECT projection must name its destination — a bare column, or an \
                        expression with an `AS <column>` alias — so its positional mapping to the INSERT column list can \
                        be verified\
                        """)
                .matches(COLUMN_NAME_PATTERN);
        return projectionEntry;
    }

    /** Index of the {@code FROM} keyword at paren depth 0 and outside a string literal, or {@code -1}. */
    private static int indexOfTopLevelFrom(String sql, int from) {
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
    private static List<String> splitTopLevel(String projection) {
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

    private static String normalizeWhitespace(String entry) {
        return entry.trim().replaceAll("\\s+", " ");
    }

    private static String readBackfillSql() throws IOException {
        assertThat(BACKFILL_SQL)
                .as("the shipped cutover backfill must be readable at %s (relative to apps/opik-backend); if it moved, "
                        + "update this guard rather than dropping the assertion", BACKFILL_SQL)
                .isRegularFile();
        return stripLineComments(Files.readString(BACKFILL_SQL));
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
