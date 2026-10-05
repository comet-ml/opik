package com.comet.opik.db;

import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link FreeFormSqlRowPolicyConformanceTest} case on the post-cutover topology production runs: traces and
 * spans are Distributed wrappers over traces_local and spans_local, reached with the cutover runbook's own statements
 * (see {@link TracesSchemaParityPostCutoverTest}). The local tables' policies exist before the wrap creates the
 * tables, as the provisioning creates them by name, so this also shows a wrap opens no gap.
 */
@DisplayName("Free-form SQL row policy conformance, post-cutover")
class FreeFormSqlRowPolicyConformancePostCutoverTest extends FreeFormSqlRowPolicyConformanceTest {

    @Override
    void prepareTopology(Client admin) {
        for (String table : List.of("traces", "spans")) {
            run(admin, "EXCHANGE TABLES %1$s.%2$s AND %1$s.%2$s_local_v2 ON CLUSTER '{cluster}'", table);
            run(admin, "RENAME TABLE %1$s.%2$s_local_v2 TO %1$s.%2$s_pre_cutover_backup ON CLUSTER '{cluster}'", table);
            run(admin, "CREATE TABLE %1$s.%2$s_dist ON CLUSTER '{cluster}' AS %1$s.%2$s "
                    + "ENGINE = Distributed('{cluster}', '%1$s', '%2$s_local', sipHash64(project_id))", table);
            run(admin, "RENAME TABLE %1$s.%2$s TO %1$s.%2$s_local, %1$s.%2$s_dist TO %1$s.%2$s ON CLUSTER '{cluster}'",
                    table);
            assertThat(admin.queryAll("SELECT engine FROM system.tables WHERE database = '%s' AND name = '%s'"
                    .formatted(DATABASE_NAME, table)).getFirst().getString(1)).as(table).isEqualTo("Distributed");
        }
    }

    private static void run(Client admin, String sql, String table) {
        admin.queryAll(sql.formatted(DATABASE_NAME, table));
    }
}
