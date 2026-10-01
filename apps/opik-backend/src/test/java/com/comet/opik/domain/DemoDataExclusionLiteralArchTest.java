package com.comet.opik.domain;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p><b>Why three rules.</b> Reintroducing the failure takes both a query template that accepts the project set and
 * a caller that fetches the whole set to pass in. The first rule forbids the template side by matching the SQL text;
 * the second forbids the fetch side by requiring the demo-project lookup to name the workspaces it is asking about,
 * so no call can return the installation's whole demo population. Each has a hole the other does not cover — a
 * template carrying the set under differently named placeholders evades the first, and the scoped lookup called with
 * every workspace evades the second — so the third closes the first's: rather than naming placeholders, it requires
 * the usage queries to render text that cannot vary at all. That is the property the whole design rests on, and it
 * holds however a future parameter is spelled.
 *
 * <p>{@link #PENDING_USAGE_QUERIES} is asserted by exact equality rather than as a skip-list, so it is
 * self-cleaning in both directions: a new violation anywhere fails the build, and so does an exemption left behind
 * after the code it covered was fixed.
 */
class DemoDataExclusionLiteralArchTest {

    /**
     * The template attribute and bind parameter the exclusion travelled under. Matching the SQL text rather than
     * the call site is what makes the rule independent of how the set is assembled.
     */
    private static final Set<String> EXCLUSION_PLACEHOLDERS = Set.of("excluded_project_ids", "demo_data_created_at");

    /**
     * Constants still allowed to inline the exclusion, as {@code SimpleClassName.FIELD_NAME}. An entry is a
     * temporary exemption for a query not yet migrated, never a standing allowance.
     */
    private static final Set<String> PENDING_USAGE_QUERIES = Set.of();

    /**
     * The whole application, not just {@code com.comet.opik.domain}: the BI and usage DAOs this guards are split
     * across {@code domain} and {@code infrastructure.bi}, so scanning one package would leave the other free to
     * inline the exclusion while the guard still reported clean.
     */
    private static final String SCANNED_PACKAGE = "com.comet.opik";

    /**
     * The daily usage and BI queries, for both entities, as {@code SimpleClassName.FIELD_NAME}. Named explicitly and
     * asserted to be found: a rename has to be reflected here rather than silently dropping a query out of the rule.
     */
    private static final Set<String> USAGE_QUERIES = Set.of(
            "TraceDAOImpl.TRACE_DAILY_COUNT_BY_WORKSPACE_PROJECT",
            "TraceDAOImpl.TRACE_DAILY_BI_INFORMATION_BY_PROJECT",
            "SpanDAO.SPAN_DAILY_COUNT_BY_WORKSPACE_PROJECT",
            "SpanDAO.SPAN_DAILY_COUNT_BY_WORKSPACE_PROJECT_USER");

    /**
     * The one attribute a usage query may carry. It holds the operation name that identifies the query in
     * {@code system.query_log} and is not a vector for the failure: every usage call site passes the op name and
     * nothing else, so what it renders is fixed per query rather than per request.
     */
    private static final String LOG_COMMENT = "log_comment";

    /** StringTemplate attributes, {@code <name>} and {@code <if(name)>} alike. */
    private static final Pattern TEMPLATE_ATTRIBUTE = Pattern.compile("<([^>]+)>");

    /** R2DBC bind parameters. The lookbehind keeps {@code ::} casts and mid-word colons out. */
    private static final Pattern BIND_PARAMETER = Pattern.compile("(?<![:\\w]):([a-zA-Z_]\\w*)");

    /** The workspace scope every demo-project lookup must carry. */
    private static final String DEMO_PROJECT_LOOKUP = "findByGlobalNames";
    private static final String WORKSPACE_SCOPE = "workspace_ids";

    /**
     * The fetch side. Loading every demo project in the installation is unbounded — one per signup — so the DAO must
     * offer no way to do it: {@link ProjectDAO#findByGlobalNames} takes the workspaces to look in. Asserted over
     * every overload, because an overload defaulting the scope away is exactly how the capability would return.
     *
     * <p>Reflection rather than an {@link com.tngtech.archunit.lang.ArchRule}, for the same reason the SQL rule
     * below is a plain test: what makes the scope load-bearing is the {@code workspace_ids} JDBI binding, which is a
     * parameter annotation ArchUnit's call-graph view does not expose.
     */
    @Test
    void everyDemoProjectLookupIsScopedToWorkspaces() {
        var overloads = Arrays.stream(ProjectDAO.class.getDeclaredMethods())
                .filter(method -> DEMO_PROJECT_LOOKUP.equals(method.getName()))
                .toList();

        // An empty selection would pass every assertion below while guarding nothing, so the rule asserts it found
        // the method it governs. If the lookup is renamed, rename it here rather than dropping the check.
        assertThat(overloads)
                .as("%s.%s is the demo-project lookup this rule governs", ProjectDAO.class.getSimpleName(),
                        DEMO_PROJECT_LOOKUP)
                .isNotEmpty();

        assertThat(overloads)
                .as("""
                        a demo-project lookup must name the workspaces it asks about: unscoped it returns every demo \
                        project in the installation, which grows with every signup, and it is that whole set which \
                        used to be rendered into the usage queries. Scoping it also lets \
                        projects_workspace_id_name_uk (workspace_id, name) serve the query\
                        """)
                .allSatisfy(overload -> assertThat(bindListNames(overload)).contains(WORKSPACE_SCOPE));
    }

    /**
     * Reflects over the SQL constants rather than expressing an {@code ArchRule}, for the same reason
     * {@link TraceMutationRoutingArchTest}'s third rule uses a custom condition: ArchUnit works from bytecode and
     * exposes call graphs, not string values. A plain test rather than an {@code ArchCondition} because the
     * assertion is over the whole set of offenders at once — a per-class condition cannot tell a pending migration
     * from an exemption left behind after one.
     */
    @Test
    void noDaoSqlConstantInlinesTheDemoProjectExclusion() {
        var daoClasses = daoClasses();

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
        // reason: with no offender expected, the assertion below passes identically whether the classes were scanned
        // and found clean or never scanned at all. If either is renamed or its SQL moves elsewhere, update this
        // guard rather than dropping the check.
        assertThat(classesDeclaringQueries)
                .as("the DAO classes holding the usage queries must be in scope; searched %s for simple names "
                        + "containing \"DAO\"", SCANNED_PACKAGE)
                .contains("TraceDAOImpl", "SpanDAO");

        assertThat(offenders)
                .as("""
                        a usage query must not render the demo-project set into its text: the set is unbounded (one \
                        project per signup) and a Distributed table re-parses the query text per shard, so the query \
                        eventually exceeds max_execution_time and the daily usage counts silently stop. Group by \
                        project_id and fold the exclusion in Java via DemoDataExclusionUtils instead\
                        """)
                .isEqualTo(new TreeSet<>(PENDING_USAGE_QUERIES));
    }

    /**
     * The property the other two rules exist to protect, asserted directly: a usage query's text is the same on
     * every execution. What broke on traces was not the exclusion as such but its size — a {@code Distributed}
     * table re-parses the query text per shard, so text that grows with the data eventually costs more than the
     * query itself. Text that cannot vary cannot grow.
     *
     * <p>Stated as "no attributes and no bind parameters" rather than as a list of forbidden names, which is what
     * makes it hold for a parameter nobody has thought of yet. Both entities are covered, since they carry the same
     * exposure and only one of them has been through this twice.
     */
    @Test
    void theUsageQueriesRenderTextThatCannotVary() {
        var constantsByName = daoClasses().stream()
                .flatMap(daoClass -> stringConstants(daoClass)
                        .map(field -> Map.entry("%s.%s".formatted(daoClass.getSimpleName(), field.getName()),
                                readConstant(field))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, _) -> first));

        // Same reason as the scope guard in the rule above: a rule that stops finding what it guards passes for the
        // wrong reason. Renaming a usage query must fail here, not quietly narrow the rule.
        assertThat(constantsByName.keySet())
                .as("the usage queries this rule governs must be in scope; searched %s", SCANNED_PACKAGE)
                .containsAll(USAGE_QUERIES);

        assertThat(USAGE_QUERIES).allSatisfy(queryName -> {
            var sql = constantsByName.get(queryName);
            assertThat(matches(TEMPLATE_ATTRIBUTE, sql))
                    .as("""
                            %s may render no template attribute other than %s: a Distributed table re-parses the \
                            query text per shard, so anything the text interpolates from the data is paid for on \
                            every shard and grows with it. Fold it in Java instead\
                            """, queryName, LOG_COMMENT)
                    .isSubsetOf(LOG_COMMENT);
            assertThat(matches(BIND_PARAMETER, sql))
                    .as("%s may bind no parameter, for the same reason", queryName)
                    .isEmpty();
        });
    }

    private List<JavaClass> daoClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(SCANNED_PACKAGE)
                .stream()
                .filter(javaClass -> javaClass.getSimpleName().contains("DAO"))
                .toList();
    }

    private Set<String> matches(Pattern pattern, String sql) {
        return pattern.matcher(sql).results()
                .map(result -> result.group(1))
                .collect(toCollection(TreeSet::new));
    }

    /** The {@code @BindList} names a JDBI query method binds, which is what puts a column in its predicate. */
    private Set<String> bindListNames(Method method) {
        return Arrays.stream(method.getParameters())
                .map(Parameter::getAnnotations)
                .flatMap(Arrays::stream)
                .filter(BindList.class::isInstance)
                .map(annotation -> ((BindList) annotation).value())
                .collect(toCollection(TreeSet::new));
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
