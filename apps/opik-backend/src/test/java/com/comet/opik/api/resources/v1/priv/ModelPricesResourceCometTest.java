package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.modelprice.CustomModelPriceHistoryPage;
import com.comet.opik.api.modelprice.CustomModelPriceList;
import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ModelPricesResourceCometTest {

    private static final String URL_TEMPLATE = "%s/v1/private/model-prices";
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
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

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private Invocation.Builder request(String path) {
        return client.target(URL_TEMPLATE.formatted(baseURI) + path)
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, WORKSPACE_NAME);
    }

    private CustomModelPriceList find() {
        try (var response = request("").get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(CustomModelPriceList.class);
        }
    }

    @Test
    void findWithAnUnknownOrganizationReturnsAnEmptyList() {
        assertThat(find()).isEqualTo(CustomModelPriceList.EMPTY);
    }

    @Test
    void replaceWithAnUnknownOrganizationIsRefusedAndSavesNothing() {
        var row = JsonUtils.createObjectNode().put("model", "gpt-4o").put("provider", "openai").put("kind", "fixed")
                .put("input_price", "2.00");

        try (var response = request("").put(Entity.json(Map.of("rows", List.of(row))))) {
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("The organization of this workspace is unknown, so its organization settings can't"
                            + " be changed");
        }
        assertThat(find()).isEqualTo(CustomModelPriceList.EMPTY);
    }

    @Test
    void historyWithAnUnknownOrganizationIsEmpty() {
        try (var response = request("/history").get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(CustomModelPriceHistoryPage.class).total()).isZero();
        }
    }

    @Test
    void resolveWithAnUnknownOrganizationUsesTheBuiltInPrice() {
        try (var response = request("/resolve").post(Entity.json(Map.of("model", "gpt-5-mini", "provider",
                "openai", "usage", Map.of("prompt_tokens", 1000, "completion_tokens", 500))))) {
            var resolution = response.readEntity(CustomModelPriceResolution.class);

            assertThat(response.getStatus()).isEqualTo(200);
            // 1000*2.5e-7 + 500*2e-6
            assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("0.00125");
            assertThat(resolution.costSource()).isEqualTo("built_in");
            assertThat(resolution.listVersion()).isZero();
        }
    }
}
