package com.comet.opik.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The free-form SQL accounts' row policies, as the accounts see them (users.xml mirrors
 * provision_agent_insights_readonly_user.sh): every read is scoped to the request's workspace and, where the account
 * binds it, project; reads prune by those keys; and the settings that can stop row policies applying are pinned.
 * The data is written through the public API, and every case runs on each {@link FreeFormSqlTopology}.
 *
 * <p><b>Re-run on every ClickHouse upgrade.</b> The guarantees here are version behaviour, not configuration alone:
 * bump the container image in the upgrade PR and this suite runs against the new version.
 */
@ParameterizedClass(name = "{0}")
@EnumSource(FreeFormSqlTopology.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("Free-form SQL row policy conformance")
class FreeFormSqlRowPolicyConformanceTest {

    private static final DatabaseAnalyticsReadOnlyFreeFormSqlConfig STANDARD = TestConfigUtils.loadConfigTest()
            .getDatabaseAnalyticsReadOnlyFreeFormSql();
    private static final DatabaseAnalyticsReadOnlyFreeFormSqlConfig EXTENDED = TestConfigUtils.loadConfigTest()
            .getDatabaseAnalyticsReadOnlyFreeFormExtendedSql();

    /** The pinned settings and their values, as both the provisioning script and users.xml set them. */
    private static final Map<String, String> PINNED = Map.ofEntries(
            Map.entry("readonly", "1"), Map.entry("allow_ddl", "0"), Map.entry("serialize_query_plan", "0"),
            Map.entry("make_distributed_plan", "0"), Map.entry("enable_parallel_replicas", "0"),
            Map.entry("max_parallel_replicas", "1"), Map.entry("use_query_cache", "0"),
            Map.entry("query_cache_share_between_users", "0"), Map.entry("use_query_condition_cache", "0"),
            Map.entry("enable_analyzer", "1"), Map.entry("apply_row_policy_after_final", "1"),
            Map.entry("allow_introspection_functions", "0"), Map.entry("optimize_trivial_count_query", "0"),
            Map.entry("optimize_use_implicit_projections", "0"), Map.entry("prefer_localhost_replica", "1"));

    /** The extended account's other tables that bind the project, as the provisioning declares. */
    private static final List<String> EXTENDED_PROJECT_BOUND_TABLES = List.of("authored_feedback_scores",
            "feedback_scores", "trace_threads");
    /** The extended account's tables bound to the workspace only: an experiment or a dataset can span projects. */
    private static final List<String> EXTENDED_WORKSPACE_ONLY_TABLES = List.of("experiments", "experiment_items",
            "dataset_items", "dataset_item_versions");
    /** Rows FreeFormSqlTestData seeds per project: one experiment, PER_PROJECT of everything else. */
    private static final Map<String, Integer> SEEDED_PER_PROJECT = Map.of("experiments", 1);

    // ClickHouse not reused: the run starts from a freshly migrated, empty database, and the Distributed wrap cannot be
    // undone, so it must not reach a container another suite shares.
    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, clickHouse, zookeeper).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mysql.getJdbcUrl())
                        .databaseAnalyticsFactory(
                                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME))
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(redis.getRedisURI())
                        .build());
    }

    private Client admin;
    private FreeFormSqlTestData data;

    /**
     * One instance serves every topology, in {@link FreeFormSqlTopology} order: the data is seeded once, the cases run
     * on the migrated schema, then the tables are wrapped as Distributed, as the cutover wraps live data, and the
     * cases run again. The wrap cannot be undone, so the migrated topology must come first.
     */
    @Parameter
    FreeFormSqlTopology topology;

    @BeforeParameterizedClassInvocation
    void applyTopology() {
        topology.apply(admin);
        // Every project's rows made it through the topology, so a cross-workspace exclusion is never vacuous.
        for (var project : List.of(data.a1(), data.a2(), data.b1())) {
            readTables().forEach(table -> assertThat(single(admin, """
                    SELECT count() FROM %s.%s WHERE workspace_id = '%s' AND project_id = '%s'
                    """.formatted(DATABASE_NAME, table, project.workspace().id(), project.id())))
                    .as("%s rows of %s after %s", table, project.name(), topology)
                    .isEqualTo(expected(table, false)));
        }
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {
        ClientSupportUtils.config(client);
        admin = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME).buildClient();
        data = FreeFormSqlTestData.seed(client, wireMock, admin);
    }

    @AfterAll
    void tearDownAll() throws Exception {
        // Each release runs even if an earlier one throws, so a failed close never leaks the containers.
        // WireMock is this suite's own; Redis and MySQL are the shared reusable containers, left running for the rest.
        try (network; var zk = zookeeper; var ch = clickHouse; var client = admin) {
            wireMock.server().stop();
        }
    }

    /** The tables the accounts read, plus their local tables once they exist (Distributed). */
    private Stream<String> readTables() {
        return Stream.of("traces", "spans", "traces_local", "spans_local")
                .filter(table -> "1".equals(single(admin, "EXISTS TABLE %s.%s".formatted(DATABASE_NAME, table))));
    }

    /**
     * The rows of {@code table} seeded in one project, or across a workspace's two projects: fixed by the seeding, not
     * read back from the tables under test, so a row lost on the way in fails rather than lowering the expectation.
     */
    private static String expected(String table, boolean wholeWorkspace) {
        int perProject = SEEDED_PER_PROJECT.getOrDefault(table.replace("_local", ""), FreeFormSqlTestData.PER_PROJECT);
        return String.valueOf(wholeWorkspace ? 2 * perProject : perProject);
    }

    /** {@code {t}} is the table; {@code {other}} another workspace's id, {@code {project}} the scope's project. */
    static Stream<Arguments> scopedShapes() {
        return Stream.of(
                arguments("count", "SELECT count() FROM {t}", true),
                arguments("another workspace, explicit", "SELECT count() FROM {t} WHERE workspace_id = '{other}'",
                        false),
                arguments("IN subquery", """
                        SELECT count() FROM {t} WHERE id IN (SELECT id FROM {t} WHERE workspace_id = '{other}')
                        """, false),
                arguments("scalar subquery", "SELECT (SELECT count() FROM {t} WHERE project_id != '{project}')",
                        false),
                arguments("self JOIN", "SELECT count() FROM {t} AS a INNER JOIN {t} AS b ON a.id = b.id", true),
                arguments("FINAL", "SELECT count() FROM {t} FINAL", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("the standard account sees only its workspace and project, on every table and query shape")
    void scopedShapes(String name, String template, boolean seesScope) {
        var project = data.a1();
        try (var standard = client(STANDARD)) {
            readTables().forEach(table -> {
                String sql = template.replace("{t}", "%s.%s".formatted(DATABASE_NAME, table))
                        .replace("{other}", data.b().id()).replace("{project}", project.id().toString());
                assertThat(single(standard, sql, project.workspace().id(), project.id().toString()))
                        .as("%s on %s", name, table).isEqualTo(seesScope ? expected(table, false) : "0");
            });
        }
    }

    @Test
    @DisplayName("the standard account stays project-bound: without a project it reads nothing")
    void standardWithoutProjectReadsNothing() {
        try (var standard = client(STANDARD)) {
            readTables().forEach(table -> assertThat(single(standard,
                    "SELECT count() FROM %s.%s".formatted(DATABASE_NAME, table), data.a().id(), "*"))
                    .as(table).isEqualTo("0"));
        }
    }

    @Test
    @DisplayName("the extended account reads its project, or the whole workspace under '*', and never another")
    void extendedOptionalProject() {
        try (var extended = client(EXTENDED)) {
            readTables().forEach(table -> assertExtendedScope(extended, table));
        }
    }

    @ParameterizedTest
    @MethodSource("extendedProjectBoundTables")
    @DisplayName("the extended account's other project-bound tables: its project, or its workspace under '*'")
    void extendedProjectBoundTablesAreScoped(String table) {
        try (var extended = client(EXTENDED)) {
            assertExtendedScope(extended, table);
        }
    }

    @ParameterizedTest
    @MethodSource("extendedWorkspaceOnlyTables")
    @DisplayName("the extended account's workspace-only tables: its whole workspace, whatever the project")
    void extendedWorkspaceOnlyTablesAreScoped(String table) {
        var project = data.a1();
        String count = "SELECT count() FROM %s.%s".formatted(DATABASE_NAME, table);
        try (var extended = client(EXTENDED)) {
            assertThat(single(extended, count, project.workspace().id(), project.id().toString()))
                    .isEqualTo(expected(table, true));
            assertThat(single(extended, count, project.workspace().id(), "*")).isEqualTo(expected(table, true));
            assertThat(single(extended, "%s WHERE workspace_id = '%s'".formatted(count, data.b().id()),
                    project.workspace().id(), "*")).isEqualTo("0");
        }
    }

    static List<String> extendedProjectBoundTables() {
        return EXTENDED_PROJECT_BOUND_TABLES;
    }

    static List<String> extendedWorkspaceOnlyTables() {
        return EXTENDED_WORKSPACE_ONLY_TABLES;
    }

    /** A project-bound table as the extended account: its project, its workspace under '*', never another. */
    private void assertExtendedScope(Client extended, String table) {
        var project = data.a1();
        String count = "SELECT count() FROM %s.%s".formatted(DATABASE_NAME, table);
        assertThat(single(extended, count, project.workspace().id(), project.id().toString())).as(table)
                .isEqualTo(expected(table, false));
        assertThat(single(extended, count, project.workspace().id(), "*")).as(table)
                .isEqualTo(expected(table, true));
        assertThat(single(extended, "%s WHERE workspace_id = '%s'".formatted(count, data.b().id()),
                project.workspace().id(), "*")).as(table).isEqualTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"traces", "spans"})
    @DisplayName("a scoped read prunes by workspace and project: both are in its primary key condition")
    void scopedReadPrunes(String table) {
        var project = data.a1();
        try (var standard = client(STANDARD)) {
            String plan = String.join("\n", standard.queryAll("""
                    EXPLAIN indexes = 1 SELECT count() FROM %s.%s WHERE id != '' SETTINGS %s
                    """.formatted(DATABASE_NAME, table, scope(project.workspace().id(), project.id().toString())))
                    .stream().map(row -> row.getString(1)).toList());
            assertThat(plan).as(plan).contains("PrimaryKey");
            // The primary key section alone: its keys, and a condition that actually bounds them.
            String primaryKey = plan.substring(plan.indexOf("PrimaryKey"));
            int next = primaryKey.indexOf("\n      ", primaryKey.indexOf("Condition:"));
            primaryKey = next < 0 ? primaryKey : primaryKey.substring(0, next);
            assertThat(primaryKey).as(plan).contains("Keys:", "workspace_id", "project_id", "Condition:")
                    .doesNotContain("Condition: true");
        }
    }

    @ParameterizedTest
    @MethodSource("accounts")
    @DisplayName("the settings that can stop row policies applying are pinned and cannot be overridden")
    void settingsArePinned(DatabaseAnalyticsReadOnlyFreeFormSqlConfig account) {
        try (var client = client(account)) {
            PINNED.forEach((setting, value) -> {
                assertThat(single(client, "SELECT value FROM system.settings WHERE name = '%s'".formatted(setting)))
                        .as(setting).isEqualTo(value);
                // A valid value other than the pinned one, so only the pin can reject it.
                String other = setting.equals("max_parallel_replicas") ? "2" : "1".equals(value) ? "0" : "1";
                assertThatThrownBy(() -> client.queryAll("SELECT 1 SETTINGS %s = %s".formatted(setting, other)))
                        .as(setting).hasRootCauseInstanceOf(ServerException.class)
                        // 164 for most, 392 for allow_ddl; aliases report under their own names.
                        .rootCause().hasMessageContaining("Cannot modify '");
            });
        }
    }

    static List<DatabaseAnalyticsReadOnlyFreeFormSqlConfig> accounts() {
        return List.of(STANDARD, EXTENDED);
    }

    private Client client(DatabaseAnalyticsReadOnlyFreeFormSqlConfig account) {
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);
        factory.setUsername(account.getUsername());
        factory.setPassword(account.getPassword());
        return factory.buildClient();
    }

    private static String scope(String workspaceId, String projectId) {
        return "SQL_workspace_id = '%s', SQL_project_id = '%s'".formatted(workspaceId, projectId);
    }

    private static String single(Client client, String sql, String workspaceId, String projectId) {
        return single(client, "%s SETTINGS %s".formatted(sql.strip(), scope(workspaceId, projectId)));
    }

    private static String single(Client client, String sql) {
        return client.queryAll(sql).getFirst().getString(1);
    }
}
