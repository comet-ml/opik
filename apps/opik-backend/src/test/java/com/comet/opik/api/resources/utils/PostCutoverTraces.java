package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;
import org.testcontainers.clickhouse.ClickHouseContainer;

import java.sql.SQLException;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * Migrates a ClickHouse container to the post-cutover {@code traces} topology production runs: the changelog through
 * the shadow-table migration, then the runbook's EXCHANGE and Distributed wrap, then the rest of the changelog. For
 * code that reads only the cut-over {@code traces} (ai-labels); {@code TracesSchemaParityPostCutoverTest} pins the
 * splice itself. Insert fixtures into {@link #SHARD}: inserts through the Distributed wrapper are asynchronous.
 */
@UtilityClass
public class PostCutoverTraces {

    public static final String SHARD = "traces_local";

    private static final String SPLICE_POINT = "000114_recreate_traces_local_v2_id_at_datetime64.sql";

    public static void migrate(ClickHouseContainer clickHouse) throws SQLException {
        MigrationUtils.runClickhouseDbMigrationThrough(clickHouse, SPLICE_POINT);
        try (var connection = clickHouse.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("EXCHANGE TABLES %1$s.traces AND %1$s.traces_local_v2 ON CLUSTER '{cluster}'"
                    .formatted(DATABASE_NAME));
            statement.execute(
                    "RENAME TABLE %1$s.traces_local_v2 TO %1$s.traces_pre_cutover_backup ON CLUSTER '{cluster}'"
                            .formatted(DATABASE_NAME));
            statement.execute("""
                    CREATE TABLE %1$s.traces_dist ON CLUSTER '{cluster}' AS %1$s.traces
                    ENGINE = Distributed('{cluster}', '%1$s', '%2$s', sipHash64(project_id))
                    """.formatted(DATABASE_NAME, SHARD));
            statement.execute("""
                    RENAME TABLE %1$s.traces TO %1$s.%2$s, %1$s.traces_dist TO %1$s.traces ON CLUSTER '{cluster}'
                    """.formatted(DATABASE_NAME, SHARD));
        }
        MigrationUtils.runClickhouseDbMigration(clickHouse);
    }
}
