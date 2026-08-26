package com.comet.opik.domain;

import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
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
 * Architectural guard making trace mutation routing correct by construction.
 *
 * <p>Post-cutover {@code traces} is a {@code Distributed} table, which supports {@code SELECT} and {@code INSERT} but
 * <b>not</b> mutations: a lightweight {@code DELETE} returns code 36 and {@code ALTER ... DELETE} code 48. So every
 * trace mutation has to target the {@code traces_local} shard once the wrap is live, and {@code traces} while it is
 * not. Getting that wrong is a 500 on the delete path for every cut-over install.
 *
 * <p>Rather than trust each new mutation to remember the branch, the decision is funnelled through one method,
 * {@link TraceDAOImpl#tracesMutationTable()}, and these rules keep it there: the configuration flag is read in exactly
 * one place, the routing decision is made in exactly one place, and no declared SQL constant names a physical trace
 * table. A new mutation path cannot choose its own table without failing the build.
 *
 * <p><b>The third rule reflects, because ArchUnit cannot see string values.</b> It works from bytecode and exposes call
 * graphs, not literals — {@code JavaField} has no constant accessor — so the SQL text is unreachable through the API
 * proper. A custom {@link ArchCondition} may still call {@link JavaClass#reflect()}, which is what this one does, so the
 * check lives with its siblings rather than in a separate suite. {@link TraceMutationSqlRoutingTest} keeps the one part
 * that genuinely cannot be expressed here.
 *
 * <p><b>The caller predicates are owner-scoped.</b> Matching on method name alone would let any class declaring a
 * method of the same name satisfy the exemption — an {@code OtherDao#tracesDistributedWrapEnabled()} reading the config
 * directly would have passed, which is precisely the second reader these rules exist to forbid.
 *
 * <p><b>Deliberately no {@code allowEmptyShould}</b>, unlike {@link TraceDeletionEventArchTest}: these rules select the
 * guarded method rather than its callers, so an empty selection means the method was renamed or removed and the rule is
 * no longer guarding anything. Failing then is the point.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class TraceMutationRoutingArchTest {

    private static final String CONFIG_FLAG = "tracesDistributedWrapEnabled";
    private static final String RESOLVER = "tracesMutationTable";

    /**
     * Exactly one method: the named one on the named class. Matching by name alone would exempt any class that happened
     * to declare a method of that name, which is the very thing these rules forbid.
     */
    private static DescribedPredicate<JavaMethod> only(Class<?> owner, String methodName) {
        return DescribedPredicate.describe("%s.%s".formatted(owner.getSimpleName(), methodName),
                method -> method.getOwner().isEquivalentTo(owner) && method.getName().equals(methodName));
    }

    /**
     * The flag itself is read only by {@code TraceDAOImpl}'s accessor of the same name, whose Javadoc carries the
     * read/mutate split and the migration rules. A second reader is a second place that can get those wrong.
     */
    @ArchTest
    static final ArchRule the_wrap_flag_is_read_in_exactly_one_place = methods()
            .that().areDeclaredIn(DatabaseAnalyticsDataModelConfig.class)
            .and().haveName(CONFIG_FLAG)
            .should().onlyBeCalled().byMethodsThat(only(TraceDAOImpl.class, CONFIG_FLAG))
            .because("""
                    the sharding-readiness wrap flag must be read only by TraceDAOImpl#tracesDistributedWrapEnabled, \
                    which documents what the two topologies imply for reads, mutations and migrations
                    """);

    /**
     * And the flag accessor is consulted only by the resolver, so the physical table name is decided once. A mutation
     * that branches on the flag itself is a mutation that can name the wrong table — which post-cutover means a failed
     * delete on every cut-over install, and pre-cutover a delete against a table that does not exist.
     */
    @ArchTest
    static final ArchRule the_mutation_table_is_decided_in_exactly_one_place = methods()
            .that().areDeclaredIn(TraceDAOImpl.class)
            .and().haveName(CONFIG_FLAG)
            .should().onlyBeCalled().byMethodsThat(only(TraceDAOImpl.class, RESOLVER))
            .because("""
                    the physical table a trace mutation targets must be resolved only by \
                    TraceDAOImpl#tracesMutationTable, so there is exactly one line to audit when the topology changes
                    """);

    /**
     * The third leg, moved here from its own suite: every trace mutation declared as a SQL constant must target
     * {@code <traces_mutation_table>} rather than naming a table. A call-graph rule cannot see this — a new mutation
     * could hardcode {@code DELETE FROM traces} without consulting the flag at all — so the condition reads the
     * constants reflectively.
     */
    @ArchTest
    static final ArchRule no_declared_sql_names_a_physical_trace_table = classes()
            .that().areAssignableTo(TraceDAOImpl.class)
            .should(notNameAPhysicalTraceTableInMutationSql());

    private static ArchCondition<JavaClass> notNameAPhysicalTraceTableInMutationSql() {
        return new ArchCondition<>("declare no mutation SQL naming `traces` or `traces_local`") {

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
                    for (var mutation : TraceMutationSql.findMutations(sql)) {
                        mutationsScanned++;
                        if (TraceMutationSql.namesAPhysicalTraceTable(mutation)) {
                            events.add(SimpleConditionEvent.violated(item, """
                                    %s.%s declares `%s`. Every trace mutation must target <traces_mutation_table>, \
                                    which TraceDAOImpl#tracesMutationTable resolves from the wrap flag: naming \
                                    `traces` breaks every cut-over install (a Distributed table rejects mutations), \
                                    and naming `traces_local` breaks every install that has not cut over (no such \
                                    table).\
                                    """.formatted(item.getSimpleName(), field.getName(), mutation)));
                        }
                    }
                }

                // A rule that stops finding anything has stopped guarding. Zero means the SQL moved out of these
                // constants, not that it became correct.
                if (mutationsScanned == 0) {
                    events.add(SimpleConditionEvent.violated(item, ("%s declares no trace mutations at all — the SQL "
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
