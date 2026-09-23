package com.comet.opik.db;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The changelog names and ledger vocabulary shared by each family's two topology gates when they apply the reference
 * DDL fixtures.
 *
 * <p>{@link #getChangelog()} is the topology-aware reference migration — two complementary precondition-guarded
 * changesets keyed on whether the family's {@code _local} shard exists, of which exactly one executes while the other is
 * recorded {@code MARK_RAN}. {@link #getUnguardedChangelog()} is the negative control: the same intent written the
 * ordinary way, as one unconditional {@code ALTER TABLE}, which applies cleanly on both topologies and is wrong on both.
 *
 * <p><b>Isolation from the shipped changelog.</b> These fixtures are deliberately kept away from anything an install
 * runs, on three independent levels, because they do write to the Liquibase ledger:
 * <ul>
 *   <li>they live under {@code src/test/resources}, so the shipped changelog's {@code includeAll} over
 *   {@code liquibase/db-app-analytics/migrations/} cannot reach them and no deployment can apply them;</li>
 *   <li>their file names carry no migration number and their changeset author is a per-family
 *   {@code opik-NNNN-test-fixture}, so a ledger row from a fixture is unmistakable and can never collide with a shipped
 *   changeset id;</li>
 *   <li>the gates that apply them run on dedicated, non-reused containers that are stopped afterwards, and each gate
 *   asserts the shipped changelog is still fully applied once a fixture has run — see
 *   {@code applyingTheFixtureLeavesTheShippedChangelogIntact} in every gate.</li>
 * </ul>
 *
 * <p>The reference change itself is identical for both families — a {@code MATERIALIZED} column over {@code name} and a
 * {@code set(0)} index on it — because both tables carry a {@code name} column and the point is the <i>routing</i>, not
 * the DDL. Only the changelog paths, the author and the changeset ids vary, which is why they are the enum's fields.
 *
 * <p>See {@code apps/opik-backend/docs/cutover-table-schema-ddl.md} for the playbook the references implement.
 */
@Getter
@RequiredArgsConstructor
enum CutoverDdlReferenceFixture {

    TRACES("liquibase/traces-ddl-reference/changelog.xml",
            "liquibase/traces-ddl-unguarded/changelog.xml",
            "opik-7772-test-fixture",
            "reference_topology_aware_change_pre_cutover",
            "reference_topology_aware_change_post_cutover"),

    SPANS("liquibase/spans-ddl-reference/changelog.xml",
            "liquibase/spans-ddl-unguarded/changelog.xml",
            "opik-8377-test-fixture",
            "reference_spans_topology_aware_change_pre_cutover",
            "reference_spans_topology_aware_change_post_cutover");

    private final String changelog;
    private final String unguardedChangelog;

    /**
     * The changeset author this family's fixtures use. Deliberately not {@code opik}: it marks a ledger row as
     * belonging to a test fixture rather than to a shipped migration, and keeps fixture ids in their own namespace.
     */
    private final String fixtureAuthor;

    private final String preCutoverChangeSet;
    private final String postCutoverChangeSet;

    /** The reference field change: MATERIALIZED, so read-facing — it must reach the shard and the wrapper. */
    static final String DERIVED_COLUMN = "reference_derived";

    /**
     * The full contract the field change declares, asserted rather than merely its presence: a column of the right name
     * but the wrong type or default kind would satisfy a name check while breaking what the migration promises.
     */
    static final String DERIVED_COLUMN_TYPE = "UInt64";
    static final String DERIVED_COLUMN_DEFAULT_KIND = "MATERIALIZED";

    /**
     * The expression too, not just the kind: a {@code MATERIALIZED UInt64} computing something other than what the
     * reference declares satisfies every other assertion while producing different values on each topology — the same
     * class of drift {@code assertPostCutoverParity} compares expressions to catch.
     */
    static final String DERIVED_COLUMN_EXPRESSION = "length(name)";

    /** The reference index change: storage-only — it must reach the shard alone. */
    static final String STORAGE_INDEX = "idx_reference_storage";

    /**
     * The index's full definition. Same reasoning as the column: an index of this name with a different type,
     * expression or granularity is not the index the migration declared.
     */
    static final TableSchema.SkipIndex EXPECTED_STORAGE_INDEX = TableSchema.SkipIndex.builder()
            .name(STORAGE_INDEX)
            .typeFull("set(0)")
            .expression("name")
            .granularity(1)
            .build();

    /** The column the unguarded negative control adds to the live table and nothing else. */
    static final String UNGUARDED_COLUMN = "unguarded_column";

    /** Liquibase records a changeset whose statements ran as {@code EXECUTED}. */
    static final String EXECUTED = "EXECUTED";

    /**
     * Liquibase records a changeset whose precondition failed with {@code onFail:MARK_RAN} as {@code MARK_RAN}: it is
     * marked applied without its statements running, which is what lets one file serve both topologies and stay
     * idempotent — the skipped branch is never retried on a later startup.
     */
    static final String MARK_RAN = "MARK_RAN";

    /**
     * How Liquibase recorded {@code changeSetId} in the ledger, or {@code null} if it recorded nothing. The ledger lives
     * in {@code default}, not the analytics database (matching {@code ChangelogRebaselineTest}).
     *
     * <p>The changeset id and author are <b>bound</b>, not interpolated: they are values in a predicate, which is what
     * {@code SKILL.md}'s SQL rule reserves for binding. (Identifiers elsewhere in these gates cannot be bound —
     * ClickHouse accepts no parameter in a table or column position — but these two can, so they are.)
     */
    String execType(Connection connection, String changeSetId) throws SQLException {
        var sql = """
                SELECT EXECTYPE FROM default.DATABASECHANGELOG WHERE ID = ? AND AUTHOR = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, changeSetId);
            statement.setString(2, fixtureAuthor);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }
}
