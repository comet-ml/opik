package com.comet.opik.domain;

import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Architectural guard making span mutation routing correct by construction, the spans counterpart of
 * {@link TraceMutationRoutingArchTest}.
 *
 * <p>Post-cutover {@code spans} is a {@code Distributed} table, which supports {@code SELECT} and {@code INSERT} but
 * <b>not</b> mutations: a lightweight {@code DELETE} returns code 36 and {@code ALTER ... DELETE} code 48. So every
 * span mutation has to target the {@code spans_local} shard once the wrap is live, and {@code spans} while it is not.
 * Getting that wrong breaks the trace-delete cascade and both retention sweeps on every cut-over install.
 *
 * <p>Rather than trust each new mutation to remember the branch, the decision is funnelled through
 * {@link SpanDAO#selectSpansMutationTable}, and these rules keep it there: the configuration flag is read in exactly
 * one place, and no declared SQL constant names a physical span table. A new mutation path cannot choose its own table
 * without failing the build.
 *
 * <p><b>The second rule reflects, because ArchUnit cannot see string values.</b> It works from bytecode and exposes
 * call graphs, not literals — {@code JavaField} has no constant accessor — so the SQL text is unreachable through the
 * API proper. A custom {@link ArchCondition} may still call {@link JavaClass#reflect()}, which is what this one does,
 * so the check lives with its sibling rather than in a separate suite. {@link SpanMutationSqlRoutingTest} keeps the one
 * part that genuinely cannot be expressed here.
 *
 * <p><b>The caller predicate is owner-scoped.</b> Matching on method name alone would let any class declaring a method
 * of that name satisfy the exemption — an {@code OtherDao#selectSpansMutationTable} reading the config directly would
 * have passed, which is precisely the second reader this rule exists to forbid.
 *
 * <p><b>Deliberately no {@code allowEmptyShould}</b>, unlike {@link SpanDeletionEventArchTest}: these rules select the
 * guarded member rather than its callers, so an empty selection means it was renamed or removed and the rule is no
 * longer guarding anything. Failing then is the point.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class SpanMutationRoutingArchTest {

    private static final String CONFIG_FLAG = "spansDistributedWrapEnabled";
    private static final String RESOLVER = "selectSpansMutationTable";

    /**
     * Exactly one code unit: the named one on the named class. Matching by name alone would exempt any class that
     * happened to declare a member of that name, which is the very thing this rule forbids.
     */
    private static DescribedPredicate<JavaCodeUnit> only(Class<?> owner, String memberName) {
        return DescribedPredicate.describe("%s.%s".formatted(owner.getSimpleName(), memberName),
                codeUnit -> codeUnit.getOwner().isEquivalentTo(owner) && codeUnit.getName().equals(memberName));
    }

    /**
     * The flag is read only where the table name is bound, so the physical table a mutation targets is decided once. A
     * second reader is a second place that can get the read/mutate split wrong — which post-cutover means a failed
     * delete on every cut-over install, and pre-cutover a delete against a table that does not exist.
     */
    @ArchTest
    static final ArchRule the_wrap_flag_is_read_only_where_the_mutation_table_is_bound = methods()
            .that().areDeclaredIn(DatabaseAnalyticsDataModelConfig.class)
            .and().haveName(CONFIG_FLAG)
            .should().onlyBeCalled().byCodeUnitsThat(only(SpanDAO.class, RESOLVER))
            .because("""
                    the sharding-readiness wrap flag must be read only by SpanDAO#selectSpansMutationTable, whose \
                    Javadoc documents what the two topologies imply for reads, mutations and migrations, so there is \
                    exactly one line to audit when the topology changes
                    """);

    /**
     * Every span mutation declared as a SQL constant must target {@code <spans_mutation_table>} rather than naming a
     * table. A call-graph rule cannot see this — a new mutation could hardcode {@code DELETE FROM spans} without
     * consulting the flag at all — so the condition reads the constants reflectively.
     */
    @ArchTest
    static final ArchRule no_declared_sql_names_a_physical_span_table = classes()
            .that().areAssignableTo(SpanDAO.class)
            .should(notNameAPhysicalSpanTableInMutationSql());

    private static ArchCondition<JavaClass> notNameAPhysicalSpanTableInMutationSql() {
        return new ArchCondition<>("declare no mutation SQL naming `spans` or `spans_local`") {

            @Override
            public void check(JavaClass item, ConditionEvents events) {
                int mutationsScanned = 0;

                for (Field field : item.reflect().getDeclaredFields()) {
                    if (!isStringConstant(field)) {
                        continue;
                    }
                    field.setAccessible(true);
                    var sql = readConstant(field);
                    if (sql == null) {
                        continue;
                    }
                    for (var mutation : MutationSql.SPANS.findMutations(sql)) {
                        mutationsScanned++;
                        if (MutationSql.SPANS.targetsAnythingOtherThanTheResolver(mutation)) {
                            events.add(SimpleConditionEvent.violated(item, """
                                    %s.%s declares `%s`. Every span mutation must target %s, which \
                                    SpanDAO#selectSpansMutationTable resolves from the wrap flag and binds: naming \
                                    `spans` breaks every cut-over install (a Distributed table rejects mutations), \
                                    naming `spans_local` breaks every install that has not cut over (no such table), \
                                    and any other target is never bound at all — it reaches the server as literal \
                                    placeholder text.\
                                    """.formatted(item.getSimpleName(), field.getName(), mutation,
                                    MutationSql.SPANS.getResolverPlaceholder())));
                        }
                    }
                }

                // A rule that stops finding anything has stopped guarding. Zero means the SQL moved out of these
                // constants, not that it became correct.
                if (mutationsScanned == 0) {
                    events.add(SimpleConditionEvent.violated(item, ("%s declares no span mutations at all — the SQL "
                            + "moved out of its constants and this rule is no longer guarding anything")
                            .formatted(item.getSimpleName())));
                }
            }
        };
    }

    private static boolean isStringConstant(Field field) {
        return Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class;
    }

    private static String readConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read SQL constant " + field.getName(), e);
        }
    }
}
