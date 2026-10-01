package com.comet.opik.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.query.QuerySettings;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The free-form SQL accounts' row policies, as the accounts see them (users.xml mirrors
 * provision_agent_insights_readonly_user.sh): every read is scoped to the request's workspace and, where the account
 * binds it, project; reads prune by those keys; and the settings that can stop row policies applying are pinned.
 *
 * <p><b>Re-run on every ClickHouse upgrade.</b> The guarantees here are version behaviour, not configuration alone:
 * bump the container image in the upgrade PR and this suite runs against the new version.
 * {@link FreeFormSqlRowPolicyConformancePostCutoverTest} runs the same cases with traces and spans wrapped as
 * Distributed, the production topology.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Free-form SQL row policy conformance")
class FreeFormSqlRowPolicyConformanceTest {

    private static final String STANDARD = "comet_readonly_freeform_sql_user";
    private static final String EXTENDED = "comet_readonly_freeform_extended_sql_user";
    private static final String WORKSPACE_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_B = UUID.randomUUID().toString();
    private static final String PROJECT_A1 = UUID.randomUUID().toString();
    private static final String PROJECT_A2 = UUID.randomUUID().toString();
    private static final String PROJECT_B1 = UUID.randomUUID().toString();
    /** Rows per project: several granules each, so a read that does not prune by scope reads visibly more. */
    private static final int ROWS = 20_000;

    /** The pinned settings and their values, as both the provisioning script and users.xml set them. */
    private static final Map<String, String> PINNED = Map.ofEntries(
            Map.entry("readonly", "1"), Map.entry("allow_ddl", "0"), Map.entry("serialize_query_plan", "0"),
            Map.entry("make_distributed_plan", "0"), Map.entry("enable_parallel_replicas", "0"),
            Map.entry("max_parallel_replicas", "1"), Map.entry("use_query_cache", "0"),
            Map.entry("query_cache_share_between_users", "0"), Map.entry("use_query_condition_cache", "0"),
            Map.entry("enable_analyzer", "1"), Map.entry("apply_row_policy_after_final", "1"),
            Map.entry("allow_introspection_functions", "0"));

    // Not reused: each run starts from a freshly migrated, empty database, so the rows are exactly the ones below.
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private Client admin;

