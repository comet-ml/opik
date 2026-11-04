package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.modelprice.CustomModelPriceHistoryPage;
import com.comet.opik.api.modelprice.CustomModelPriceList;
import com.comet.opik.api.modelprice.CustomModelPriceProblems;
import com.comet.opik.api.modelprice.CustomModelPriceReplaceResult;
import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.modelprice.CustomModelPriceRow;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.domain.cost.CustomModelPriceValidator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ModelPricesResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/model-prices";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(new CustomConfig("serviceToggles.modelPricesEnabled", "true")))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);
    }

    private static ObjectNode fixed(String model, String provider, String inputPrice, String outputPrice) {
        return JsonUtils.createObjectNode()
                .put("model", model)
                .put("provider", provider)
                .put("kind", "fixed")
                .put("input_price", inputPrice)
                .put("output_price", outputPrice);
    }

    private static ObjectNode alias(String model, String provider, String targetModel, String targetProvider) {
        var node = JsonUtils.createObjectNode()
                .put("model", model)
                .put("provider", provider)
                .put("kind", "alias");
        node.putObject("alias_of").put("model", targetModel).put("provider", targetProvider);
        return node;
    }

    private static Map<String, Object> body(List<? extends JsonNode> rows, long expectedVersion) {
        return Map.of("rows", rows, "expected_version", expectedVersion);
    }

    private CustomModelPriceList find() {
        try (var response = client.target(URL_TEMPLATE.formatted(baseURI)).request().get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(CustomModelPriceList.class);
        }
    }

    private Response put(Map<String, Object> body, boolean dryRun) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .queryParam("dry_run", dryRun)
                .request()
                .put(Entity.json(body));
    }

    private CustomModelPriceReplaceResult replace(List<? extends JsonNode> rows) {
        try (var response = put(body(rows, find().version()), false)) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(CustomModelPriceReplaceResult.class);
        }
    }

    private CustomModelPriceResolution resolve(String model, String provider, Map<String, Integer> usage) {
        try (var response = client.target(URL_TEMPLATE.formatted(baseURI) + "/resolve")
                .request()
                .post(Entity.json(Map.of("model", model, "provider", provider, "usage", usage)))) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(CustomModelPriceResolution.class);
        }
    }

    @Test
    void replaceSavesTheRowsUnderTheDefaultOrganizationAndRaisesTheVersion() {
        replace(List.of());
        long before = find().version();

        var result = replace(List.of(alias("aide-gpt-5-mini", "azure", "gpt-5-mini", "azure"),
                fixed("gpt-5-mini", "azure", "1.00", "10.00")));

        assertThat(result.saved()).isTrue();
        assertThat(result.version()).isEqualTo(before + 1);
        assertThat(result.created()).extracting(CustomModelPriceRow::model)
                .containsExactlyInAnyOrder("aide-gpt-5-mini", "gpt-5-mini");
        var list = find();
        assertThat(list.version()).isEqualTo(before + 1);
        assertThat(list.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.id()).isNotNull();
            assertThat(row.createdAt()).isNotNull();
        });
        assertThat(list.rows()).filteredOn(row -> row.kind().equals("fixed")).singleElement()
                .satisfies(row -> {
                    assertThat(row.inputPrice()).isEqualTo("1");
                    assertThat(row.outputPrice()).isEqualTo("10");
                    assertThat(row.cacheReadPrice()).isNull();
                });
    }

    @Test
    void rowsReadBackCanBeSentBackUnchanged() {
        replace(List.of(fixed("gpt-4o", "openai", "2.00", "8.00"),
                alias("aide-gpt-4o", "", "gpt-4o", "openai")));
        var list = find();

        List<JsonNode> readBack = new ArrayList<>(list.rows().stream().map(JsonUtils::valueToTree).toList());
        try (var response = put(body(readBack, list.version()), false)) {
            var result = response.readEntity(CustomModelPriceReplaceResult.class);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(result.saved()).isFalse();
            assertThat(result.version()).isEqualTo(list.version());
            assertThat(result.unchanged()).isEqualTo(2);
        }
    }

    @Test
    void dryRunReturnsTheDiffAndSavesNothing() {
        replace(List.of(fixed("gpt-4o", "openai", "2.00", "8.00")));
        var before = find();

        try (var response = put(body(List.of(fixed("gpt-4o", "openai", "3.00", "8.00"),
                fixed("gpt-4.1", "openai", "1.00", "4.00")), before.version()), true)) {
            var result = response.readEntity(CustomModelPriceReplaceResult.class);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(result.saved()).isFalse();
            assertThat(result.created()).extracting(CustomModelPriceRow::model).containsExactly("gpt-4.1");
            assertThat(result.updated()).singleElement().satisfies(change -> {
                assertThat(change.before().inputPrice()).isEqualTo("2");
                assertThat(change.after().inputPrice()).isEqualTo("3");
            });
        }
        assertThat(find()).isEqualTo(before);
    }

    @Test
    void staleExpectedVersionIsAConflictAndSavesNothing() {
        replace(List.of(fixed("gpt-4o", "openai", "2.00", "8.00")));
        var before = find();

        try (var response = put(body(List.of(fixed("gpt-4o", "openai", "5.00", "8.00")), before.version() - 1),
                false)) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(ErrorMessage.class).getMessage()).contains("changed since version");
        }
        assertThat(find()).isEqualTo(before);
    }

    @Test
    void invalidRowsListEveryProblemAndSaveNothing() {
        replace(List.of());
        var before = find();
        var misspelled = JsonUtils.createObjectNode().put("model", "gpt-4o").put("provider", "openai")
                .put("kind", "fixed").put("input_prise", "1.00");
        var freeCacheRead = fixed("gpt-4.1", "openai", "1.00", "4.00").put("cache_read_price", "0");
        var otherOrganization = fixed("gpt-5", "openai", "1.00", "4.00").put("organization_id", "another");

        try (var response = put(body(List.of(misspelled, freeCacheRead, otherOrganization), before.version()),
                false)) {
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(response.readEntity(CustomModelPriceProblems.class).errors())
                    .extracting(CustomModelPriceValidator.Problem::row, CustomModelPriceValidator.Problem::field,
                            CustomModelPriceValidator.Problem::code)
                    .contains(
                            tuple(0, "input_prise", "unknown_field"),
                            tuple(1, "cache_read_price", "cache_price_not_positive"),
                            tuple(2, "organization_id", "unknown_field"));
        }
        assertThat(find()).isEqualTo(before);
    }

    @Test
    void historyListsEachChangeNewestFirst() {
        replace(List.of());
        replace(List.of(fixed("gpt-4o", "openai", "2.00", "8.00")));
        long version = replace(List.of(fixed("gpt-4o", "openai", "3.00", "8.00"))).version();

        try (var response = client.target(URL_TEMPLATE.formatted(baseURI) + "/history")
                .queryParam("size", 2)
                .request()
                .get()) {
            var history = response.readEntity(CustomModelPriceHistoryPage.class);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(history.content()).hasSize(2);
            assertThat(history.total()).isGreaterThanOrEqualTo(2);
            assertThat(history.content().getFirst()).satisfies(entry -> {
                assertThat(entry.listVersion()).isEqualTo(version);
                assertThat(entry.action()).isEqualTo("updated");
                assertThat(new BigDecimal(entry.before().get("input_price").asText())).isEqualByComparingTo("2");
                assertThat(new BigDecimal(entry.after().get("input_price").asText())).isEqualByComparingTo("3");
            });
            assertThat(history.content().get(1)).satisfies(entry -> {
                assertThat(entry.listVersion()).isEqualTo(version - 1);
                assertThat(entry.action()).isEqualTo("created");
                assertThat(entry.before()).isNull();
            });
        }
    }

    @Test
    void historyPageFarPastTheEndIsEmpty() {
        replace(List.of(fixed("gpt-4o", "openai", "2.00", "8.00")));

        try (var response = client.target(URL_TEMPLATE.formatted(baseURI) + "/history")
                .queryParam("page", Integer.MAX_VALUE)
                .queryParam("size", 1000)
                .request()
                .get()) {
            var history = response.readEntity(CustomModelPriceHistoryPage.class);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(history.page()).isEqualTo(Integer.MAX_VALUE);
            assertThat(history.content()).isEmpty();
            assertThat(history.total()).isPositive();
        }
    }

    @Test
    void resolveExplainsTheOrganizationsPrice() {
        long version = replace(List.of(alias("aide-gpt-5-mini", "azure", "gpt-5-mini", "azure"),
                fixed("gpt-5-mini", "azure", "1.00", "10.00"))).version();

        // 500k*1e-6 + 500k*2.5e-8 (built-in cache read) + 100k*1e-5
        var resolution = resolve("aide-gpt-5-mini", "azure", Map.of("prompt_tokens", 1_000_000,
                "prompt_tokens_details.cached_tokens", 500_000, "completion_tokens", 100_000));

        assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("1.5125");
        assertThat(resolution.costSource()).isEqualTo("organization");
        assertThat(resolution.listVersion()).isEqualTo(version);
        assertThat(resolution.match().row().kind()).isEqualTo("alias");
        assertThat(resolution.match().targetRow().model()).isEqualTo("gpt-5-mini");
        assertThat(resolution.lines()).extracting(CustomModelPriceResolution.Line::name,
                CustomModelPriceResolution.Line::source)
                .contains(tuple("input", "organization"),
                        tuple("cache_read", "built_in"));
    }

    @Test
    void resolveWithoutAMatchingRowUsesTheBuiltInPrice() {
        replace(List.of());

        // 1000*2.5e-7 + 500*2e-6
        var resolution = resolve("gpt-5-mini", "openai", Map.of("prompt_tokens", 1000, "completion_tokens", 500));

        assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("0.00125");
        assertThat(resolution.costSource()).isEqualTo("built_in");
        assertThat(resolution.match().row()).isNull();
    }
}
