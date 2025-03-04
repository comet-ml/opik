package com.comet.opik.api.resources.utils;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.experimental.UtilityClass;
import org.jdbi.v3.core.Jdbi;
import ru.yandex.clickhouse.ClickHouseConnectionImpl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

@UtilityClass
public class MigrationUtils {

    public static final String MYSQL_CHANGELOG_FILE = "liquibase/db-app-state/changelog.xml";
    public static final String CLICKHOUSE_CHANGELOG_FILE = "liquibase/db-app-analytics/changelog.xml";

    public static void runDbMigration(Jdbi jdbi, Map<String, String> parameters) {
        try (var handle = jdbi.open()) {
            runDbMigration(handle.getConnection(), MYSQL_CHANGELOG_FILE, parameters);
        }
    }

    public static void runDbMigration(Connection connection, Map<String, String> parameters) {
        runDbMigration(connection, MYSQL_CHANGELOG_FILE, parameters);
    }

    public static void runClickhouseDbMigration(Connection connection, String changeLogFile,
            Map<String, String> parameters) {
        try {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(
                            new JdbcConnection(new ClickHouseConnectionImpl(connection.getMetaData().getURL())));
            try (var liquibase = new Liquibase(changeLogFile, new ClassLoaderResourceAccessor(), database)) {
                parameters.forEach(liquibase::setChangeLogParameter);
                liquibase.update("updateSql");
            }
        } catch (LiquibaseException e) {
            throw new UnexpectedLiquibaseException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void runDbMigration(Connection connection, String changeLogFile, Map<String, String> parameters) {
        try {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(changeLogFile, new ClassLoaderResourceAccessor(), database)) {
                parameters.forEach(liquibase::setChangeLogParameter);
                liquibase.update("updateSql");
            }
        } catch (LiquibaseException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }
}
