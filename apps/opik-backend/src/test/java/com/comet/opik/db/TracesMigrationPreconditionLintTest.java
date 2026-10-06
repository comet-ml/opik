package com.comet.opik.db;

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
 * up ClickHouse and ZooKeeper, apply the whole changelog twice, and report the <i>consequence</i> (a column missing from
 * the shadow) rather than the cause. This runs in milliseconds with no container and names the file and changeset, so
 * the common mistake is caught at the point it was made. It is a fast path in front of the gates, not a replacement: it
 * checks that a guard is present, and only the gates can check the DDL is actually right.
 *
 * <p><b>Two kinds of test, and the second is the load-bearing one.</b> {@link ShippedMigrations} runs the lint over the
 * real directory, which is what actually blocks a bad PR. But no shipped migration after the splice point mutates a
 * trace table today, so that test never reaches the interesting branch — on its own it would pass regardless of what
 * the lint's patterns did, which is precisely the trap a guard like this falls into. {@link LintDecision} therefore
 * exercises {@link CutoverMigrationPreconditionLint} directly against inline migrations, one per way of getting it wrong.
 *
 * <p><b>Coverage starts strictly after {@link #CUTOVER_SPLICE_POINT}.</b> Shipped migrations are append-only and never
 * edited, so the ones that mutate {@code traces} unguarded ({@code 000091_add_id_at_to_traces},
 * {@code 000113_add_id_bloom_filter_index_to_traces}, …) must stay exactly as they are: they predate the cutover and are
 * correct for the installs that ran them. Rather than carry a grandfather list that someone would eventually append to,
 * the lint starts after the shadow-table migration — the first point at which an install can already be post-cutover,
 * and therefore the first point at which two branches become mandatory. That boundary needs no maintenance and cannot
 * be widened by accident.
 *
 * <p>The playbook this enforces is {@code apps/opik-backend/docs/cutover-table-schema-ddl.md}.
 */
class TracesMigrationPreconditionLintTest {

    private static final CutoverMigrationPreconditionLint LINT = CutoverMigrationPreconditionLint.TRACES;

    /** Relative to the Maven module directory ({@code apps/opik-backend}), the working directory locally and in CI. */
    private static final Path MIGRATIONS = Path.of("src/main/resources/liquibase/db-app-analytics/migrations");

    /**
     * The last migration that runs before an install can have cut over. Everything after it must tolerate both
     * topologies; everything up to and including it ran pre-cutover only. Declared on the lint constant, so it is the
     * same value the post-cutover gate splices at.
     */
    private static final String CUTOVER_SPLICE_POINT = LINT.getCutoverSplicePoint();

    @Nested
    class ShippedMigrations {

        @Test
        void everyTraceMutatingMigrationAfterTheSplicePointIsTopologyAware() throws IOException {
            var migrations = migrationsAfterTheSplicePoint();

            var problems = new ArrayList<String>();
            for (var migration : migrations) {
                problems.addAll(LINT.problems(
                        migration.getFileName().toString(), Files.readString(migration)));
            }

            assertThat(problems)
                    .as("""
                            a migration that mutates `traces` or `traces_local` must ship as two complementary \
                            changesets guarded on whether traces_local exists. See docs/cutover-table-schema-ddl.md and the \
                            reference migration it links.\
                            """)
                    .isEmpty();
        }

        /**
         * The directory scan is only meaningful if it is actually looking at files. Zero would mean the splice point or
         * the directory moved and the scan above had quietly become a no-op.
         */
        @Test
        void theScanCoversMigrationsAfterTheSplicePoint() throws IOException {
            assertThat(migrationsAfterTheSplicePoint())
                    .as("migrations must exist after %s; if none do, this lint is scanning nothing",
                            CUTOVER_SPLICE_POINT)
                    .isNotEmpty();
        }

        /** Migrations strictly after the splice point, in the lexicographic order the changelog applies them. */
        private List<Path> migrationsAfterTheSplicePoint() throws IOException {
            assertThat(MIGRATIONS)
                    .as("""
                            the migrations directory must be readable at %s (relative to apps/opik-backend); if it \
                            moved, update this lint rather than dropping it\
                            """, MIGRATIONS)
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
     * changing a pattern in {@link CutoverMigrationPreconditionLint} breaks one of these rather than silently accepting
     * an unguarded migration.
     */
    @Nested
    class LintDecision {

        /** Spliced into the fixtures below with {@code %s}, so each reads as the one migration file it represents. */
        private static final String GUARD_PRE = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'""";
        private static final String GUARD_POST = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'""";

        @Test
        void acceptsTheGuardedTwoBranchPattern() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_foo_post_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(GUARD_PRE, GUARD_POST);

            assertThat(LINT.problems("000200_add_foo.sql", sql)).isEmpty();
        }

        /**
         * The finding a file-level search cannot make: the guard is present in the file, but on a different changeset
         * than the one performing the mutation, so the mutation itself runs unconditionally on both topologies.
         */
        @Test
        void rejectsAMutationGuardedByADifferentChangeset() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_guarded_but_unrelated
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_unguarded_traces_change
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(GUARD_PRE);

            assertThat(LINT.problems("000200_mixed.sql", sql))
                    .singleElement(STRING)
                    .contains("000200_unguarded_traces_change");
        }

        /**
         * A single guarded branch is a valid guard and still wrong: post-cutover it is recorded {@code MARK_RAN}, so a
         * cut-over install never receives the change while its ledger says it did.
         */
        @Test
        void rejectsASingleBranch() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(GUARD_PRE);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("BOTH complementary branches");
        }

        /** Header prose is before the first changeset, so it can never satisfy the guard for a later mutation. */
        @Test
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

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /** Prose inside the changeset that merely discusses a trace mutation must not trip the lint. */
        @Test
        void ignoresCommentedOutMutations() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_spans
                    -- Unlike ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces, this one only touches spans.
                    -- ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo_to_spans.sql", sql)).isEmpty();
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
                    Arguments.of("an insert into the shadow, which post-cutover does not exist",
                            "INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 SELECT * FROM ${ANALYTICS_DB_DATABASE_NAME}.traces;"),
                    Arguments.of("an insert into the shard, which pre-cutover does not exist",
                            "INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.traces_local SELECT * FROM ${ANALYTICS_DB_DATABASE_NAME}.traces;"));
        }

        /**
         * Statements no migration may use on these tables at all, guarded or not. A guard would not save them:
         * post-cutover the {@code Distributed} wrapper rejects row mutations outright, and a structural change rides
         * the successor's table definition rather than an in-window {@code ALTER}. Telling an author to "add a
         * topology guard" to a {@code DROP TABLE} would be actively bad advice, which is why these get their own
         * verdict.
         *
         * <p>The {@code IF EXISTS} forms are here because the keyword sits between the statement and the table name:
         * a pattern that goes straight from one to the other sees no mutation at all.
         */
        static Stream<Arguments> destructiveMutations() {
            return Stream.of(
                    Arguments.of("a delete",
                            "DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.traces WHERE workspace_id = 'x';"),
                    Arguments.of("a rename",
                            "RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_old;"),
                    Arguments.of("a drop", "DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local;"),
                    Arguments.of("a drop guarded by IF EXISTS",
                            "DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2;"),
                    Arguments.of("a truncate guarded by IF EXISTS",
                            "TRUNCATE TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces;"),
                    Arguments.of("a detach guarded by IF EXISTS",
                            "DETACH TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces;"),
                    Arguments.of("an optimize",
                            "OPTIMIZE TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces FINAL;"),
                    Arguments.of("an exchange",
                            "EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2;"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("destructiveMutations")
        void rejectsADestructiveMutationEvenWhenGuarded(String description, String statement) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_destructive_pre_cutover
                    %s
                    """.formatted(GUARD_PRE) + statement + "\n";

            assertThat(LINT.problems("000200_destructive.sql", sql))
                    .as("%s must be rejected outright, not merely asked for a guard", description)
                    .singleElement(STRING)
                    .contains("No migration may do that during the mixed-fleet window");
        }

        /**
         * The complement: an insert into the <i>live</i> name is correct on both topologies, because the
         * {@code Distributed} wrapper accepts inserts and routes them to the shard. Requiring a guard there would be
         * ceremony, so only the single-topology tables above are flagged.
         */
        @Test
        void ignoresAnInsertIntoTheLiveTable() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_seed
                    INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.traces (id) SELECT id FROM ${ANALYTICS_DB_DATABASE_NAME}.staging;
                    """;

            assertThat(LINT.problems("000200_seed.sql", sql)).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unguardedMutations")
        void rejectsUnguardedMutations(String description, String statement) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_unguarded
                    """ + statement + "\n";

            assertThat(LINT.problems("000200_unguarded.sql", sql))
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
        void rejectsAGuardThatDoesNotQueryTheTopology() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    --preconditions onFail:MARK_RAN onError:HALT
                    --precondition-sql-check expectedResult:0 SELECT 0 -- traces_local
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /**
         * Two changes guarded to the same topology and one to the other: the branch <i>set</i> is still {@code {0, 1}},
         * so a set-based check reads it as complementary, while the pre-cutover topology in fact receives a change the
         * post-cutover one never does.
         */
        @Test
        void rejectsUnpairedBranches() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_bar_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS bar String DEFAULT '';

                    --changeset opik:000200_add_foo_post_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(GUARD_PRE, GUARD_PRE, GUARD_POST);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("must pair up");
        }

        /**
         * A branch key the check can never return. The sqlCheck counts rows in {@code system.tables} for one table
         * name, so 0 and 1 are the only answers; anything else leaves the changeset recorded {@code MARK_RAN} on every
         * install, with its statements never running anywhere.
         *
         * <p>The typo'd <i>pair</i> is already caught by the both-branches rule, which reports "found only [0, 2]".
         * This is the case it misses: a correct pair plus a stray third changeset, where the branch set still contains
         * 0 and 1 and the odd one out goes unexamined.
         */
        @Test
        void rejectsABranchKeyTheCheckCanNeverReturn() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ADD COLUMN IF NOT EXISTS foo String;

                    --changeset opik:000200_post_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String;

                    --changeset opik:000200_typo
                    --preconditions onFail:MARK_RAN onError:HALT
                    --precondition-sql-check expectedResult:2 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS never_lands String;
                    """
                    .formatted(GUARD_PRE, GUARD_POST);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("can never return")
                    // Which changeset and which value, not just the verdict: two of the three changesets here are
                    // correct, so a diagnostic naming the wrong one would otherwise read as a pass.
                    .contains("000200_typo")
                    .contains("expectedResult:2");
        }

        /**
         * A block-commented mutation is not a mutation. Rejecting one would be a false positive — the kind that teaches
         * people the lint is noise and to work around it.
         */
        @Test
        void ignoresABlockCommentedMutation() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_spans
                    /* Superseded, kept for context:
                       ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                     */
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo_to_spans.sql", sql)).isEmpty();
        }

        /**
         * A header Liquibase would not accept means the statements below it fold into the previous changeset, where
         * they inherit a guard written for something else. The lint must not accept that arrangement quietly.
         */
        @Test
        void rejectsAMalformedChangesetHeader() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS bar String DEFAULT '';
                    """
                    .formatted(GUARD_PRE);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("does not name an author:id");
        }

        /**
         * A header naming a token that is not {@code author:id} is the same hazard as one naming nothing, and it runs
         * in the direction that passes: this lint splits on it and sees a correctly paired pre/post migration, while
         * Liquibase folds the second branch — guard and all — into the first, where the two {@code sqlCheck}s are ANDed
         * and neither branch ever runs.
         */
        @Test
        void rejectsAChangesetHeaderThatDoesNotNameAnAuthorAndId() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ADD COLUMN IF NOT EXISTS foo String;

                    --changeset bogus
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String;
                    """.formatted(GUARD_PRE, GUARD_POST);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("does not name an author:id");
        }

        /**
         * The silent-failure case: {@code --changeset a:b id:c} is a shape many shipped changesets already use, and a
         * header pattern anchored after {@code author:id} matches none of them. With no changeset parsed every check
         * below is skipped and the migration passes unexamined — the worst possible outcome for a guard.
         */
        @Test
        void parsesAttributedChangesetHeaders() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo id:add-foo runOnChange:false
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("000200_add_foo")
                    .contains("without a complete topology guard");
        }

        /** And an unparseable header must fail rather than pass, so "checked nothing" can never read as "all clear". */
        @Test
        void rejectsAnUnparseableChangesetHeader() {
            var sql = """
                    --liquibase formatted sql
                    -- changesets go here
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("no `--changeset` header could be parsed");
        }

        static Stream<Arguments> incompleteGuardDirectives() {
            return Stream.of(
                    Arguments.of("onError:HALT missing, so a precondition that cannot be evaluated falls through to a "
                            + "guessed topology instead of stopping", "--preconditions onFail:MARK_RAN"),
                    Arguments.of("onFail:MARK_RAN missing, so the skipped branch is left unrecorded and retried "
                            + "against the wrong topology on a later startup", "--preconditions onError:HALT"),
                    Arguments.of("no --preconditions line at all, leaving the sqlCheck inert",
                            "-- the preconditions line belongs here"));
        }

        /**
         * Both directives the playbook names are load-bearing, and {@code guarded} requires both, so each has to be
         * shown to fail on its own — a check that only ever saw one missing would not notice the other being dropped.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("incompleteGuardDirectives")
        void rejectsAnIncompleteGuard(String description, String preconditions) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    %s
                    --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE name = 'traces_local'
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(preconditions);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .as("%s must be rejected", description)
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        @Test
        void ignoresAMigrationThatOnlyReadsTraces() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_seed_summary
                    INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.trace_summary SELECT workspace_id, count() FROM ${ANALYTICS_DB_DATABASE_NAME}.traces GROUP BY workspace_id;
                    """;

            assertThat(LINT.problems("000200_seed_summary.sql", sql)).isEmpty();
        }

        /**
         * The reference fixture is the shape the playbook tells people to copy, so it must pass the lint that enforces
         * the playbook. If the two ever disagree, one of them is wrong and a migration writer following the reference
         * would be blocked by CI — which is how a guard loses its authority. The container gates prove the reference
         * <i>applies</i> correctly on both topologies; only this proves the lint agrees it is well-formed.
         */
        @Test
        void acceptsTheShippedReferenceFixture() throws IOException {
            var reference = Path
                    .of("src/test/resources/liquibase/traces-ddl-reference/migrations/reference_topology_aware_change.sql");
            assertThat(reference)
                    .as("the traces reference migration must be readable at %s (relative to apps/opik-backend)",
                            reference)
                    .isRegularFile();

            assertThat(LINT.problems(reference.getFileName().toString(), Files.readString(reference)))
                    .as("the reference the playbook tells people to copy must satisfy the lint that enforces it")
                    .isEmpty();
        }

        /** And the negative control must fail it, or the fixture is not a control at all. */
        @Test
        void rejectsTheShippedUnguardedFixture() throws IOException {
            var unguarded = Path
                    .of("src/test/resources/liquibase/traces-ddl-unguarded/migrations/unguarded_traces_change.sql");
            assertThat(unguarded)
                    .as("the traces negative control must be readable at %s (relative to apps/opik-backend)", unguarded)
                    .isRegularFile();

            assertThat(LINT.problems(unguarded.getFileName().toString(), Files.readString(unguarded)))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

    }
}
