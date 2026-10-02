package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@link FreeFormSqlPostRunCheckTest} case with traces and spans wrapped as Distributed over traces_local and
 * spans_local, the production topology, reached with the cutover runbook's statements.
 */
@DisplayName("Free-form SQL post-run policy check, end to end, post-cutover")
class FreeFormSqlPostRunCheckPostCutoverTest extends FreeFormSqlPostRunCheckTest {

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

    @Test
    @DisplayName("the shard-side read of traces_local without its policy is withheld: the original gap")
    void missingLocalPolicyIsWithheld() {
        assertThatThrownBy(() -> partialService().executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE_A, PROJECT_A,
                "SELECT toJSONString(map('n', toString(count()))) AS result FROM traces").join())
                .isInstanceOf(CompletionException.class)
                .cause().satisfies(FreeFormSqlQueryServiceTest.withheld(500));
    }

    private static void run(Client admin, String sql, String table) {
        admin.queryAll(sql.formatted(DATABASE_NAME, table));
    }
}
