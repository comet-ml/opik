package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ai-labels migrations apply, roll back and re-apply cleanly. Runs on containers of its own, never the reused
 * ones: rolling back drops tables other suites read.
 */
@DisplayName("ai-labels migrations")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiLabelsMigrationsTest {

    private static final String LAST_CLICKHOUSE_MIGRATION = "000135_create_ai_labels.sql";
    private static final String LAST_MYSQL_MIGRATION = "000108_create_ai_label_jobs.sql";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);

    {
        Startables.deepStart(zookeeper, clickHouse, mysql).join();
    }

    @AfterAll
    void tearDown() {
        clickHouse.stop();
        zookeeper.stop();
        mysql.stop();
        network.close();
    }

    @Test
    @DisplayName("ClickHouse: ai_label_status and ai_labels apply, roll back and re-apply")
    void clickHouseMigrationsApplyAndRollBack() throws SQLException {
        var query = "SELECT name FROM system.tables WHERE database = 'opik' AND name IN ('ai_label_status', 'ai_labels')";

        MigrationUtils.runClickhouseDbMigrationThrough(clickHouse, LAST_CLICKHOUSE_MIGRATION);
        assertThat(names(clickHouse, query)).containsExactlyInAnyOrder("ai_label_status", "ai_labels");

        MigrationUtils.rollbackClickhouseDbMigration(clickHouse, 2);
        assertThat(names(clickHouse, query)).isEmpty();

        MigrationUtils.runClickhouseDbMigrationThrough(clickHouse, LAST_CLICKHOUSE_MIGRATION);
        assertThat(names(clickHouse, query)).containsExactlyInAnyOrder("ai_label_status", "ai_labels");
    }

    @Test
    @DisplayName("MySQL: ai_label_workspaces and ai_label_jobs apply, roll back and re-apply")
    void mysqlMigrationsApplyAndRollBack() throws SQLException {
        var query = "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name IN ('ai_label_workspaces', 'ai_label_jobs')";

        MigrationUtils.runMysqlDbMigrationThrough(mysql, LAST_MYSQL_MIGRATION);
        assertThat(names(mysql, query)).containsExactlyInAnyOrder("ai_label_workspaces", "ai_label_jobs");

        MigrationUtils.rollbackMysqlDbMigration(mysql, 2);
        assertThat(names(mysql, query)).isEmpty();

        MigrationUtils.runMysqlDbMigrationThrough(mysql, LAST_MYSQL_MIGRATION);
        assertThat(names(mysql, query)).containsExactlyInAnyOrder("ai_label_workspaces", "ai_label_jobs");
    }

    private static List<String> names(JdbcDatabaseContainer<?> container, String query) throws SQLException {
        try (var connection = container.createConnection("");
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(query)) {
            var names = new ArrayList<String>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names;
        }
    }
}
