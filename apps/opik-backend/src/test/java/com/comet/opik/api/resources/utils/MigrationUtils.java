package com.comet.opik.api.resources.utils;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
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
import java.util.List;
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

    /**
     * Applies the ClickHouse changelog only up to and including the changesets of {@code migrationFileName}, leaving
     * every later migration unrun so a caller can transform the schema mid-changelog and then resume with
     * {@link #runClickhouseDbMigration(ClickHouseContainer)}.
     * <p>
     * This exists for the post-cutover topology gate: the cutover's {@code EXCHANGE} + {@code Distributed} wrap is
     * produced by the operator runbook rather than by Liquibase, so the only way to run the later migrations against
     * the topology they will really meet in production is to stop the changelog at the shadow-table migration, splice
     * the transform in, and carry on. The cut is expressed as a migration <i>file name</i> rather than a changeset
     * count so appending migrations never silently moves it.
     *
     * @param migrationFileName the migration file the apply stops after, e.g.
     *            {@code 000114_recreate_traces_local_v2_id_at_datetime64.sql}
     */
    public static void runClickhouseDbMigrationThrough(ClickHouseContainer container, String migrationFileName) {
        try (var connection = container.createConnection("")) {
            DatabaseConnection dbConnection = new JdbcConnection(
                    new ClickHouseConnectionImpl(connection.getMetaData().getURL()));
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(dbConnection);
            try (var liquibase = new Liquibase(CLICKHOUSE_CHANGELOG_FILE, new ClassLoaderResourceAccessor(),
                    database)) {
                ClickHouseContainerUtils.migrationParameters().forEach(liquibase::setChangeLogParameter);
                liquibase.update(countChangeSetsThrough(liquibase, migrationFileName), new Contexts(),
                        new LabelExpression());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run ClickHouse DB migration", e);
        } catch (LiquibaseException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    /**
     * Identifiers of the ClickHouse changesets the database has <b>not</b> applied. Empty means the changelog is fully
     * applied — which is the assertion a topology gate needs after resuming a spliced apply, because a changeset the
     * extension skipped (a precondition evaluating to {@code MARK_RAN} is recorded as run; an unsupported statement is
     * not) would otherwise leave the schema short without anything throwing.
     */
    public static List<String> unrunClickhouseChangeSetIds(ClickHouseContainer container) {
        try (var connection = container.createConnection("")) {
            DatabaseConnection dbConnection = new JdbcConnection(
                    new ClickHouseConnectionImpl(connection.getMetaData().getURL()));
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(dbConnection);
            try (var liquibase = new Liquibase(CLICKHOUSE_CHANGELOG_FILE, new ClassLoaderResourceAccessor(),
                    database)) {
                ClickHouseContainerUtils.migrationParameters().forEach(liquibase::setChangeLogParameter);
                return liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression())
                        .stream()
                        .map(ChangeSet::getId)
                        .toList();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list unrun ClickHouse changesets", e);
        } catch (LiquibaseException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    /**
     * Number of changesets from the start of the changelog through the last one declared in {@code migrationFileName}.
     * The changelog is a single {@code includeAll}, so this is the file's position in lexicographic order; counting the
     * parsed changesets rather than the files keeps it right for a migration that declares more than one.
     */
    private static int countChangeSetsThrough(Liquibase liquibase, String migrationFileName)
            throws LiquibaseException {
        var changeSets = liquibase.getDatabaseChangeLog().getChangeSets();
        for (int i = changeSets.size() - 1; i >= 0; i--) {
            if (changeSets.get(i).getFilePath().endsWith(migrationFileName)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(
                "No changeset found for migration file '%s' in %s".formatted(migrationFileName,
                        CLICKHOUSE_CHANGELOG_FILE));
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
