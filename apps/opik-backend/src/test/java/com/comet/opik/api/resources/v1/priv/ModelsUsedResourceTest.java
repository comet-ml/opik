package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.modelprice.CustomModelPriceList;
import com.comet.opik.api.modelprice.CustomModelPricesUsed;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import org.apache.commons.lang3.RandomStringUtils;
import org.jdbi.v3.core.Jdbi;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ModelsUsedResourceTest {

    private static final String PRICES_URL_TEMPLATE = "%s/v1/private/model-prices";
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = ProjectService.DEFAULT_WORKSPACE_NAME;
    private static final CustomModelPricesUsed.Workspace DEFAULT_WORKSPACE = CustomModelPricesUsed.Workspace.builder()
            .id(ProjectService.DEFAULT_WORKSPACE_ID)
            .name(ProjectService.DEFAULT_WORKSPACE_NAME)
            .build();
    private static final Map<String, Integer> USAGE = Map.of("prompt_tokens", 1_000, "completion_tokens", 100);

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

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ClientSupport client;
    private String pricesUrl;
    private SpanResourceClient spanResourceClient;
    private Jdbi mysql;
    private String pricedModel;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.client = client;
        this.pricesUrl = PRICES_URL_TEMPLATE.formatted(baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.mysql = Jdbi.create(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());

        this.pricedModel = uniqueModel();
        long expectedVersion;
        try (var response = client.target(pricesUrl).request().get()) {
            expectedVersion = response.readEntity(CustomModelPriceList.class).version();
        }
        var row = JsonUtils.createObjectNode()
                .put("model", pricedModel)
                .put("provider", "openai")
                .put("kind", "fixed")
                .put("input_price", "1.00")
                .put("output_price", "10.00");
        try (var response = client.target(pricesUrl).request()
                .put(Entity.json(Map.of("rows", List.of(row), "expected_version", expectedVersion)))) {
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    private static String uniqueModel() {
        return "aide-" + RandomStringUtils.secure().nextAlphanumeric(12).toLowerCase();
    }

    private Span span(String model, String provider, String projectName) {
        return podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .projectId(null)
                .model(model)
                .provider(provider)
                .usage(USAGE)
                .metadata(null)
                .totalEstimatedCost(null)
                .feedbackScores(null)
                .build();
    }

    private Span span(String model, String provider) {
        return span(model, provider, "project-" + RandomStringUtils.secure().nextAlphanumeric(16));
    }

    private CustomModelPricesUsed modelsUsed() {
        try (var response = client.target(pricesUrl + "/models-used").request().get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(CustomModelPricesUsed.class);
        }
    }

    private Optional<CustomModelPricesUsed.Model> modelUsed(String model, String provider) {
        return modelsUsed().content().stream()
                .filter(used -> used.model().equals(model) && used.provider().equals(provider))
                .findFirst();
    }

    private CustomModelPricesUsed.Model awaitModelUsed(String model, String provider) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> modelUsed(model, provider).isPresent());
        return modelUsed(model, provider).orElseThrow();
    }

    private void assertNeverUsed(String model, String provider) {
        await().during(2, TimeUnit.SECONDS).atMost(4, TimeUnit.SECONDS)
                .until(() -> modelUsed(model, provider).isEmpty());
    }

    @Test
    void spanBatchRecordsEachModelForTheWorkspace() {
        String first = uniqueModel();
        String second = uniqueModel();

        spanResourceClient.batchCreateSpans(List.of(span(first, "openai"), span(first, "openai"),
                span(second, "anthropic")), API_KEY, WORKSPACE_NAME);

        var used = awaitModelUsed(first, "openai");
        assertThat(used.daysUsed()).isEqualTo(1);
        assertThat(used.lastSeen()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(used.workspaces()).containsExactly(DEFAULT_WORKSPACE);
        assertThat(awaitModelUsed(second, "anthropic").workspaces()).containsExactly(DEFAULT_WORKSPACE);
    }

    @Test
    void singleSpanIsRecorded() {
        String model = uniqueModel();

        spanResourceClient.createSpan(span(model, "openai"), API_KEY, WORKSPACE_NAME);

        assertThat(awaitModelUsed(model, "openai").workspaces()).containsExactly(DEFAULT_WORKSPACE);
    }

    @Test
    void updateThatSetsTheModelIsRecorded() {
        String model = uniqueModel();
        var span = span(null, null).toBuilder().usage(null).build();
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .parentSpanId(span.parentSpanId())
                .model(model)
                .provider("openai")
                .usage(USAGE)
                .build(), API_KEY, WORKSPACE_NAME);

        awaitModelUsed(model, "openai");
    }

    @Test
    void secondWriteOfTheSameModelTheSameDayDoesNotWriteAgain() {
        String model = uniqueModel();
        spanResourceClient.createSpan(span(model, "openai"), API_KEY, WORKSPACE_NAME);
        awaitModelUsed(model, "openai");
        mysql.useHandle(handle -> handle.execute("DELETE FROM model_prices_used WHERE model_name = ?", model));

        spanResourceClient.createSpan(span(model, "openai"), API_KEY, WORKSPACE_NAME);

        assertNeverUsed(model, "openai");
    }

    @Test
    void demoProjectSpansAreNotRecorded() {
        String demoModel = uniqueModel();
        String model = uniqueModel();

        spanResourceClient.batchCreateSpans(List.of(span(demoModel, "openai", DemoData.PROJECTS.getFirst()),
                span(model, "openai")), API_KEY, WORKSPACE_NAME);

        awaitModelUsed(model, "openai");
        assertNeverUsed(demoModel, "openai");
    }

    @Test
    void customProviderKeepsTheNamesAPriceRowNeeds() {
        String model = "custom-llm/local/" + uniqueModel();

        spanResourceClient.createSpan(span(model, "ollama:local"), API_KEY, WORKSPACE_NAME);

        assertThat(awaitModelUsed(model, "ollama:local").priceSource()).isEqualTo("none");
    }

    @Test
    void priceSourceSaysWhereEachModelsPriceComesFromToday() {
        String unpriced = uniqueModel();

        spanResourceClient.batchCreateSpans(List.of(span(pricedModel, "openai"), span("gpt-4o", "openai"),
                span(unpriced, "openai")), API_KEY, WORKSPACE_NAME);

        assertThat(awaitModelUsed(pricedModel, "openai").priceSource()).isEqualTo("organization");
        assertThat(awaitModelUsed("gpt-4o", "openai").priceSource()).isEqualTo("built_in");
        assertThat(awaitModelUsed(unpriced, "openai").priceSource()).isEqualTo("none");
    }

    @Test
    void writeThatNeedsNoPriceRecordsNothing() {
        String model = uniqueModel();
        var span = span(model, "openai").toBuilder().totalEstimatedCost(new BigDecimal("0.25")).build();

        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        assertNeverUsed(model, "openai");
    }
}
