package com.comet.opik.db;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
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
import ru.yandex.clickhouse.ClickHouseConnectionImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the recovery path that {@code rebaseline_db_changelog.sh} automates: when the ledger is
 * lost but the schema is intact, {@code changelogSync} (Dropwizard's {@code fast-forward --all})
 * restores the ledger without executing DDL, and a subsequent startup migration is a clean no-op.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Changelog Re-baseline")
class ChangelogRebaselineTest {

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    {
        Startables.deepStart(zookeeper, clickHouse).join();
    }

    @BeforeAll
    void migrate() {
        MigrationUtils.runClickhouseDbMigration(clickHouse);
    }

    @AfterAll
    void tearDown() {
        clickHouse.stop();
        zookeeper.stop();
    }

    @Test
    @Order(1)
    @DisplayName("replaying a wiped ledger against an intact schema fails")
    void replayingWipedLedgerFails() throws Exception {
        long applied = countLedgerRows();
        assertThat(applied).as("baseline migration recorded changesets").isPositive();

        wipeLedger();
        assertThat(countLedgerRows()).isZero();

        // This is the failure the recovery procedure exists to avoid: without re-baselining, a
        // starting replica replays every changeset against tables that already exist.
        assertThatThrownBy(() -> MigrationUtils.runClickhouseDbMigration(clickHouse))
                .hasMessageContaining("000001_init_script.sql");
    }

    @Test
    @Order(2)
    @DisplayName("changelogSync restores the ledger without executing DDL, and startup is then a no-op")
    void changelogSyncRestoresLedger() throws Exception {
        var tablesBefore = countTables();
        assertThat(countLedgerRows()).as("ledger is still empty from the previous step").isZero();

        changelogSync();

        assertThat(countLedgerRows())
                .as("every changeset is now recorded as applied")
                .isPositive();
        assertThat(countTables())
                .as("changelogSync writes ledger rows only — the schema must be untouched")
                .isEqualTo(tablesBefore);

        // The point of the whole exercise: a replica starting after recovery migrates cleanly.
        assertThatCode(() -> MigrationUtils.runClickhouseDbMigration(clickHouse))
                .doesNotThrowAnyException();
    }

    private void changelogSync() throws Exception {
        try (var connection = clickHouse.createConnection("")) {
            var dbConnection = new JdbcConnection(new ClickHouseConnectionImpl(connection.getMetaData().getURL()));
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(dbConnection);
            try (var liquibase = new Liquibase(MigrationUtils.CLICKHOUSE_CHANGELOG_FILE,
                    new ClassLoaderResourceAccessor(), database)) {
                ClickHouseContainerUtils.migrationParameters().forEach(liquibase::setChangeLogParameter);
                liquibase.changeLogSync("");
            }
        }
    }

    private void wipeLedger() throws Exception {
        try (var connection = clickHouse.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE default.DATABASECHANGELOG");
        }
    }

    private long countLedgerRows() throws Exception {
        return queryCount("SELECT count() FROM default.DATABASECHANGELOG");
    }

    private long countTables() throws Exception {
        return queryCount("SELECT count() FROM system.tables WHERE database = '%s'"
                .formatted(ClickHouseContainerUtils.DATABASE_NAME));
    }

    private long queryCount(String sql) throws Exception {
        try (var connection = clickHouse.createConnection("");
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
