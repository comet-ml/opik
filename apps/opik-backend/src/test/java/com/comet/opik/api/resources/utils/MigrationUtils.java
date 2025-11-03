package com.comet.opik.api.resources.utils;

import liquibase.Liquibase;
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.experimental.UtilityClass;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.mysql.MySQLContainer;
import ru.yandex.clickhouse.ClickHouseConnectionImpl;

import java.sql.SQLException;
import java.util.Map;

@UtilityClass
public class MigrationUtils {

    public static final String MYSQL_CHANGELOG_FILE = "liquibase/db-app-state/changelog.xml";
    public static final String CLICKHOUSE_CHANGELOG_FILE = "liquibase/db-app-analytics/changelog.xml";

    public static void runMysqlDbMigration(Jdbi jdbi) {
        try (var handle = jdbi.open()) {
            runDbMigration(MYSQL_CHANGELOG_FILE, MySQLContainerUtils.migrationParameters(),
                    new JdbcConnection(handle.getConnection()));
        }
    }

    public static void runMysqlDbMigration(MySQLContainer mysqlContainer) {
        try (var connection = mysqlContainer.createConnection("")) {
            runDbMigration(MYSQL_CHANGELOG_FILE, MySQLContainerUtils.migrationParameters(),
                    new JdbcConnection(connection));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run MySQL DB migration", e);
        }
    }

    public static void runClickhouseDbMigration(ClickHouseContainer container) {
        try (var connection = container.createConnection("")) {
            DatabaseConnection dbConnection = new JdbcConnection(
                    new ClickHouseConnectionImpl(connection.getMetaData().getURL()));
            runDbMigration(CLICKHOUSE_CHANGELOG_FILE, ClickHouseContainerUtils.migrationParameters(), dbConnection);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run ClickHouse DB migration", e);
        }
    }

    private static void runDbMigration(String changeLogFile, Map<String, String> parameters,
            DatabaseConnection connection) {
        try {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(connection);
            try (var liquibase = new Liquibase(changeLogFile, new ClassLoaderResourceAccessor(), database)) {
                parameters.forEach(liquibase::setChangeLogParameter);
                liquibase.update("updateSql");
            }
        } catch (LiquibaseException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }
}
