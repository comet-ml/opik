package com.comet.opik.db;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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
 * The static lint over the shipped ClickHouse migrations for the span family: a migration that mutates {@code spans}
 * must be topology-aware. The spans counterpart of {@link TracesMigrationPreconditionLintTest}.
 *
 * <p><b>Why, when the parity gates already cover this.</b> The gates are correct but expensive and indirect — they spin
 * up ClickHouse and ZooKeeper, apply the whole changelog twice, and report the <i>consequence</i> (a column missing from
 * the shadow) rather than the cause. This runs in milliseconds with no container and names the file and changeset, so
 * the common mistake is caught at the point it was made. It is a fast path in front of the gates, not a replacement: it
 * checks that a guard is present, and only the gates can check the DDL is actually right.
 *
 * <p><b>Two kinds of test, and the second is the load-bearing one.</b> {@link ShippedMigrations} runs the lint over the
 * real directory, which is what actually blocks a bad PR. But no shipped migration after the splice point mutates a
 * span table today, so that test never reaches the interesting branch — on its own it would pass regardless of what
 * the lint's patterns did, which is precisely the trap a guard like this falls into. {@link LintDecision} therefore
 * exercises {@link CutoverMigrationPreconditionLint#SPANS} directly against inline migrations, one per way of getting
 * it wrong.
 *
 * <p><b>Both families' suites regression-test the shared lint, each against its own tables.</b> That is what keeps one
 * implementation safe to share: a suite wired to the wrong constant fails on its very first case rather than passing
 * while checking a family it was never pointed at.
 * {@link LintDecision#eachFamilysLintIgnoresTheOtherFamilysTables} makes the same point from the other direction —
 * neither constant may see the other family's tables at all.
 *
 * <p><b>Coverage starts strictly after the spans splice point.</b> Shipped migrations are append-only and never edited,
 * so the ones that mutate {@code spans} unguarded ({@code 000105_add_id_at_to_spans},
 * {@code 000097_add_minmax_indexes_experiment_refs_lookup}, …) must stay exactly as they are: they predate the cutover
 * and are correct for the installs that ran them. Rather than carry a grandfather list that someone would eventually
 * append to, the lint starts after the last migration that shapes the shadow table — the first point at which an
 * install can already be post-cutover, and therefore the first point at which two branches become mandatory. That
 * boundary needs no maintenance and cannot be widened by accident.
 *
 * <p>The playbook this enforces is {@code apps/opik-backend/docs/cutover-table-schema-ddl.md}.
 */
class SpansMigrationPreconditionLintTest {

    private static final CutoverMigrationPreconditionLint LINT = CutoverMigrationPreconditionLint.SPANS;

    /** Relative to the Maven module directory ({@code apps/opik-backend}), the working directory locally and in CI. */
    private static final Path MIGRATIONS = Path.of("src/main/resources/liquibase/db-app-analytics/migrations");

    /**
     * The last migration that runs before an install can have cut over. Everything after it must tolerate both
     * topologies; everything up to and including it ran pre-cutover only. Declared on the lint constant, so it is the
     * same value {@link SpansSchemaParityPostCutoverTest} splices at.
     */
    private static final String CUTOVER_SPLICE_POINT = LINT.getCutoverSplicePoint();

    @Nested
    class ShippedMigrations {

        @Test
        void everySpanMutatingMigrationAfterTheSplicePointIsTopologyAware() throws IOException {
            var migrations = migrationsAfterTheSplicePoint();

            var problems = new ArrayList<String>();
            for (var migration : migrations) {
                problems.addAll(LINT.problems(migration.getFileName().toString(), Files.readString(migration)));
            }

            assertThat(problems)
                    .as("""
                            a migration that mutates `spans` or `spans_local` must ship as two complementary \
                            changesets guarded on whether spans_local exists. See docs/cutover-table-schema-ddl.md and \
                            the reference migration it links.\
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
        private static final String GUARD_CHECK_SPANS = "--precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'";
        private static final String GUARD_CHECK_TRACES = "--precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'";

        private static final String GUARD_PRE = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'""";
        private static final String GUARD_POST = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'""";

        /** The traces halves of the per-family split, so the accepted remedy can be asserted from this suite too. */
        private static final String TRACES_GUARD_PRE = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'""";
        private static final String TRACES_GUARD_POST = """
                --preconditions onFail:MARK_RAN onError:HALT
                --precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'""";

        @Test
        void acceptsTheGuardedTwoBranchPattern() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_foo_post_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_unguarded_spans_change
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(GUARD_PRE);

            assertThat(LINT.problems("000200_mixed.sql", sql))
                    .singleElement(STRING)
                    .contains("000200_unguarded_spans_change");
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
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
                    --   --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE name = 'spans_local'
                    -- but it is written unguarded.
                    --changeset opik:000200_add_foo
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /** Prose inside the changeset that merely discusses a span mutation must not trip the lint. */
        @Test
        void ignoresCommentedOutMutations() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_trace_threads
                    -- Unlike ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans, this one only touches trace_threads.
                    -- ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo_to_trace_threads.sql", sql)).isEmpty();
        }

        /**
         * Every shape of unguarded span mutation the classifier must recognise, in one place rather than one test per
         * statement kind.
         * <p>
         * The qualified and quoted forms matter: a classifier matching only a bare name optionally prefixed by
         * {@code ${ANALYTICS_DB_DATABASE_NAME}.} would not see {@code analytics.spans} or {@code `spans`} as mutations
         * at all, and the migration would pass the lint untouched. The structural kinds ({@code RENAME}, {@code DROP},
         * {@code EXCHANGE}) are there because a migration should not be doing them during the mixed-fleet window — but
         * if one tries, the lint must not be the thing that lets it through.
         */
        static Stream<Arguments> unguardedMutations() {
            return Stream.of(
                    Arguments.of("bare spans",
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("the shard",
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD INDEX IF NOT EXISTS idx_foo name TYPE set(0) GRANULARITY 1;"),
                    Arguments.of("the shadow",
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 MODIFY COLUMN name String CODEC(ZSTD(3));"),
                    Arguments.of("unqualified", "ALTER TABLE spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("another database qualifier",
                            "ALTER TABLE analytics.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("backtick-quoted",
                            "ALTER TABLE `spans` ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("quoted and qualified",
                            "ALTER TABLE `analytics`.`spans_local` ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("an insert into the shadow, which post-cutover does not exist",
                            "INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 SELECT * FROM ${ANALYTICS_DB_DATABASE_NAME}.spans;"),
                    Arguments.of("an insert into the shard, which pre-cutover does not exist",
                            "INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.spans_local SELECT * FROM ${ANALYTICS_DB_DATABASE_NAME}.spans;"));
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
                            "DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.spans WHERE workspace_id = 'x';"),
                    Arguments.of("a rename",
                            "RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans TO ${ANALYTICS_DB_DATABASE_NAME}.spans_old;"),
                    Arguments.of("a drop", "DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local;"),
                    Arguments.of("a drop guarded by IF EXISTS",
                            "DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2;"),
                    Arguments.of("a truncate guarded by IF EXISTS",
                            "TRUNCATE TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans;"),
                    Arguments.of("a detach guarded by IF EXISTS",
                            "DETACH TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans;"),
                    Arguments.of("an optimize",
                            "OPTIMIZE TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans FINAL;"),
                    Arguments.of("an exchange",
                            "EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.spans AND ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2;"));
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
                    INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.spans (id) SELECT id FROM ${ANALYTICS_DB_DATABASE_NAME}.staging;
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
                    .as("%s must be recognised as a span mutation and rejected", description)
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        static Stream<Arguments> crossFamilyMutations() {
            return Stream.of(
                    Arguments.of("a traces mutation seen by the spans lint", CutoverMigrationPreconditionLint.SPANS,
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("a traces_local mutation seen by the spans lint",
                            CutoverMigrationPreconditionLint.SPANS,
                            "DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.traces_local WHERE workspace_id = 'x';"),
                    Arguments.of("a spans mutation seen by the traces lint", CutoverMigrationPreconditionLint.TRACES,
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';"),
                    Arguments.of("a spans_local_v2 mutation seen by the traces lint",
                            CutoverMigrationPreconditionLint.TRACES,
                            "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 MODIFY COLUMN name String;"));
        }

        /**
         * Neither family's constant may see the other's tables. The two are one implementation with two sets of names,
         * so a mis-built pattern that matched {@code spans*} from the traces constant would make every span migration
         * fail the traces lint — and, worse, a pattern that matched neither would make both lints pass everything.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("crossFamilyMutations")
        void eachFamilysLintIgnoresTheOtherFamilysTables(String description,
                CutoverMigrationPreconditionLint lint, String statement) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_unguarded
                    """ + statement + "\n";

            assertThat(lint.problems("000200_unguarded.sql", sql))
                    .as("%s is not a %s-table mutation, so the %s lint must ignore it", description,
                            lint.getFamilyNoun(), lint.getFamilyNoun())
                    .isEmpty();
        }

        /**
         * A changeset mutating both families cannot be guarded correctly, however carefully it is written, because the
         * families cut over independently and one changeset carries one branch condition. Both lints must reject it.
         *
         * <p>This is the case the single-family message would otherwise steer an author into: told to add a
         * {@code sqlCheck} for the missing family "on the changeset itself", they produce two ANDed preconditions,
         * which Liquibase evaluates false on any install where the families differ — both changesets recorded
         * {@code MARK_RAN} and the change silently never applied. Before this check, that shape passed both lints.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("combinedFamilyGuards")
        void rejectsAChangesetMutatingBothFamilies(String description, String guards) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    --preconditions onFail:MARK_RAN onError:HALT
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """.formatted(guards);

            for (var lint : CutoverMigrationPreconditionLint.values()) {
                assertThat(lint.problems("000200_add_foo.sql", sql))
                        .as("%s must be rejected by the %s lint", description, lint.getFamilyNoun())
                        .singleElement(STRING)
                        .contains("mutates both");
            }
        }

        static Stream<Arguments> combinedFamilyGuards() {
            return Stream.of(
                    Arguments.of("guarded on one family only", GUARD_CHECK_TRACES),
                    Arguments.of("one ANDed sqlCheck per family", GUARD_CHECK_TRACES + "\n" + GUARD_CHECK_SPANS),
                    Arguments.of("no guard at all", "-- no precondition here"));
        }

        /**
         * The remedy the message prescribes: one guarded pre/post pair per family, each keyed on its own shard. Both
         * lints must accept it, or the rule above would have no satisfiable form.
         */
        @Test
        void acceptsPerFamilyChangesetsForACombinedMigration() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_traces_pre_cutover
                    %1$s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_foo_to_traces_post_cutover
                    %2$s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_foo_to_spans_pre_cutover
                    %3$s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_foo_to_spans_post_cutover
                    %4$s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(TRACES_GUARD_PRE, TRACES_GUARD_POST, GUARD_PRE, GUARD_POST);

            for (var lint : CutoverMigrationPreconditionLint.values()) {
                assertThat(lint.problems("000200_add_foo.sql", sql))
                        .as("the per-family split must satisfy the %s lint", lint.getFamilyNoun())
                        .isEmpty();
            }
        }

        /**
         * A name that merely starts with the family's is not the family's table. {@code spans_attachments} would be
         * flagged by a prefix match, which is a false positive on a table the cutover never touches — and the kind that
         * teaches people to distrust the lint.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_attachments ADD COLUMN IF NOT EXISTS foo String;",
                "ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v3 ADD COLUMN IF NOT EXISTS foo String;",
                "INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.span_summary SELECT workspace_id, count() FROM ${ANALYTICS_DB_DATABASE_NAME}.spans GROUP BY workspace_id;",
        })
        void ignoresNeighbouringTablesAndReads(String statement) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_unrelated
                    """ + statement + "\n";

            assertThat(LINT.problems("000200_unrelated.sql", sql)).isEmpty();
        }

        /**
         * The guard must actually interrogate the topology. Requiring only an expected result and the word
         * {@code spans_local} somewhere on the line accepted a constant — {@code SELECT 0 -- spans_local} — which
         * evaluates the same on both topologies and so guards nothing at all.
         */
        @Test
        void rejectsAGuardThatDoesNotQueryTheTopology() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    --preconditions onFail:MARK_RAN onError:HALT
                    --precondition-sql-check expectedResult:0 SELECT 0 -- spans_local
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        /**
         * A guard keyed on the <i>other</i> family's shard is not a guard at all: {@code traces_local} exists or not
         * independently of whether spans has cut over, so the branch that runs is chosen by an unrelated fact.
         */
        @Test
        void rejectsAGuardKeyedOnTheOtherFamilysShard() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_pre_cutover
                    --preconditions onFail:MARK_RAN onError:HALT
                    --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset opik:000200_add_bar_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS bar String DEFAULT '';

                    --changeset opik:000200_add_foo_post_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(GUARD_PRE, GUARD_PRE, GUARD_POST);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .singleElement(STRING)
                    .contains("must pair up");
        }

        static Stream<Arguments> divergentBranches() {
            return Stream.of(
                    Arguments.of("different index names",
                            """
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD INDEX IF NOT EXISTS idx_a name TYPE set(0) GRANULARITY 1;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD INDEX IF NOT EXISTS idx_a name TYPE set(0) GRANULARITY 1;
                                    """,
                            """
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD INDEX IF NOT EXISTS idx_b name TYPE set(0) GRANULARITY 1;
                                    """),
                    Arguments.of("different column names",
                            """
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD COLUMN IF NOT EXISTS foo String;
                                    """,
                            """
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD COLUMN IF NOT EXISTS bar String;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS bar String;
                                    """),
                    Arguments.of("a column the post-cutover branch forgets entirely",
                            """
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS bar String;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD COLUMN IF NOT EXISTS foo String;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD COLUMN IF NOT EXISTS bar String;
                                    """,
                            """
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD COLUMN IF NOT EXISTS foo String;
                                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
                                    """));
        }

        /**
         * Both branches guarded, paired and keyed on the right shard, yet applying different changes. No parity gate
         * can see this: the pre-cutover gate has no shard so only ever runs the {@code expectedResult:0} branch, and
         * the post-cutover gate splices the cutover in first so only ever runs the other. The fleet ends up split —
         * an install that migrated before cutting over carries one object, one that cut over first carries the other.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("divergentBranches")
        void rejectsBranchesThatApplyDifferentChanges(String description, String preBody, String postBody) {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_change_pre_cutover
                    %s
                    %s
                    --changeset opik:000200_change_post_cutover
                    %s
                    %s
                    """.formatted(GUARD_PRE, preBody, GUARD_POST, postBody);

            assertThat(LINT.problems("000200_change.sql", sql))
                    .as("%s must be rejected", description)
                    .singleElement(STRING)
                    .contains("must apply the same change");
        }

        /**
         * The legitimate asymmetry the rule must not flag: a storage-only index reaches both tables pre-cutover but the
         * shard alone afterwards, and a read-facing column reaches the shadow pre-cutover and the wrapper after. The
         * tables named differ on purpose; the objects do not.
         */
        @Test
        void acceptsBranchesThatNameTheSameObjectsOnDifferentTables() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_change_pre_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD INDEX IF NOT EXISTS idx_foo foo TYPE set(0) GRANULARITY 1;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD INDEX IF NOT EXISTS idx_foo foo TYPE set(0) GRANULARITY 1;

                    --changeset opik:000200_change_post_cutover
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD INDEX IF NOT EXISTS idx_foo foo TYPE set(0) GRANULARITY 1;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
                    """
                    .formatted(GUARD_PRE, GUARD_POST);

            assertThat(LINT.problems("000200_change.sql", sql)).isEmpty();
        }

        /**
         * A block-commented mutation is not a mutation. Rejecting one would be a false positive — the kind that teaches
         * people the lint is noise and to work around it.
         */
        @Test
        void ignoresABlockCommentedMutation() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_add_foo_to_trace_threads
                    /* Superseded, kept for context:
                       ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                     */
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """;

            assertThat(LINT.problems("000200_add_foo_to_trace_threads.sql", sql)).isEmpty();
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

                    --changeset
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS bar String DEFAULT '';
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ADD COLUMN IF NOT EXISTS foo String;

                    --changeset bogus
                    %s
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local ADD COLUMN IF NOT EXISTS foo String;
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ADD COLUMN IF NOT EXISTS foo String;
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
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
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
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
                    --precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE name = 'spans_local'
                    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
                    """
                    .formatted(preconditions);

            assertThat(LINT.problems("000200_add_foo.sql", sql))
                    .as("%s must be rejected", description)
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }

        @Test
        void ignoresAMigrationThatOnlyReadsSpans() {
            var sql = """
                    --liquibase formatted sql
                    --changeset opik:000200_seed_summary
                    INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.span_summary SELECT workspace_id, count() FROM ${ANALYTICS_DB_DATABASE_NAME}.spans GROUP BY workspace_id;
                    """;

            assertThat(LINT.problems("000200_seed_summary.sql", sql)).isEmpty();
        }

        /**
         * The reference fixture is the shape the playbook tells people to copy, so it must pass the lint that enforces
         * the playbook. If the two ever disagree, one of them is wrong and a migration writer following the reference
         * would be blocked by CI — which is how a guard loses its authority.
         */
        @Test
        void acceptsTheShippedReferenceFixture() throws IOException {
            var reference = Path
                    .of("src/test/resources/liquibase/spans-ddl-reference/migrations/reference_topology_aware_change.sql");
            assertThat(reference)
                    .as("the spans reference migration must be readable at %s (relative to apps/opik-backend)",
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
                    .of("src/test/resources/liquibase/spans-ddl-unguarded/migrations/unguarded_spans_change.sql");
            assertThat(unguarded)
                    .as("the spans negative control must be readable at %s (relative to apps/opik-backend)", unguarded)
                    .isRegularFile();

            assertThat(LINT.problems(unguarded.getFileName().toString(), Files.readString(unguarded)))
                    .singleElement(STRING)
                    .contains("without a complete topology guard");
        }
    }
}
