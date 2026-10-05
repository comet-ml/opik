package com.comet.opik.db;

import com.clickhouse.client.api.Client;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs provision_agent_insights_readonly_user.sh itself against a migrated ClickHouse that has none of the read-only
 * accounts, the way docker-compose and dev-runner.sh do. FreeFormSqlRowPolicyConformanceTest covers the same grants,
 * policies and pins through users.xml; this covers the script's own path: its DDL, its checks, and a rerun over
 * existing state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Agent Insights read-only user provisioning script")
class ProvisionAgentInsightsReadonlyUserTest {

    private static final String SCRIPT = "provision_agent_insights_readonly_user.sh";
    private static final String STANDARD_USER = "comet_readonly_freeform_sql_user";
    private static final String EXTENDED_USER = "comet_readonly_freeform_extended_sql_user";
    /** The script defaults an empty password to opik, so its admin needs one. */
    private static final String ADMIN = "provisioning_admin";
    private static final String PASSWORD = "opik";
    private static final String WORKSPACE_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_B = UUID.randomUUID().toString();
    private static final String PROJECT_A1 = UUID.randomUUID().toString();
    private static final String PROJECT_A2 = UUID.randomUUID().toString();
    private static final int ROWS = 10;

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils
            .newClickHouseContainerWithoutReadOnlyUsers(network, zookeeper);
    private Client admin;

    @BeforeAll
    void setUpAll() {
        Startables.deepStart(zookeeper, clickHouse).join();
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        admin = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME).buildClient();
        admin.queryAll("CREATE USER %s IDENTIFIED BY '%s'".formatted(ADMIN, PASSWORD));
        admin.queryAll("GRANT CURRENT GRANTS ON *.* TO %s WITH GRANT OPTION".formatted(ADMIN));
        for (var scope : new String[][]{{WORKSPACE_A, PROJECT_A1}, {WORKSPACE_A, PROJECT_A2},
                {WORKSPACE_B, UUID.randomUUID().toString()}}) {
            admin.queryAll(("INSERT INTO %s.traces (workspace_id, project_id, id) SELECT '%s', '%s', "
                    + "toString(generateUUIDv7()) FROM numbers(%d)").formatted(DATABASE_NAME, scope[0], scope[1],
                            ROWS));
        }
    }

    @AfterAll
    void tearDownAll() {
        admin.close();
        clickHouse.stop();
        zookeeper.stop();
        network.close();
    }

    @Test
    @Order(1)
    @DisplayName("provisions both accounts, each scoped by its row policies, with the local tables covered")
    void provisionsScopedAccounts() throws Exception {
        assertThat(provision().exitCode()).isZero();

        assertThat(count(STANDARD_USER, WORKSPACE_A, PROJECT_A1)).isEqualTo(ROWS);
        assertThat(count(EXTENDED_USER, WORKSPACE_A, "*")).as("optional project: the whole workspace")
                .isEqualTo(2 * ROWS);
        assertThat(count(STANDARD_USER, WORKSPACE_A, "*")).as("the standard account stays project-bound").isZero();
        for (var table : new String[]{"traces_local", "spans_local"}) {
            assertThat(single("SELECT count() FROM system.row_policies WHERE database = '%s' AND table = '%s'"
                    .formatted(DATABASE_NAME, table))).as(table).isEqualTo("2");
        }
        assertThat(single("SELECT count() FROM system.settings_profile_elements WHERE profile_name = "
                + "'comet_llm_readonly_freeform_sql_profile' AND setting_name = 'readonly' AND writability = 'CONST'"))
                .isEqualTo("1");
    }

    @Test
    @Order(2)
    @DisplayName("a rerun over the existing accounts succeeds and leaves the scope as it was")
    void rerunIsIdempotent() throws Exception {
        assertThat(provision().exitCode()).isZero();
        assertThat(count(STANDARD_USER, WORKSPACE_A, PROJECT_A1)).isEqualTo(ROWS);
    }

    @Test
    @Order(3)
    @DisplayName("a table the account can read without a row policy fails the provisioning")
    void grantWithoutPolicyFails() throws Exception {
        admin.queryAll("GRANT SELECT ON %s.projects TO %s".formatted(DATABASE_NAME, STANDARD_USER));
        try {
            var run = provision();
            assertThat(run.exitCode()).isNotZero();
            assertThat(run.output()).contains("'%s' can SELECT projects with no row policy".formatted(STANDARD_USER));
        } finally {
            admin.queryAll("REVOKE SELECT ON %s.projects FROM %s".formatted(DATABASE_NAME, STANDARD_USER));
        }
    }

    private record Run(int exitCode, String output) {
    }

    private Run provision() throws IOException, InterruptedException {
        var process = new ProcessBuilder("bash", SCRIPT).redirectErrorStream(true);
        process.environment().putAll(Map.of(
                "TOGGLE_OLLIE_ENABLED", "true",
                "ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_USER_ENABLED", "true",
                "ANALYTICS_DB_HOST", clickHouse.getHost(),
                "ANALYTICS_DB_PORT", String.valueOf(clickHouse.getMappedPort(8123)),
                "ANALYTICS_DB_USERNAME", ADMIN,
                "ANALYTICS_DB_PASS", PASSWORD,
                "ANALYTICS_DB_DATABASE_NAME", DATABASE_NAME));
        var started = process.start();
        String output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(started.waitFor(2, TimeUnit.MINUTES)).as("the script finished").isTrue();
        return new Run(started.exitValue(), output);
    }

    private long count(String user, String workspace, String project) {
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);
        factory.setUsername(user);
        factory.setPassword(PASSWORD);
        try (var client = factory.buildClient()) {
            return Long.parseLong(client.queryAll(("SELECT count() FROM %s.traces SETTINGS SQL_workspace_id = '%s', "
                    + "SQL_project_id = '%s'").formatted(DATABASE_NAME, workspace, project)).getFirst().getString(1));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String single(String sql) {
        return admin.queryAll(sql).getFirst().getString(1);
    }
}
