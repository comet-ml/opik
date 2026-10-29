package com.comet.opik.db;

import com.clickhouse.client.api.Client;

import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * The table topologies the free-form SQL suites run on, as a parameter rather than a subclass: the migrated schema
 * as is, and traces and spans wrapped as Distributed over their local tables, which is production's topology and
 * where a policy on the wrapper alone does not scope the shard's read. The wrap is the plain table change, with no
 * data in it: the suites seed through the API after each topology is in place.
 */
public enum FreeFormSqlTopology {

    MIGRATED {
        @Override
        public void apply(Client admin) {
        }
    },
    DISTRIBUTED {
        @Override
        public void apply(Client admin) {
            for (String table : List.of("traces", "spans")) {
                run(admin, """
                        EXCHANGE TABLES %1$s.%2$s AND %1$s.%2$s_local_v2 ON CLUSTER '{cluster}'
                        """, table);
                run(admin, """
                        RENAME TABLE %1$s.%2$s_local_v2 TO %1$s.%2$s_pre_cutover_backup ON CLUSTER '{cluster}'
                        """, table);
                run(admin, """
                        CREATE TABLE %1$s.%2$s_dist ON CLUSTER '{cluster}' AS %1$s.%2$s
                        ENGINE = Distributed('{cluster}', '%1$s', '%2$s_local', sipHash64(project_id))
                        """, table);
                run(admin, """
                        RENAME TABLE %1$s.%2$s TO %1$s.%2$s_local, %1$s.%2$s_dist TO %1$s.%2$s ON CLUSTER '{cluster}'
                        """, table);
                String engine = admin.queryAll("""
                        SELECT engine_full FROM system.tables WHERE database = '%s' AND name = '%s'
                        """.formatted(DATABASE_NAME, table)).getFirst().getString(1);
                if (!engine.startsWith("Distributed(") || !engine.contains("'%s_local'".formatted(table))) {
                    throw new IllegalStateException("%s is not Distributed over %s_local: %s".formatted(table, table,
                            engine));
                }
            }
        }
    };

    /** Brings the migrated schema to this topology. */
    public abstract void apply(Client admin);

    private static void run(Client admin, String sql, String table) {
        admin.queryAll(sql.formatted(DATABASE_NAME, table));
    }
}
