package com.comet.opik.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

            try (var files = Files.list(MIGRATIONS)) {
                var ordered = files
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

        @Test
        @DisplayName("rejects an unguarded mutation")
        void rejectsAnUnguardedMutation() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("000200_add_foo")
                    .contains("without a topology guard");
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
                    .contains("without a topology guard");
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

        /** The shadow exists only pre-cutover, so a shadow-only migration is single-topology by nature. */
        @Test
        @DisplayName("ignores a shadow-only migration")
        void ignoresAShadowOnlyMigration() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_tune_shadow
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' MODIFY COLUMN name String CODEC(ZSTD(3));
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_tune_shadow.sql", sql)).isEmpty();
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

        @Test
        @DisplayName("rejects an unguarded shard mutation post-cutover")
        void rejectsAnUnguardedShardMutation() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_index_shard
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD INDEX IF NOT EXISTS idx_foo name TYPE set(0) GRANULARITY 1;
                    """;

            assertThat(TracesMigrationPreconditionLint.problems("000200_index_shard.sql", sql))
                    .singleElement(STRING)
                    .contains("without a topology guard");
        }
    }
}
