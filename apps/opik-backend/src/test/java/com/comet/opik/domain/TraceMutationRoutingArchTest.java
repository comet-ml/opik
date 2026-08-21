package com.comet.opik.domain;

import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
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
 * {@link TraceDAOImpl#tracesMutationTable()}, and these two rules keep it there: the configuration flag is read in
 * exactly one place, and the routing decision is made in exactly one place. A new mutation path cannot choose its own
 * table without failing the build. {@link TraceMutationSqlRoutingTest} covers the other half — that no SQL spells a
 * physical trace table name out.
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
     * The flag itself is read only by {@code TraceDAOImpl}'s accessor of the same name, whose Javadoc carries the
     * read/mutate split and the migration rules. A second reader is a second place that can get those wrong.
     */
    @ArchTest
    static final ArchRule the_wrap_flag_is_read_in_exactly_one_place = methods()
            .that().areDeclaredIn(DatabaseAnalyticsDataModelConfig.class)
            .and().haveName(CONFIG_FLAG)
            .should().onlyBeCalled().byMethodsThat(name(CONFIG_FLAG))
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
            .should().onlyBeCalled().byMethodsThat(name(RESOLVER))
            .because("""
                    the physical table a trace mutation targets must be resolved only by \
                    TraceDAOImpl#tracesMutationTable, so there is exactly one line to audit when the topology changes
                    """);
}
