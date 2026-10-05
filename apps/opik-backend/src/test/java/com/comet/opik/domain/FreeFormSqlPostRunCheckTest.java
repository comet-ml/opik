package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfigTest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

/**
 * The post-run policy check end to end: the real service, the real read-only accounts (users.xml) and the real
 * query log and plans of ClickHouse, on the migrated schema. {@link FreeFormSqlPostRunCheckPostCutoverTest} runs the
 * same cases with traces and spans wrapped as Distributed. Re-run on every ClickHouse upgrade: the check depends on
 * what query_log and EXPLAIN record.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Free-form SQL post-run policy check, end to end")
class FreeFormSqlPostRunCheckTest {

    static final String STANDARD_USER = "comet_readonly_freeform_sql_user";
    static final String EXTENDED_USER = "comet_readonly_freeform_extended_sql_user";
    /** A SQL-created account whose authored_feedback_scores policy, and local-table policies, were never created. */
    static final String PARTIAL_USER = "ro_partial_policies";
    static final String WORKSPACE_A = UUID.randomUUID().toString();
    static final String WORKSPACE_B = UUID.randomUUID().toString();
    static final UUID PROJECT_A = UUID.randomUUID();
    static final String PROJECT_B = UUID.randomUUID().toString();
    static final int ROWS = 100;
    // Not reused: each run starts from a freshly migrated, empty database, so the rows are exactly the ones below.
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private Client admin;
    private Client standard;
    private Client extended;
    private Client partial;

    @BeforeAll
    void setUpAll() {
        Startables.deepStart(zookeeper, clickHouse).join();
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        admin = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME).buildClient();
        prepareTopology(admin);
        for (var scope : List.of(List.of(WORKSPACE_A, PROJECT_A.toString()), List.of(WORKSPACE_B, PROJECT_B))) {
            insert("traces (workspace_id, project_id, id) SELECT '%s', '%s', toString(generateUUIDv7())", scope);
            insert("spans (workspace_id, project_id, trace_id, id) SELECT '%s', '%s', toString(generateUUIDv7()), "
                    + "toString(generateUUIDv7())", scope);
            insert("authored_feedback_scores (workspace_id, project_id, entity_id, value) SELECT '%s', '%s', "
                    + "toString(generateUUIDv7()), 0", scope);
        }
        String policy = "workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id')";
        admin.queryAll("CREATE USER %s IDENTIFIED BY 'opik' SETTINGS PROFILE 'comet_llm_readonly_freeform_sql_profile'"
                .formatted(PARTIAL_USER));
        for (String table : List.of("traces", "traces_local", "spans", "spans_local", "authored_feedback_scores")) {
            if (!table.endsWith("_local") && !table.equals("authored_feedback_scores")) {
                admin.queryAll("CREATE ROW POLICY %s_partial ON %s.%s FOR SELECT USING %s AS RESTRICTIVE TO %s"
                        .formatted(table, DATABASE_NAME, table, policy, PARTIAL_USER));
            }
            admin.queryAll("GRANT SELECT ON %s.%s TO %s".formatted(DATABASE_NAME, table, PARTIAL_USER));
        }
        standard = client(STANDARD_USER);
        extended = client(EXTENDED_USER);
        partial = client(PARTIAL_USER);
    }

    private void insert(String statement, List<String> scope) {
        // Foreground, so rows written through a Distributed table are readable at once.
        admin.queryAll(("INSERT INTO %s." + statement + " FROM numbers(%d) SETTINGS distributed_foreground_insert = 1")
                .formatted(DATABASE_NAME, scope.get(0), scope.get(1), ROWS));
    }

    /** The table topology the tests run on: the migrated schema as is, pre-cutover. */
    void prepareTopology(Client admin) {
    }

    @AfterAll
    void tearDownAll() throws Exception {
        // Each release runs even if an earlier one throws, so a failed close never leaks the containers.
        try (network;
                var zk = zookeeper;
                var ch = clickHouse;
                var a = admin;
                var s = standard;
                var e = extended;
                var p = partial) {
            // Resources close in reverse order: the clients, then ClickHouse, ZooKeeper and the network.
        }
    }

    private Client client(String user) {
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);
        factory.setUsername(user);
        factory.setPassword("opik");
        return factory.buildClient();
    }

    /** The service on real clients; {@code standardClient} and {@code standardUser} pick the standard account. */
    private FreeFormSqlQueryService service(Client standardClient, String standardUser) {
        var analytics = new DatabaseAnalyticsFactory();
        analytics.setDatabaseName(DATABASE_NAME);
        var standardConfig = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();
        standardConfig.setUsername(standardUser);
        var extendedConfig = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();
        extendedConfig.setUsername(EXTENDED_USER);
        var dao = new FreeFormSqlQueryDAOImpl(standardClient, extended, admin);
        return new FreeFormSqlQueryService(dao, mock(FreeFormSqlEntityNameEnricher.class),
                logReader(dao),
                FreeFormSqlPostRunCheckConfigTest.config(FreeFormSqlPostRunCheckConfig.Mode.ENFORCE), analytics,
                standardConfig, extendedConfig);
    }

    /**
     * Reads the log as the application does, after flushing it as admin in place of waiting for ClickHouse's own
     * flush interval, so a case takes milliseconds rather than the configured delay.
     */
    private FreeFormSqlQueryLogReader logReader(FreeFormSqlQueryDAO dao) {
        return new FreeFormSqlQueryLogReader((queryId, user) -> {
            admin.queryAll("SYSTEM FLUSH LOGS ON CLUSTER '{cluster}' query_log");
            return dao.fetchQueryLog(queryId, user);
        }, 1, 0, FreeFormSqlQueryLogReader.Scheduler.DELAYED);
    }

    private static String count(String from) {
        return "SELECT toJSONString(map('n', toString(count()))) AS result FROM " + from;
    }

    private String run(String query) {
        return service(standard, STANDARD_USER).executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE_A, PROJECT_A, query)
                .join().results().getFirst().get("n").asText();
    }

    static Stream<Arguments> verifiedShapes() {
        return Stream.of(
                arguments("top-level read", count("traces"), ROWS),
                arguments("JOIN", count("traces AS t INNER JOIN traces AS u ON t.id = u.id"), ROWS),
                arguments("CTE", "WITH c AS (SELECT id FROM traces) " + count("c"), ROWS),
                arguments("CTE over a table that is never Distributed",
                        "WITH c AS (SELECT entity_id FROM authored_feedback_scores) " + count("c"), ROWS),
                arguments("derived table", count("(SELECT id FROM spans)"), ROWS),
                arguments("UNION", count("(SELECT id FROM traces UNION ALL SELECT id FROM spans)"), 2 * ROWS),
                arguments("IN subquery", count("traces WHERE id IN (SELECT id FROM traces)"), ROWS),
                arguments("IN over a table that is never Distributed",
                        count("traces WHERE id NOT IN (SELECT entity_id FROM authored_feedback_scores)"), ROWS),
                arguments("EXISTS", count("traces WHERE EXISTS (SELECT 1 FROM authored_feedback_scores)"), ROWS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("results of every verifiable shape are returned, and scoped")
    void verifiedShapes(String name, String query, int expected) {
        assertThat(run(query)).isEqualTo(String.valueOf(expected));
    }

    @Test
    @DisplayName("a scalar subquery reading a table is rejected before running, with the rewrite to apply")
    void scalarSubqueryReadingATableIsRejected() {
        for (String query : List.of(
                "SELECT toJSONString(map('n', toString((SELECT count() FROM authored_feedback_scores)))) AS result",
                "SELECT toJSONString(map('n', toString((SELECT count() FROM traces)))) AS result",
                "WITH c AS (SELECT id FROM spans) SELECT toJSONString(map('n', toString((SELECT count() FROM c)))) "
                        + "AS result")) {
            assertRejected(query, BadRequestException.class, "cannot read a table", "WITH s AS");
        }
        // A scalar subquery reading no table stays allowed.
        assertThat(run("SELECT toJSONString(map('n', toString((SELECT 1)))) AS result")).isEqualTo("1");
    }

    @Test
    @DisplayName("a table read without its row policy is withheld, not returned")
    void missingPolicyIsWithheld() {
        assertThatThrownBy(() -> service(partial, PARTIAL_USER).executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE_A,
                PROJECT_A, count("authored_feedback_scores")).join())
                .isInstanceOf(CompletionException.class)
                .cause().satisfies(FreeFormSqlQueryServiceTest.withheld(500));
    }

    void assertRejected(String query, Class<? extends WebApplicationException> type, String... message) {
        assertThatThrownBy(() -> run(query))
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(type)
                .satisfies(error -> assertThat(String.valueOf(((WebApplicationException) error).getResponse()
                        .getEntity())).contains(message));
    }

    FreeFormSqlQueryService partialService() {
        return service(partial, PARTIAL_USER);
    }

    static Stream<Arguments> scalarReads() {
        String traces = DATABASE_NAME + ".traces";
        String spans = DATABASE_NAME + ".spans";
        return Stream.of(
                arguments("top-level, JOIN, derived, UNION", "SELECT count() FROM traces AS t INNER JOIN (SELECT id "
                        + "FROM traces) AS u ON t.id = u.id, (SELECT id FROM spans UNION ALL SELECT id FROM traces) AS w",
                        Set.of()),
                arguments("CTE", "WITH c AS (SELECT id FROM spans) SELECT count() FROM c", Set.of()),
                arguments("IN and EXISTS", "SELECT count() FROM traces WHERE id IN (SELECT id FROM spans) "
                        + "AND EXISTS (SELECT 1 FROM authored_feedback_scores)", Set.of()),
                arguments("scalar with no table", "SELECT (SELECT 1)", Set.of()),
                arguments("scalar", "SELECT (SELECT count() FROM traces)", Set.of(traces)),
                arguments("scalar over a CTE resolves to its table",
                        "WITH c AS (SELECT id FROM spans) SELECT (SELECT count() FROM c)", Set.of(spans)),
                arguments("scalar nested inside IN", "SELECT count() FROM traces WHERE id IN (SELECT id FROM traces "
                        + "WHERE id != (SELECT max(id) FROM spans))", Set.of(spans)),
                arguments("scalar nested inside EXISTS", "SELECT count() FROM traces WHERE EXISTS (SELECT 1 FROM "
                        + "traces WHERE id = (SELECT min(id) FROM spans))", Set.of(spans)),
                arguments("scalar as a function argument",
                        "SELECT count() FROM traces WHERE id != (SELECT max(id) FROM spans)", Set.of(spans)));
    }

    @Test
    @DisplayName("a scalar subquery stored by reference hides its reads, so it is rejected too")
    void storedScalarIsOpaque() {
        String query = "SELECT count() FROM traces WHERE has((SELECT groupArray(id) FROM spans), id)";
        var tree = new FreeFormSqlQueryDAOImpl(standard, extended, admin)
                .explainQueryTree(FreeFormSqlAccount.STANDARD, WORKSPACE_A, PROJECT_A.toString(), query).join();
        assertThat(FreeFormSqlSubqueries.scalarReads(tree, DATABASE_NAME).opaque()).isTrue();
        assertRejected("SELECT toJSONString(map('n', toString(count()))) AS result FROM traces "
                + "WHERE has((SELECT groupArray(id) FROM spans), id)", BadRequestException.class, "cannot read a table",
                "too large to inspect");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("the scalar gate classifies subqueries from ClickHouse's real resolved query tree")
    void scalarReads(String name, String query, Set<String> expected) {
        var tree = new FreeFormSqlQueryDAOImpl(standard, extended, admin)
                .explainQueryTree(FreeFormSqlAccount.STANDARD, WORKSPACE_A, PROJECT_A.toString(), query).join();
        assertThat(FreeFormSqlSubqueries.scalarReads(tree, DATABASE_NAME).tables()).isEqualTo(expected);
    }
}
