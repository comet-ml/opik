package com.comet.opik.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * The static lint over the shipped ClickHouse migrations: a migration that mutates {@code traces} must be
 * topology-aware.
 *
 * <p><b>Why, when the parity gates already cover this.</b> The gates are correct but expensive and indirect — they spin
 * up ClickHouse and ZooKeeper, apply 100-plus migrations twice, and report the <i>consequence</i> (a column missing from
 * the shadow) rather than the cause. This runs in milliseconds with no container and names the file and changeset, so
 * the common mistake is caught at the point it was made. It is a fast path in front of the gates, not a replacement: it
 * checks that a guard is present, and only the gates can check the DDL is actually right.
 *
 * <p><b>Two kinds of test, and the second is the load-bearing one.</b> {@link ShippedMigrations} runs the lint over the
 * real directory, which is what actually blocks a bad PR. But no shipped migration after the splice point mutates a
 * trace table today, so that test never reaches the interesting branch — on its own it would pass regardless of what
 * the lint's patterns did, which is precisely the trap a guard like this falls into. {@link LintDecision} therefore
 * exercises {@link TracesMigrationPreconditionLint} directly against inline migrations, one per way of getting it wrong.
 *
 * <p><b>Coverage starts strictly after {@link #CUTOVER_SPLICE_POINT}.</b> Shipped migrations are append-only and never
 * edited, so the ones that mutate {@code traces} unguarded ({@code 000091_add_id_at_to_traces},
 * {@code 000113_add_id_bloom_filter_index_to_traces}, …) must stay exactly as they are: they predate the cutover and are
 * correct for the installs that ran them. Rather than carry a grandfather list that someone would eventually append to,
 * the lint starts after the shadow-table migration — the first point at which an install can already be post-cutover,
 * and therefore the first point at which two branches become mandatory. That boundary needs no maintenance and cannot
 * be widened by accident.
 *
 * <p>The playbook this enforces is {@code apps/opik-backend/docs/traces-schema-ddl.md}.
 */
@DisplayName("Traces Migration Precondition Lint")
class TracesMigrationPreconditionLintTest {

    /** Relative to the Maven module directory ({@code apps/opik-backend}), the working directory locally and in CI. */
    private static final Path MIGRATIONS = Path.of("src/main/resources/liquibase/db-app-analytics/migrations");

    /**
     * The last migration that runs before an install can have cut over. Everything after it must tolerate both
     * topologies; everything up to and including it ran pre-cutover only. Matches the splice point the post-cutover
     * gate uses.
     */
    private static final String CUTOVER_SPLICE_POINT = "000114_recreate_traces_local_v2_id_at_datetime64.sql";

    @Nested
    @DisplayName("shipped migrations")
    class ShippedMigrations {

        @Test
        @DisplayName("every trace-mutating migration after the splice point is topology-aware")
        void everyTraceMutatingMigrationAfterTheSplicePointIsTopologyAware() throws IOException {
            var migrations = migrationsAfterTheSplicePoint();

            var problems = new ArrayList<String>();
            for (var migration : migrations) {
                problems.addAll(TracesMigrationPreconditionLint.problems(
                        migration.getFileName().toString(), Files.readString(migration)));
            }

            assertThat(problems)
                    .as("""
                            a migration that mutates `traces` or `traces_local` must ship as two complementary \
                            changesets guarded on whether traces_local exists. See docs/traces-schema-ddl.md and the \
                            reference migration it links.\
                            """)
                    .isEmpty();
        }

        /**
         * The directory scan is only meaningful if it is actually looking at files. Zero would mean the splice point or
         * the directory moved and the scan above had quietly become a no-op.
         */
        @Test
        @DisplayName("the scan covers the migrations added after the splice point")
        void theScanCoversMigrationsAfterTheSplicePoint() throws IOException {
            assertThat(migrationsAfterTheSplicePoint())
                    .as("migrations must exist after %s; if none do, this lint is scanning nothing",
                            CUTOVER_SPLICE_POINT)
                    .isNotEmpty();
        }

        /** Migrations strictly after the splice point, in the lexicographic order the changelog applies them. */
        private List<Path> migrationsAfterTheSplicePoint() throws IOException {
            assertThat(MIGRATIONS)
                    .as("the migrations directory must be readable at %s (relative to apps/opik-backend); if it moved, "
                            + "update this lint rather than dropping it", MIGRATIONS)
                    .isDirectory();

            // Recursive on purpose: Liquibase's `includeAll path="migrations/"` descends into subdirectories, so a
            // non-recursive listing would apply nested migrations in production while never linting them.
            try (var files = Files.walk(MIGRATIONS)) {
                var ordered = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();

                var splicePoint = ordered.stream()
                        .filter(path -> path.getFileName().toString().equals(CUTOVER_SPLICE_POINT))
                        .findFirst();
                assertThat(splicePoint)
                        .as("the splice point %s must exist in %s", CUTOVER_SPLICE_POINT, MIGRATIONS)
                        .isPresent();

                return ordered.subList(ordered.indexOf(splicePoint.orElseThrow()) + 1, ordered.size());
            }
        }
    }

    /**
     * The lint's decision, exercised directly. Each case is a way a real migration could be written; the point is that
     * changing a pattern in {@link TracesMigrationPreconditionLint} breaks one of these rather than silently accepting
     * an unguarded migration.
     */
    @Nested
    @DisplayName("lint decision")
    class LintDecision {

        private static final String GUARD_PRE = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
                """;
        private static final String GUARD_POST = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
                """;

        @Test
        @DisplayName("accepts the guarded two-branch pattern")
        void acceptsTheGuardedTwoBranchPattern() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    """ + GUARD_PRE
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                            --changeset opik:000200_add_foo_post_cutover
                            """
                    + GUARD_POST
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                            """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql)).isEmpty();
        }

        /**
         * The finding a file-level search cannot make: the guard is present in the file, but on a different changeset
         * than the one doing the mutating, so the mutation itself runs unconditionally on both topologies.
         */
        @Test
        @DisplayName("rejects a mutation guarded only by a different changeset")
        void rejectsAMutationGuardedByADifferentChangeset() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_guarded_but_unrelated
                    """ + GUARD_PRE
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                            --changeset opik:000200_unguarded_traces_change
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                            """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_mixed.sql", sql))
                    .singleElement(STRING)
                    .contains("000200_unguarded_traces_change");
        }

        /**
         * A single guarded branch is a valid guard and still wrong: post-cutover it is recorded {@code MARK_RAN}, so a
         * cut-over install never receives the change while its ledger says it did.
         */
        @Test
        @DisplayName("rejects a single branch even when correctly guarded")
        void rejectsASingleBranch() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    """ + GUARD_PRE
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                            """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("BOTH complementary branches");
        }

        /** Header prose is before the first changeset, so it can never satisfy the guard for a later mutation. */
        @Test
        @DisplayName("rejects a mutation whose only guard is header prose")
        void rejectsAMutationGuardedOnlyByHeaderProse() {
            var sql = """
                    --liquibase formatted sql
                    -- This migration would normally need:
                    --   --preconditions onFail:MARK_RAN onError:HALT
                    --   --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE name = 'traces_local'
                    -- but it is written unguarded.
                    --changeset opik:000200_add_foo
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /** Prose inside the changeset that merely discusses a trace mutation must not trip the lint. */
        @Test
        @DisplayName("ignores commented-out and discussed mutations")
        void ignoresCommentedOutMutations() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_spans
                    -- Unlike ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces, this one only touches spans.
                    -- ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo_to_spans.sql", sql)).isEmpty();
        }

        /**
         * Every shape of unguarded trace mutation the classifier must recognise, in one place rather than one test per
         * statement kind.
         * <p>
         * The qualified and quoted forms are regressions: the classifier previously matched only a bare name optionally
         * prefixed by {@code ${ANALYTICS_DB_DATABASE_NAME}.}, so {@code analytics.traces} and {@code `traces`} were not
         * seen as mutations at all and the migration passed the lint untouched. The structural kinds
         * ({@code RENAME}, {@code DROP}, {@code EXCHANGE}) are there because a migration should not be doing them during
         * the mixed-fleet window — but if one tries, the lint must not be the thing that lets it through.
         */
        static Stream<Arguments> unguardedMutations() {
            return Stream.of(
                    Arguments.of("bare traces",
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("the shard",
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ADD INDEX IF NOT EXISTS idx_foo name TYPE set(0) GRANULARITY 1;"),
                    Arguments.of("the shadow",
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 MODIFY COLUMN name String CODEC(ZSTD(3));"),
                    Arguments.of("unqualified", "ALTER TABLE traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("another database qualifier",
                            "ALTER TABLE analytics.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("backtick-quoted",
                            "ALTER TABLE `traces` ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("quoted and qualified",
                            "ALTER TABLE `analytics`.`traces_local` ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("a delete",
                            "DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.traces WHERE workspace_id = 'x';"),
                    Arguments.of("a rename",
                            "RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_old;"),
                    Arguments.of("a drop", "DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local;"),
                    Arguments.of("an exchange",
                            "EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2;"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unguardedMutations")
        @DisplayName("rejects an unguarded trace mutation in any form")
        void rejectsUnguardedMutations(String description, String statement) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_unguarded
                    """ + statement + "\n";

            assertThat(TracesMigrationPreconditionLint.problems("000200_unguarded.sql", sql))
                    .as("%s must be recognised as a trace mutation and rejected", description)
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /**
         * The guard must actually interrogate the topology. Requiring only an expected result and the word
         * {@code traces_local} somewhere on the line accepted a constant — {@code SELECT 0 -- traces_local} — which
         * evaluates the same on both topologies and so guards nothing at all.
         */
        @Test
        @DisplayName("rejects a guard whose sqlCheck does not query system.tables")
        void rejectsAGuardThatDoesNotQueryTheTopology() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    --preconditions onFail:MARK_RAN onError:HALT
                    --precondition-sql-check expectedResult:0 SELECT 0 -- traces_local
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /**
         * Two changes guarded to the same topology and one to the other: the branch <i>set</i> is still {@code {0, 1}},
         * so a set-based check reads it as complementary, while the pre-cutover topology in fact receives a change the
         * post-cutover one never does.
         */
        @Test
        @DisplayName("rejects branches that do not pair up")
        void rejectsUnpairedBranches() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    """ + GUARD_PRE
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                            --changeset opik:000200_add_bar_pre_cutover
                            """
                    + GUARD_PRE
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS bar String DEFAULT '';

                            --changeset opik:000200_add_foo_post_cutover
                            """
                    + GUARD_POST
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                            """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("must pair up");
        }

        /**
         * A block-commented mutation is not a mutation. Rejecting one would be a false positive — the kind that teaches
         * people the lint is noise and to work around it.
         */
        @Test
        @DisplayName("ignores a block-commented mutation")
        void ignoresABlockCommentedMutation() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_spans
                    /* Superseded, kept for context:
                       ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                     */
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo_to_spans.sql", sql)).isEmpty();
        }

        /**
         * A header Liquibase would not accept means the statements below it fold into the previous changeset, where
         * they inherit a guard written for something else. The lint must not accept that arrangement quietly.
         */
        @Test
        @DisplayName("rejects a malformed changeset header that would fold its statements into the previous changeset")
        void rejectsAMalformedChangesetHeader() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    """ + GUARD_PRE
                    + """
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                            --changeset
                            ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS bar String DEFAULT '';
                            """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("does not name an author:id");
        }

        /**
         * The silent-failure case: {@code --changeset a:b id:c} is already used by 26 shipped changesets, and a header
         * pattern anchored after {@code author:id} matched none of them. With no changeset parsed every check was
         * skipped, so the migration passed unexamined — the worst possible outcome for a guard.
         */
        @Test
        @DisplayName("still parses a changeset header carrying Liquibase attributes")
        void parsesAttributedChangesetHeaders() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo id:add-foo runOnChange:false
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("000200_add_foo")
                    .contains("without a complete topology guard");
        }

        /** And an unparseable header must fail rather than pass, so "checked nothing" can never read as "all clear". */
        @Test
        @DisplayName("rejects a trace mutation whose changeset header cannot be parsed")
        void rejectsAnUnparseableChangesetHeader() {
            var sql = """
                    --liquibase formatted sql
                    -- changesets go here
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("no `--changeset` header could be parsed");
        }

        /**
         * {@code onError:HALT} is one of the four load-bearing details the playbook names: without it a precondition
         * that cannot be evaluated falls through to a guessed topology instead of stopping.
         */
        @Test
        @DisplayName("rejects a guard missing onError:HALT")
        void rejectsAGuardMissingOnErrorHalt() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    --preconditions onFail:MARK_RAN
                    --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE name = 'traces_local'
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        @Test
        @DisplayName("ignores a migration that only reads traces")
        void ignoresAMigrationThatOnlyReadsTraces() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_seed_summary
                    INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.trace_summary SELECT workspace_id, count() FROM ${ANALYTICS_DB_DATABASE_NAME}.traces GROUP BY workspace_id;
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_seed_summary.sql", sql)).isEmpty();
        }

    }
}
