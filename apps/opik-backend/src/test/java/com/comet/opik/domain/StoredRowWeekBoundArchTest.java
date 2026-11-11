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
 * Every {@code traces} or {@code spans} write that reads its stored row back carries the {@code <id_weeks>} week
 * bound.
 *
 * <p>Those writes are {@code INSERT ... SELECT} statements merging the request with the latest stored row of its id.
 * Without the bound that read opens every weekly partition holding the workspace, and nothing fails: the write is
 * correct, just slower, on every call. {@code TracesReadPathWeekBoundTest} and {@code SpansReadPathWeekBoundTest} cover
 * the statements that exist; this rule covers the next one, which neither can name.
 *
 * <p><b>The condition reflects, for the reason {@link TraceMutationRoutingArchTest} gives</b>: ArchUnit exposes no
 * string values, so the SQL constants are read through {@link JavaClass#reflect()}, which also sees a constant built by
 * concatenation.
 *
 * <p>It counts per statement, not per read, and checks the slot, not that the call site sets it: a site rendering one
 * of these statements without adding {@code id_weeks} emits no bound and is caught only by the suites above.
 *
 * <p><b>Deliberately no {@code allowEmptyShould}</b>, and {@code finish} fails when no statement was checked: either
 * means the SQL moved and the rule stopped guarding anything.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class StoredRowWeekBoundArchTest {

    /** A write into {@code traces} or {@code spans}, never a {@code *_local} sibling. */
    private static final Pattern WRITE = Pattern.compile("\\bINSERT\\s+INTO\\s+(?:\\w+\\.)?(traces|spans)\\b(?!_)",
            Pattern.CASE_INSENSITIVE);

    /** The week bound on the read's own {@code id_at}. */
    private static final Pattern WEEK_BOUND = Pattern.compile("toDayOfWeek\\(\\s*(?:\\w+\\.)?id_at\\b");

    @ArchTest
    final ArchRule every_write_reading_its_stored_row_carries_a_week_bound = classes()
            .that().resideInAPackage("com.comet.opik..")
            .and().haveSimpleNameContaining("DAO")
            .should(boundEveryStoredRowRead());

    private ArchCondition<JavaClass> boundEveryStoredRowRead() {
        return new ArchCondition<>("bound every stored-row read of a traces or spans write") {

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
                    var write = sql == null ? null : WRITE.matcher(sql);
                    if (write == null || !write.find()) {
                        continue;
                    }
                    int reads = count(Pattern.compile("\\b(?:FROM|JOIN)\\s+(?:\\w+\\.)?%s\\b(?!_)"
                            .formatted(write.group(1)), Pattern.CASE_INSENSITIVE), sql);
                    if (reads == 0) {
                        continue;
                    }
                    statementsChecked++;
                    int bounds = count(WEEK_BOUND, sql);
                    if (bounds < reads) {
                        events.add(SimpleConditionEvent.violated(item, """
                                %s.%s writes `%s` and reads it back %d time(s), but carries %d week bound(s). Bound \
                                each stored-row read with <if(id_weeks)>, as the other writes do, and set it from \
                                WeeklyPartitions.weeksOf at the call site; without it the read opens every weekly \
                                partition on every write.\
                                """.formatted(item.getSimpleName(), field.getName(), write.group(1), reads, bounds)));
                    }
                }
            }

            @Override
            public void finish(ConditionEvents events) {
                if (statementsChecked == 0) {
                    events.add(SimpleConditionEvent.violated(this,
                            "no DAO declares a traces or spans write that reads its stored row back - the SQL moved"
                                    + " out of these constants and this rule is no longer guarding anything"));
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
