package com.comet.opik.domain.cost;

import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.ClientErrorException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelPriceStoreTest {

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final ModelPriceStore store = new ModelPriceStore(UUID::randomUUID);
    private Jdbi jdbi;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
    }

    private static List<CustomModelPriceValidator.ValidRow> validRows(String json) {
        var nodes = new ArrayList<JsonNode>();
        JsonUtils.getJsonNodeFromString(json).forEach(nodes::add);
        var result = CustomModelPriceValidator.validate(nodes);
        assertThat(result.errors()).isEmpty();
        return result.rows();
    }

    private ModelPriceReplaceResult replace(String organizationId, String json, Long expectedVersion) {
        return jdbi.inTransaction(handle -> store.replaceAll(handle, organizationId, validRows(json), expectedVersion,
                "alice"));
    }

    private ModelPriceList load(String organizationId) {
        return jdbi.inTransaction(handle -> store.load(handle, organizationId));
    }

    private List<String> historyActions(String organizationId, long listVersion) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT action FROM model_prices_history"
                + " WHERE organization_id = :organizationId AND list_version = :listVersion ORDER BY action")
                .bind("organizationId", organizationId)
                .bind("listVersion", listVersion)
                .mapTo(String.class)
                .list());
    }

    private static final String TWO_ROWS = """
            [{"model": "team-mini", "provider": "azure", "kind": "alias",
              "alias_of": {"model": "gpt-5-mini", "provider": "azure"}},
             {"model": "gpt-5-mini", "provider": "azure", "kind": "fixed",
              "input_price": "1.00", "output_price": "10.00"}]
            """;

    @Test
    void loadOfAnOrganizationWithNoRowsIsAnEmptyListAtVersionZero() {
        var list = load(UUID.randomUUID().toString());

        assertThat(list.version()).isZero();
        assertThat(list.rows()).isEmpty();
    }

    @Test
    void replaceAllOnAnEmptyListSavesTheRowsAsVersionOne() {
        var organizationId = UUID.randomUUID().toString();

        var result = replace(organizationId, TWO_ROWS, 0L);

        assertThat(result.saved()).isTrue();
        assertThat(result.version()).isEqualTo(1);
        var list = load(organizationId);
        assertThat(list.version()).isEqualTo(1);
        assertThat(list.rows()).extracting(CustomModelPrice::modelName).containsExactly("gpt-5-mini", "team-mini");
        assertThat(list.rows().getFirst().inputPerMillion()).isEqualByComparingTo("1.00");
        assertThat(historyActions(organizationId, 1)).containsExactly("created", "created");
    }

    @Test
    void replaceAllWithTheSameRowsKeepsTheVersion() {
        var organizationId = UUID.randomUUID().toString();
        replace(organizationId, TWO_ROWS, null);

        var result = replace(organizationId, TWO_ROWS, null);

        assertThat(result.saved()).isFalse();
        assertThat(result.version()).isEqualTo(1);
        assertThat(load(organizationId).version()).isEqualTo(1);
        assertThat(historyActions(organizationId, 2)).isEmpty();
    }

    @Test
    void replaceAllWritesOneHistoryRowPerChangeUnderOneNewVersion() {
        var organizationId = UUID.randomUUID().toString();
        replace(organizationId, """
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00"},
                 {"model": "gpt-4o", "provider": "openai", "kind": "fixed", "input_price": "2.00"},
                 {"model": "gpt-4.1", "provider": "openai", "kind": "fixed", "input_price": "3.00"}]
                """, null);

        var result = replace(organizationId, """
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00"},
                 {"model": "gpt-4o", "provider": "openai", "kind": "fixed", "input_price": "2.50"},
                 {"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """, 1L);

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.diff().unchanged()).hasSize(1);
        assertThat(historyActions(organizationId, 2)).containsExactly("created", "deleted", "updated");
        assertThat(load(organizationId).rows()).extracting(CustomModelPrice::modelName)
                .containsExactly("gpt-4o", "gpt-5-mini", "o3");
    }

    @Test
    void replaceAllWithAStaleExpectedVersionIsAConflictAndSavesNothing() {
        var organizationId = UUID.randomUUID().toString();
        replace(organizationId, TWO_ROWS, null);

        assertThatThrownBy(() -> replace(organizationId, """
                [{"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """, 0L))
                .isInstanceOfSatisfying(ClientErrorException.class,
                        conflict -> assertThat(conflict.getResponse().getStatus()).isEqualTo(409));
        assertThat(load(organizationId).rows()).hasSize(2);
        assertThat(load(organizationId).version()).isEqualTo(1);
    }

    @Test
    void previewReplaceAllReturnsTheChangesWithoutWritingThem() {
        var organizationId = UUID.randomUUID().toString();
        replace(organizationId, TWO_ROWS, null);

        var preview = jdbi.inTransaction(handle -> store.previewReplaceAll(handle, organizationId, validRows("""
                [{"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """), 1L));

        assertThat(preview.saved()).isFalse();
        assertThat(preview.diff().created()).hasSize(1);
        assertThat(preview.diff().deleted()).hasSize(2);
        assertThat(load(organizationId).rows()).hasSize(2);
        assertThat(load(organizationId).version()).isEqualTo(1);
    }

    @Test
    void replaceAllKeepsAPerTokenSizedPriceExactly() {
        var organizationId = UUID.randomUUID().toString();

        replace(organizationId, """
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "0.00000025"}]
                """, null);

        assertThat(load(organizationId).rows().getFirst().inputPerMillion()).isEqualByComparingTo("0.00000025");
    }

    @Test
    void replaceAllOfOneOrganizationLeavesAnotherUntouched() {
        var first = UUID.randomUUID().toString();
        var second = UUID.randomUUID().toString();
        replace(second, TWO_ROWS, null);

        replace(first, "[]", null);
        replace(first, """
                [{"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """, null);

        assertThat(load(second).rows()).hasSize(2);
        assertThat(load(second).version()).isEqualTo(1);
    }
}
