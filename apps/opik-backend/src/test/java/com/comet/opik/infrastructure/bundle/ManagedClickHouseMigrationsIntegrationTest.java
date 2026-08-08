package com.comet.opik.infrastructure.bundle;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedClickHouseMigrationsIntegrationTest {

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeper);

    @BeforeAll
    void setUp() throws SQLException {
        Startables.deepStart(clickHouse).join();
        execute("""
                CREATE DATABASE IF NOT EXISTS opik
                ENGINE = Replicated('/clickhouse/databases/opik', '{shard}', '{replica}')
                """);
    }

    @AfterAll
    void tearDown() {
        if (clickHouse.isRunning()) {
            clickHouse.stop();
        }
        if (zookeeper.isRunning()) {
            zookeeper.stop();
        }
        network.close();
    }

    @Test
    void runsCompleteManagedChangelogAndRerunsIdempotently() throws SQLException {
        var accessor = new ManagedClickHouseMigrationResourceAccessor();

        MigrationUtils.runClickhouseDbMigration(clickHouse, accessor);
        var appliedChangeSets = queryLong("SELECT count() FROM DATABASECHANGELOG");

        assertThat(queryString("""
                SELECT engine FROM system.tables
                WHERE database = 'opik' AND name = 'alert_logs'
                """)).isEqualTo("ReplicatedMergeTree");
        assertThat(queryString("""
                SELECT engine FROM system.tables
                WHERE database = 'opik' AND name = 'comments'
                """)).isEqualTo("ReplicatedReplacingMergeTree");

        MigrationUtils.runClickhouseDbMigration(clickHouse, new ManagedClickHouseMigrationResourceAccessor());
        assertThat(queryLong("SELECT count() FROM DATABASECHANGELOG")).isEqualTo(appliedChangeSets);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = clickHouse.createConnection(""); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (var connection = clickHouse.createConnection("");
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (var connection = clickHouse.createConnection("");
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }
}
