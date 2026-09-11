package com.comet.opik.domain;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the unbounded demo-project set out of the daily usage and BI queries.
 *
 * <p>Those queries used to exclude demo data with an inline {@code project_id NOT IN [...]} literal holding one
 * UUID per demo project across all workspaces. One demo project is created per signup, so that literal grows
 * without bound, and a {@code Distributed} table re-parses the query text for its shard — the queries eventually
 * exceeded {@code max_execution_time} and the daily counts silently stopped being produced, because the callers
 * consuming them cannot tell an empty result from a failed one. The exclusion now happens in Java
 * ({@link com.comet.opik.domain.utils.DemoDataExclusionUtils}), which keeps the query text constant.
 *
 * <p><b>Why two rules.</b> Reintroducing the failure takes both a query template that accepts the project set and a
 * caller that fetches the whole set to pass in. The first rule forbids the template side by name, which is precise
 * but rename-sensitive; the second forbids the fetch side through the call graph, which is name-independent. Either
 * alone has a hole: a template attribute called something else evades the first, and a set obtained some other way
 * evades the second.
 *
 * <p><b>Spans are a known pending violation, listed explicitly.</b> {@link SpanDAO} carries the same pattern in
 * three constants, but {@code spans} is not wrapped in a {@code Distributed} table yet, so it is not affected
 * today and its migration is deliberately a separate change. {@link #PENDING_SPAN_USAGE_QUERIES} is asserted by
 * exact equality rather than as a skip-list, which makes it self-cleaning in both directions: a new violation
 * anywhere fails the build, and so does migrating spans without deleting the entries.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class DemoDataExclusionLiteralArchTest {

    /**
     * The template attribute and bind parameter the exclusion travelled under. Matching the SQL text rather than
     * the call site is what makes the rule independent of how the set is assembled.
     */
    private static final Set<String> EXCLUSION_PLACEHOLDERS = Set.of("excluded_project_ids", "demo_data_created_at");

    /**
     * Still-inlining constants, as {@code SimpleClassName.FIELD_NAME}. Emptying this set is the spans follow-up; the
     * assertion below fails if it is emptied without the code change, or left populated after it.
     */
    private static final Set<String> PENDING_SPAN_USAGE_QUERIES = Set.of(
            "SpanDAO.SPAN_COUNT_BY_WORKSPACE_ID",
            "SpanDAO.SPAN_DAILY_BI_INFORMATION",
            "SpanDAO.SPAN_DAILY_COUNT_BY_WORKSPACE_PROJECT_USER");

    private static final String DOMAIN_PACKAGE = "com.comet.opik.domain";

    private static final String UNSCOPED_DEMO_PROJECT_FETCH = "getDemoProjectIdsWithTimestamps";

    /**
     * The fetch side. The unscoped fetch loads every demo project in the installation, so a new caller is a new
     * place that can put the whole set into a query. The trace paths use {@link ProjectService#getDemoProjectIds}
     * instead, which is scoped to the projects that actually had activity, and the span usage paths are the only
     * remaining callers until they migrate too.
     *
     * <p><b>Deliberately no {@code allowEmptyShould}</b>: the rule selects the unscoped method itself, so an empty
     * selection means it was renamed or removed and the rule guards nothing. Failing then is the point.
     */
    @ArchTest
    static final ArchRule the_unscoped_demo_project_fetch_is_read_only_by_the_span_usage_paths = methods()
            .that().areDeclaredIn(ProjectService.class)
            .and().haveName(UNSCOPED_DEMO_PROJECT_FETCH)
            .should().onlyBeCalled().byCodeUnitsThat(DescribedPredicate.describe(SpanService.class.getSimpleName(),
                    codeUnit -> codeUnit.getOwner().isEquivalentTo(SpanService.class)))
            .because("""
                    fetching every demo project in the installation is unbounded — one per signup — so it must not \
                    spread beyond the span usage paths that have yet to migrate. New callers scope the lookup to the \
                    ids they already hold, via ProjectService#getDemoProjectIds\
                    """);

    /**
     * Reflects over the SQL constants rather than expressing an {@link ArchRule}, for the same reason
     * {@link TraceMutationRoutingArchTest}'s third rule uses a custom condition: ArchUnit works from bytecode and
     * exposes call graphs, not string values. A plain test rather than an {@code ArchCondition} because the
     * assertion is over the whole set of offenders at once — a per-class condition cannot tell "spans still
     * pending" from "spans migrated but the exemption left behind".
     */
    @Test
    void noDaoSqlConstantInlinesTheDemoProjectExclusion() {
        var daoClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(DOMAIN_PACKAGE)
                .stream()
                .filter(javaClass -> javaClass.getSimpleName().contains("DAO"))
                .toList();

        // Every string constant is inspected, not only the query-shaped ones: a placeholder can live in a predicate
        // fragment that carries no statement keyword of its own.
        var offenders = daoClasses.stream()
                .flatMap(daoClass -> stringConstants(daoClass)
                        .filter(field -> EXCLUSION_PLACEHOLDERS.stream().anyMatch(readConstant(field)::contains))
                        .map(field -> "%s.%s".formatted(daoClass.getSimpleName(), field.getName())))
                .collect(toCollection(TreeSet::new));

        var classesDeclaringQueries = daoClasses.stream()
                .filter(daoClass -> stringConstants(daoClass).anyMatch(field -> isQuery(readConstant(field))))
                .map(JavaClass::getSimpleName)
                .collect(toCollection(TreeSet::new));

        // A guard that stops finding the queries it guards has stopped guarding, and would then pass for the wrong
        // reason. SpanDAO's presence also follows from the offender assertion below, but TraceDAOImpl — the class
        // whose queries were migrated — would otherwise be indistinguishable from not being scanned at all. If
        // either is renamed or its SQL moves elsewhere, update this guard rather than dropping the check.
        assertThat(classesDeclaringQueries)
                .as("the DAO classes holding the usage queries must be in scope; searched %s for simple names "
                        + "containing \"DAO\"", DOMAIN_PACKAGE)
                .contains("TraceDAOImpl", "SpanDAO");

        assertThat(offenders)
                .as("""
                        a usage query must not render the demo-project set into its text: the set is unbounded (one \
                        project per signup) and a Distributed table re-parses the query text per shard, so the query \
                        eventually exceeds max_execution_time and the daily usage counts silently stop. Group by \
                        project_id and fold the exclusion in Java via DemoDataExclusionUtils instead. If this failed \
                        because the spans queries were migrated, delete their entries from \
                        PENDING_SPAN_USAGE_QUERIES\
                        """)
                .isEqualTo(new TreeSet<>(PENDING_SPAN_USAGE_QUERIES));
    }

    private Stream<Field> stringConstants(JavaClass daoClass) {
        return Arrays.stream(daoClass.reflect().getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && field.getType() == String.class)
                .filter(field -> readConstant(field) != null);
    }

    /** Query-shaped enough to prove the guard is looking at SQL, without trying to parse it. */
    private boolean isQuery(String sql) {
        var upperCase = sql.toUpperCase();
        return upperCase.contains("SELECT ") || upperCase.contains("INSERT INTO");
    }

    private String readConstant(Field field) {
        try {
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read SQL constant " + field.getName(), e);
        }
    }
}