    @BeforeAll
    void setUpAll() {
        Startables.deepStart(zookeeper, clickHouse).join();
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        admin = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME).buildClient();
        prepareTopology(admin);
        for (var scope : List.of(List.of(WORKSPACE_A, PROJECT_A1), List.of(WORKSPACE_A, PROJECT_A2),
                List.of(WORKSPACE_B, PROJECT_B1))) {
            // Foreground, so rows written through a Distributed table are readable at once.
            admin.queryAll(("INSERT INTO %s.traces (workspace_id, project_id, id) SELECT '%s', '%s', "
                    + "toString(generateUUIDv7()) FROM numbers(%d) SETTINGS distributed_foreground_insert = 1")
                    .formatted(DATABASE_NAME, scope.get(0), scope.get(1), ROWS));
            admin.queryAll(("INSERT INTO %s.spans (workspace_id, project_id, trace_id, id) SELECT '%s', '%s', "
                    + "toString(generateUUIDv7()), toString(generateUUIDv7()) FROM numbers(%d) "
                    + "SETTINGS distributed_foreground_insert = 1")
                    .formatted(DATABASE_NAME, scope.get(0), scope.get(1), ROWS));
        }
    }

    /** The table topology the tests run on: the migrated schema as is, pre-cutover. */
    void prepareTopology(Client admin) {
    }

    @AfterAll
    void tearDownAll() throws Exception {
        // Each release runs even if an earlier one throws, so a failed close never leaks the containers.
        try (network; var zk = zookeeper; var ch = clickHouse; var client = admin) {
            // Resources close in reverse order: the client, then ClickHouse, ZooKeeper and the network.
        }
    }

    /** The tables the accounts read, plus their local tables once they exist (post-cutover). */
    private Stream<String> readTables() {
        return Stream.of("traces", "spans", "traces_local", "spans_local")
                .filter(table -> "1".equals(single(admin, "EXISTS TABLE %s.%s".formatted(DATABASE_NAME, table))));
    }

    static Stream<Arguments> scopedShapes() {
        return Stream.of(
                arguments("count", "SELECT count() FROM {t}", String.valueOf(ROWS)),
                arguments("another workspace, explicit",
                        "SELECT count() FROM {t} WHERE workspace_id = '" + WORKSPACE_B + "'", "0"),
                arguments("group by workspace", "SELECT count(DISTINCT workspace_id) FROM {t}", "1"),
                arguments("IN subquery",
                        "SELECT count() FROM {t} WHERE id IN (SELECT id FROM {t} WHERE workspace_id = '"
                                + WORKSPACE_B + "')",
                        "0"),
                arguments("scalar subquery",
                        "SELECT (SELECT count() FROM {t} WHERE project_id != '" + PROJECT_A1 + "')", "0"),
                arguments("self JOIN", "SELECT count() FROM {t} AS a INNER JOIN {t} AS b ON a.id = b.id",
                        String.valueOf(ROWS)),
                arguments("FINAL", "SELECT count() FROM {t} FINAL", String.valueOf(ROWS)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("the standard account sees only its workspace and project, on every table and query shape")
    void scopedShapes(String name, String template, String expected) {
        try (var standard = client(STANDARD)) {
            readTables().forEach(table -> assertThat(single(standard,
                    template.replace("{t}", DATABASE_NAME + "." + table), WORKSPACE_A, PROJECT_A1))
                    .as("%s on %s", name, table).isEqualTo(expected));
        }
    }

    @Test
    @DisplayName("the standard account stays project-bound: without a project it reads nothing")
    void standardWithoutProjectReadsNothing() {
        try (var standard = client(STANDARD)) {
            readTables().forEach(table -> assertThat(single(standard,
                    "SELECT count() FROM %s.%s".formatted(DATABASE_NAME, table), WORKSPACE_A, "*"))
                    .as(table).isEqualTo("0"));
        }
    }

    @Test
    @DisplayName("the extended account reads its project, or the whole workspace under '*', and never another")
    void extendedOptionalProject() {
        try (var extended = client(EXTENDED)) {
            readTables().forEach(table -> {
                String count = "SELECT count() FROM %s.%s".formatted(DATABASE_NAME, table);
                assertThat(single(extended, count, WORKSPACE_A, PROJECT_A1)).as(table).isEqualTo(String.valueOf(ROWS));
                assertThat(single(extended, count, WORKSPACE_A, "*")).as(table).isEqualTo(String.valueOf(2 * ROWS));
                assertThat(single(extended, count + " WHERE workspace_id = '" + WORKSPACE_B + "'", WORKSPACE_A, "*"))
                        .as(table).isEqualTo("0");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"traces", "spans"})
    @DisplayName("a scoped read prunes by workspace and project instead of scanning other workspaces")
    void scopedReadPrunes(String table) {
        String queryId = UUID.randomUUID().toString();
        try (var standard = client(STANDARD)) {
            standard.queryAll("SELECT count() FROM %s.%s WHERE id != '' SETTINGS %s"
                    .formatted(DATABASE_NAME, table, scope(WORKSPACE_A, PROJECT_A1)),
                    new QuerySettings().setQueryId(queryId));
        }
        admin.queryAll("SYSTEM FLUSH LOGS");
        // The most any one read of this query read, initial or on the shard: in-scope rows plus partial granules.
        long readRows = Long.parseLong(single(admin, ("SELECT max(read_rows) FROM system.query_log WHERE "
                + "(query_id = '%1$s' OR initial_query_id = '%1$s') AND type = 'QueryFinish'").formatted(queryId)));
        assertThat(readRows).isBetween((long) ROWS, ROWS + 2L * 8192).isLessThan(3L * ROWS);
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, EXTENDED})
    @DisplayName("the settings that can stop row policies applying are pinned and cannot be overridden")
    void settingsArePinned(String user) {
        try (var account = client(user)) {
            PINNED.forEach((setting, value) -> {
                assertThat(single(account, "SELECT value FROM system.settings WHERE name = '%s'".formatted(setting)))
                        .as(setting).isEqualTo(value);
                // A valid value other than the pinned one, so only the pin can reject it.
                String other = setting.equals("max_parallel_replicas") ? "2" : "1".equals(value) ? "0" : "1";
                assertThatThrownBy(() -> account.queryAll("SELECT 1 SETTINGS %s = %s".formatted(setting, other)))
                        .as(setting).hasRootCauseInstanceOf(ServerException.class)
                        // 164 for most, 392 for allow_ddl; aliases report under their own names.
                        .rootCause().hasMessageContaining("Cannot modify '");
            });
        }
    }

    private Client client(String user) {
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);
        factory.setUsername(user);
        factory.setPassword("opik");
        return factory.buildClient();
    }

    private static String scope(String workspaceId, String projectId) {
        return "SQL_workspace_id = '%s', SQL_project_id = '%s'".formatted(workspaceId, projectId);
    }

    private static String single(Client client, String sql, String workspaceId, String projectId) {
        return single(client, sql + " SETTINGS " + scope(workspaceId, projectId));
    }

    private static String single(Client client, String sql) {
        return client.queryAll(sql).getFirst().getString(1);
    }
}
