package com.comet.opik.domain;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Every {@code traces} read an experiment reaches carries the week bound of OPIK-8343.
 *
 * <p>{@code ExperimentTracesWeekBoundTest} asserts that the statements it names prune, and
 * {@code ExperimentReadPathWeekBoundTest} that they carry none on the legacy table. Neither can say anything about a
 * read that does not exist yet, and that is what this family is exposed to: a missing bound has no effect on results,
 * so a {@code traces} read added to one of these statements — or a statement added beside them — is wrong only in how
 * many partitions it opens. Nothing fails, and the cost lands on the whole query, because a statement's partition
 * count is the union over its accesses and is paid as planning before any pruning happens.
 *
 * <p><b>The condition reflects, for the reason {@link TraceMutationRoutingArchTest} gives</b>: ArchUnit works from
 * bytecode and exposes call graphs, not literals, so SQL text is unreachable through the API proper and a custom
 * {@link ArchCondition} calling {@link JavaClass#reflect()} is how that suite reaches its own constants. Reflecting
 * also reads a constant assembled at class initialisation — {@code TraceDAO#UPDATE} is one, built by concatenation —
 * which reading the source cannot do without parsing Java.
 *
 * <p>It recognises the three bound spellings in the codebase — the {@code IN}-set form this ticket uses, OPIK-8332's
 * {@code IN :id_weeks} and OPIK-8333's {@code >=} range — by the one thing they share, the week expression applied to
 * the read's own {@code id_at}.
 *
 * <p><b>Deliberately no {@code allowEmptyShould}</b>, following the same suite: selecting no class means the DAOs were
 * renamed or moved out of the package and the rule is no longer guarding anything, and the {@code init} counter covers
 * the same failure one level down — classes still selected, but none declaring the SQL this is about.
 *
 * <p><b>What it does not catch, stated so it is not mistaken for airtight.</b> It counts per statement, not per read,
 * so two reads and two bounds pass even if both bounds sit on one read. It cannot check that the relation a bound
 * derives its weeks from is the one that narrows {@code id}, which is what makes the bound a hint rather than a filter
 * — that is a reading judgement per site, recorded in {@code ExperimentDAO#addTracesPartitionedFlag}. And it says
 * nothing about render sites: the flag is added per call site, so a new site rendering a gated statement without
 * {@code addTracesPartitionedFlag} emits no bound and is caught only by naming it in the two suites above.
 *
 * <p>One statement in scope is skipped by construction, and measured rather than assumed: the rule checks 28
 * statements over 46 reads, and {@code ExperimentAggregatesDAO#GET_TRACES_DATA} is not among them. Its ids arrive
 * bound from Java, so its SQL names no experiment relation for the selection to match, and no purely textual signal
 * separates it from the trace-id-list reads in {@code TraceDAO} - one of which, the resolver's unbounded fallback, is
 * deliberately without a bound and would fail a rule widened to reach it. That statement's bound rests on
 * {@code ExperimentTracesWeekBoundTest} naming it instead.
 *
 * <p>The realistic regression — a read added to an experiment statement with no bound at all — is caught.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class ExperimentTracesWeekBoundArchTest {

    /** A read of the trace table itself: optionally database-qualified, never a {@code traces_*} sibling. */
    private static final Pattern TRACES_READ = Pattern.compile("\\b(?:FROM|JOIN)\\s+(?:\\w+\\.)?traces\\b(?!_)",
            Pattern.CASE_INSENSITIVE);

    /** The week expression applied to the read's own {@code id_at} — one per bound, whichever form spells it. */
    private static final Pattern WEEK_BOUND = Pattern.compile("toDayOfWeek\\(\\s*(?:\\w+\\.)?id_at\\b");

    /**
     * A statement reaches {@code traces} through an experiment only if it names one of these, which is also what it
     * derives the weeks from. A read with no such relation has no set to bound itself by and belongs to another
     * ticket — the project-keyed and thread-keyed paths OPIK-8343 puts out of scope.
     */
    private static final Pattern EXPERIMENT_RELATION = Pattern.compile(
            "\\bexperiment_items\\b|\\bexperiments\\b|\\boptimization", Pattern.CASE_INSENSITIVE);

    @ArchTest
    final ArchRule every_declared_experiment_traces_read_carries_a_week_bound = classes()
            .that().resideInAPackage("com.comet.opik.domain..")
            .and().haveSimpleNameContaining("DAO")
            .should(boundEveryTracesReadAnExperimentReaches());

    private ArchCondition<JavaClass> boundEveryTracesReadAnExperimentReaches() {
        return new ArchCondition<>("bound every declared traces read an experiment reaches") {

            private int statementsChecked;

            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                statementsChecked = 0;
            }

            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Field field : item.reflect().getDeclaredFields()) {
                    if (!isStringConstant(field)) {
                        continue;
                    }
                    field.setAccessible(true);
                    var sql = readConstant(field);
                    if (sql == null || !EXPERIMENT_RELATION.matcher(sql).find()) {
                        continue;
                    }
                    int reads = count(TRACES_READ, sql);
                    if (reads == 0) {
                        continue;
                    }
                    statementsChecked++;
                    int bounds = count(WEEK_BOUND, sql);
                    if (bounds < reads) {
                        events.add(SimpleConditionEvent.violated(item, """
                                %s.%s reads `traces` %d time(s) through an experiment relation but carries %d week \
                                bound(s). Every such read must bound id_at to the weeks its own id set resolves to, \
                                gated on traces_partitioned — see ExperimentDAO#addTracesPartitionedFlag for the \
                                derivation and why it cannot change results. Without it the read opens every weekly \
                                partition, and because a statement's partition count is the union over its accesses, \
                                one unbounded read costs the whole statement its pruning.\
                                """.formatted(item.getSimpleName(), field.getName(), reads, bounds)));
                    }
                }
            }

            /** A rule that stops finding anything has stopped guarding. Zero means the SQL moved, not that it is safe. */
            @Override
            public void finish(ConditionEvents events) {
                if (statementsChecked == 0) {
                    events.add(SimpleConditionEvent.violated(this,
                            "no DAO declares a traces read an experiment reaches — the SQL moved out of these "
                                    + "constants and this rule is no longer guarding anything"));
                }
            }
        };
    }

    private boolean isStringConstant(Field field) {
        return Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class;
    }

    private String readConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read SQL constant " + field.getName(), e);
        }
    }

    private int count(Pattern pattern, String text) {
        int found = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
