package com.comet.opik.db;

import lombok.experimental.UtilityClass;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The names and ledger vocabulary shared by the two topology gates when they apply the reference DDL fixtures.
 *
 * <p>{@link #CHANGELOG} is the topology-aware reference migration — two complementary precondition-guarded changesets
 * keyed on whether {@code traces_local} exists, of which exactly one executes while the other is recorded
 * {@code MARK_RAN}. {@link #UNGUARDED_CHANGELOG} is the negative control: the same intent written the ordinary way, as
 * one unconditional {@code ALTER TABLE traces}, which applies cleanly on both topologies and is wrong on both.
 *
 * <p><b>Isolation from the shipped changelog.</b> These fixtures are deliberately kept away from anything an install
 * runs, on three independent levels, because they do write to the Liquibase ledger:
 * <ul>
 *   <li>they live under {@code src/test/resources}, so the shipped changelog's {@code includeAll} over
 *   {@code liquibase/db-app-analytics/migrations/} cannot reach them and no deployment can apply them;</li>
 *   <li>their file names carry no migration number and their changeset author is
 *   {@value #FIXTURE_AUTHOR}, so a ledger row from a fixture is unmistakable and can never collide with a shipped
 *   changeset id;</li>
 *   <li>the gates that apply them run on dedicated, non-reused containers that are stopped afterwards, and each gate
 *   asserts the shipped changelog is still fully applied once a fixture has run — see
 *   {@code applyingTheFixtureLeavesTheShippedChangelogIntact} in both gates.</li>
 * </ul>
 *
 * <p>See {@code apps/opik-backend/docs/traces-schema-ddl.md} for the playbook the reference implements.
 */
@UtilityClass
class TracesDdlReferenceFixture {

    static final String CHANGELOG = "liquibase/traces-ddl-reference/changelog.xml";
    static final String UNGUARDED_CHANGELOG = "liquibase/traces-ddl-unguarded/changelog.xml";

    /**
     * The changeset author every fixture uses. Deliberately not {@code opik}: it marks a ledger row as belonging to a
     * test fixture rather than to a shipped migration, and keeps fixture ids in their own namespace.
     */
    static final String FIXTURE_AUTHOR = "opik-7772-test-fixture";

    static final String PRE_CUTOVER_CHANGESET = "reference_topology_aware_change_pre_cutover";
    static final String POST_CUTOVER_CHANGESET = "reference_topology_aware_change_post_cutover";

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
    static final TableSchema.SkipIndex EXPECTED_STORAGE_INDEX = new TableSchema.SkipIndex(
            STORAGE_INDEX, "set(0)", "name", 1);

    /** The column the unguarded negative control adds to {@code traces} and nothing else. */
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
    static String execType(Connection connection, String changeSetId) throws SQLException {
        var sql = """
                SELECT EXECTYPE FROM default.DATABASECHANGELOG WHERE ID = ? AND AUTHOR = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, changeSetId);
            statement.setString(2, FIXTURE_AUTHOR);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }
}
