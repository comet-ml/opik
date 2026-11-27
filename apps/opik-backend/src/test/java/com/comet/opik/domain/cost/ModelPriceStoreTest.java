package com.comet.opik.domain.cost;

import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.ClientErrorException;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelPriceStoreTest {

    private static final String USER_NAME = RandomStringUtils.secure().nextAlphanumeric(10);
    private static final RecursiveComparisonConfiguration PRICES_BY_VALUE = RecursiveComparisonConfiguration.builder()
            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .build();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final ModelPriceStore store = new ModelPriceStore(TestIdGeneratorFactory.create()::generateId);
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

    private static List<CustomModelPrice> prices(String json) {
        return validRows(json).stream()
                .map(CustomModelPriceValidator.ValidRow::price)
                .sorted(Comparator.comparing(CustomModelPrice::modelName).thenComparing(CustomModelPrice::provider))
                .toList();
    }

    private static String newOrganizationId() {
        return UUID.randomUUID().toString();
    }

    private ModelPriceReplaceResult replace(String organizationId, String json, Long expectedVersion) {
        return jdbi.inTransaction(handle -> store.replaceAll(handle, organizationId, validRows(json), expectedVersion,
                USER_NAME));
    }

    private ModelPriceList load(String organizationId) {
        return jdbi.inTransaction(handle -> store.load(handle, organizationId));
    }

    private void assertLoaded(String organizationId, long version, String json) {
        assertThat(load(organizationId))
                .usingRecursiveComparison(PRICES_BY_VALUE)
                .isEqualTo(ModelPriceList.builder()
                        .organizationId(organizationId)
                        .version(version)
                        .rows(prices(json))
                        .build());
    }

    private List<ModelPriceHistoryAction> historyActions(String organizationId, long listVersion) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                SELECT action FROM model_prices_history
                WHERE organization_id = :organizationId AND list_version = :listVersion
                """)
                .bind("organizationId", organizationId)
                .bind("listVersion", listVersion)
                .mapTo(String.class)
                .map(ModelPriceHistoryAction::fromString)
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
        var organizationId = newOrganizationId();

        assertThat(load(organizationId)).isEqualTo(ModelPriceList.builder()
                .organizationId(organizationId)
                .version(0)
                .rows(List.of())
                .build());
    }

    @Test
    void replaceAllOnAnEmptyListSavesTheRowsAsVersionOne() {
        var organizationId = newOrganizationId();

        var result = replace(organizationId, TWO_ROWS, 0L);

        assertThat(result.saved()).isTrue();
        assertThat(result.version()).isEqualTo(1);
        assertLoaded(organizationId, 1, TWO_ROWS);
        assertThat(historyActions(organizationId, 1))
                .containsExactlyInAnyOrder(ModelPriceHistoryAction.CREATED, ModelPriceHistoryAction.CREATED);
    }

    @Test
    void replaceAllSavesNamesThatDifferOnlyByAnAccentAsTwoRows() {
        var organizationId = newOrganizationId();
        var rows = """
                [{"model": "modèle", "provider": "openai", "kind": "fixed", "input_price": "1.00"},
                 {"model": "modele", "provider": "openai", "kind": "fixed", "input_price": "2.00"}]
                """;

        var result = replace(organizationId, rows, null);

        assertThat(result.saved()).isTrue();
        assertLoaded(organizationId, 1, rows);
    }

    @Test
    void replaceAllWithTheSameRowsKeepsTheVersion() {
        var organizationId = newOrganizationId();
        replace(organizationId, TWO_ROWS, null);

        var result = replace(organizationId, TWO_ROWS, null);

        assertThat(result.saved()).isFalse();
        assertThat(result.version()).isEqualTo(1);
        assertLoaded(organizationId, 1, TWO_ROWS);
        assertThat(historyActions(organizationId, 2)).isEmpty();
    }

    @Test
    void replaceAllWritesOneHistoryRowPerChangeUnderOneNewVersion() {
        var organizationId = newOrganizationId();
        replace(organizationId, """
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00"},
                 {"model": "gpt-4o", "provider": "openai", "kind": "fixed", "input_price": "2.00"},
                 {"model": "gpt-4.1", "provider": "openai", "kind": "fixed", "input_price": "3.00"}]
                """, null);
        var after = """
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00"},
                 {"model": "gpt-4o", "provider": "openai", "kind": "fixed", "input_price": "2.50"},
                 {"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """;

        var result = replace(organizationId, after, 1L);

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.diff().unchanged())
                .extracting(ModelPriceRow::toPrice)
                .usingRecursiveFieldByFieldElementComparator(PRICES_BY_VALUE)
                .containsExactlyElementsOf(prices("""
                        [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00"}]
                        """));
        assertThat(historyActions(organizationId, 2)).containsExactlyInAnyOrder(ModelPriceHistoryAction.CREATED,
                ModelPriceHistoryAction.DELETED, ModelPriceHistoryAction.UPDATED);
        assertLoaded(organizationId, 2, after);
    }

    @Test
    void replaceAllReturnsTheCreatedAndUpdatedRowsAsStored() {
        var organizationId = newOrganizationId();
        replace(organizationId, """
                [{"model": "gpt-4o", "provider": "openai", "kind": "fixed", "input_price": "2.00"}]
                """, null);

        var result = replace(organizationId, """
                [{"model": "gpt-4o", "provider": "openai", "kind": "fixed", "input_price": "2.50"},
                 {"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """, 1L);

        var stored = jdbi.withHandle(handle -> handle.attach(ModelPriceDAO.class).findRows(organizationId));
        assertThat(Stream.concat(result.diff().created().stream(),
                result.diff().updated().stream().map(ModelPriceDiff.Change::after)))
                .containsExactlyInAnyOrderElementsOf(stored);
        assertThat(stored).allSatisfy(row -> {
            assertThat(row.createdAt()).isNotNull();
            assertThat(row.lastUpdatedAt()).isNotNull();
            assertThat(row.lastUpdatedBy()).isEqualTo(USER_NAME);
        });
    }

    @Test
    void concurrentReplaceAllsOfOneListRunOneAfterTheOther() throws Exception {
        var organizationId = newOrganizationId();
        replace(organizationId, TWO_ROWS, null);
        int saves = 4;
        var start = new CyclicBarrier(saves);

        try (var executor = Executors.newFixedThreadPool(saves)) {
            var results = IntStream.range(0, saves)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return replace(organizationId, """
                                [{"model": "o%d", "provider": "openai", "kind": "fixed", "input_price": "1.00"}]
                                """.formatted(index), null);
                    }))
                    .toList();
            for (var result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS).saved()).isTrue();
            }
        }

        assertThat(load(organizationId).version()).isEqualTo(1 + saves);
    }

    @Test
    void replaceAllWithAStaleExpectedVersionIsAConflictAndSavesNothing() {
        var organizationId = newOrganizationId();
        replace(organizationId, TWO_ROWS, null);

        assertThatThrownBy(() -> replace(organizationId, """
                [{"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """, 0L))
                .isInstanceOfSatisfying(ClientErrorException.class,
                        conflict -> assertThat(conflict.getResponse().getStatus()).isEqualTo(409));
        assertLoaded(organizationId, 1, TWO_ROWS);
    }

    @Test
    void previewReplaceAllReturnsTheChangesWithoutWritingThem() {
        var organizationId = newOrganizationId();
        replace(organizationId, TWO_ROWS, null);
        var replacement = """
                [{"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """;

        var preview = jdbi.inTransaction(
                handle -> store.previewReplaceAll(handle, organizationId, validRows(replacement), 1L));

        assertThat(preview.saved()).isFalse();
        assertThat(preview.diff().created())
                .extracting(ModelPriceRow::toPrice)
                .usingRecursiveFieldByFieldElementComparator(PRICES_BY_VALUE)
                .containsExactlyElementsOf(prices(replacement));
        assertThat(preview.diff().deleted())
                .extracting(ModelPriceRow::toPrice)
                .usingRecursiveFieldByFieldElementComparator(PRICES_BY_VALUE)
                .containsExactlyInAnyOrderElementsOf(prices(TWO_ROWS));
        assertLoaded(organizationId, 1, TWO_ROWS);
    }

    @Test
    void replaceAllKeepsAPerTokenSizedPriceExactly() {
        var organizationId = newOrganizationId();
        var rows = """
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "0.00000025"}]
                """;

        replace(organizationId, rows, null);

        assertLoaded(organizationId, 1, rows);
    }

    @Test
    void replaceAllOfOneOrganizationLeavesAnotherUntouched() {
        var first = newOrganizationId();
        var second = newOrganizationId();
        replace(second, TWO_ROWS, null);

        replace(first, "[]", null);
        replace(first, """
                [{"model": "o3", "provider": "openai", "kind": "fixed", "input_price": "4.00"}]
                """, null);

        assertLoaded(second, 1, TWO_ROWS);
    }
}
