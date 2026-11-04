package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.modelprice.CustomModelPriceList;
import com.comet.opik.api.modelprice.CustomModelPriceReplaceResult;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanOrganizationPricesTest {

    private static final String PRICES_URL_TEMPLATE = "%s/v1/private/model-prices";
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = ProjectService.DEFAULT_WORKSPACE_NAME;
    private static final Map<String, Integer> ONE_MILLION_IN_100K_OUT = Map.of(
            "prompt_tokens", 1_000_000, "completion_tokens", 100_000);
    private static final Map<String, Integer> ONE_MILLION_EACH = Map.of(
            "prompt_tokens", 1_000_000, "completion_tokens", 1_000_000);

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

    private SpanResourceClient spanResourceClient;
    private TransactionTemplateAsync templateAsync;
    private String organizationVersion;

    @BeforeAll
    void beforeAll(ClientSupport client, TransactionTemplateAsync templateAsync) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.templateAsync = templateAsync;

        var pricesUrl = PRICES_URL_TEMPLATE.formatted(baseURI);
        long expectedVersion;
        try (var response = client.target(pricesUrl).request().get()) {
            expectedVersion = response.readEntity(CustomModelPriceList.class).version();
        }
        var rows = List.of(
                fixed("gpt-5-mini", "openai", "1.00", "10.00"),
                fixed("custom-llm/local/llama3.2", "ollama:local", "0.50", "1.50"),
                fixed("meta-llama/Llama-3-8B", "openai", "0.10", "0.20"),
                fixed("aide-free", "openai", "0", "0"));
        try (var response = client.target(pricesUrl).request()
                .put(Entity.json(Map.of("rows", rows, "expected_version", expectedVersion)))) {
            assertThat(response.getStatus()).isEqualTo(200);
            var result = response.readEntity(CustomModelPriceReplaceResult.class);
            this.organizationVersion = "1.1;o" + result.version();
        }
    }

    private static ObjectNode fixed(String model, String provider, String inputPrice, String outputPrice) {
        return JsonUtils.createObjectNode()
                .put("model", model)
                .put("provider", provider)
                .put("kind", "fixed")
                .put("input_price", inputPrice)
                .put("output_price", outputPrice);
    }

    private Span span(String model, String provider, Map<String, Integer> usage) {
        return podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(16))
                .projectId(null)
                .model(model)
                .provider(provider)
                .usage(usage)
                .metadata(null)
                .totalEstimatedCost(null)
                .feedbackScores(null)
                .build();
    }

    private record StoredCost(BigDecimal cost, String version) {
    }

    private StoredCost storedCost(UUID spanId) {
        return templateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT total_estimated_cost, total_estimated_cost_version
                    FROM spans
                    WHERE id = :id
                    ORDER BY last_updated_at DESC
                    LIMIT 1
                    """);
            statement.bind("id", spanId);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> new StoredCost(
                            row.get("total_estimated_cost", BigDecimal.class),
                            row.get("total_estimated_cost_version", String.class)))));
        }).block();
    }

    private static void assertCost(StoredCost stored, String expectedCost, String expectedVersion) {
        assertThat(stored.cost()).isEqualByComparingTo(expectedCost);
        assertThat(stored.version()).isEqualTo(expectedVersion);
    }

    @Test
    void batchSpanIsPricedWithTheOrganizationsRow() {
        var span = span("gpt-5-mini", "openai", ONE_MILLION_IN_100K_OUT);

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(span.id()), "2.00", organizationVersion);
    }

    @Test
    void singleSpanIsPricedWithTheOrganizationsRow() {
        var span = span("gpt-5-mini", "openai", ONE_MILLION_IN_100K_OUT);

        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(span.id()), "2.00", organizationVersion);
    }

    @Test
    void costTheClientSentWinsOverTheOrganizationsRow() {
        var span = span("gpt-5-mini", "openai", ONE_MILLION_IN_100K_OUT).toBuilder()
                .totalEstimatedCost(new BigDecimal("0.5"))
                .build();

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(span.id()), "0.5", "");
    }

    @Test
    void modelWithoutARowIsPricedWithBuiltInPricesAndStampedWithThePriceFiles() {
        var span = span("gpt-4o", "openai", ONE_MILLION_IN_100K_OUT);

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        var stored = storedCost(span.id());
        // 1M*2.5e-6 + 100k*1e-5
        assertThat(stored.cost()).isEqualByComparingTo("3.5");
        assertThat(stored.version()).matches("1\\.1;b[0-9a-f]{8}");
    }

    @Test
    void createAfterAFreeUpdateStampsTheCostItStores() {
        var span = span("gpt-4o", "openai", ONE_MILLION_IN_100K_OUT);
        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .parentSpanId(span.parentSpanId())
                .model("aide-free")
                .provider("openai")
                .usage(ONE_MILLION_IN_100K_OUT)
                .build(), API_KEY, WORKSPACE_NAME);
        assertCost(storedCost(span.id()), "0", organizationVersion);

        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        var stored = storedCost(span.id());
        assertThat(stored.cost()).isEqualByComparingTo("3.5");
        assertThat(stored.version()).matches("1\\.1;b[0-9a-f]{8}");
    }

    @Test
    void updateThatAddsUsageIsPricedOnceWithTheOrganizationsRow() {
        var span = span("gpt-5-mini", "openai", null);
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .parentSpanId(span.parentSpanId())
                .usage(ONE_MILLION_IN_100K_OUT)
                .build(), API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(span.id()), "2.00", organizationVersion);
    }

    @Test
    void updateOfASpanNotCreatedYetIsPricedWithTheOrganizationsRow() {
        var spanId = podamFactory.manufacturePojo(Span.class).id();

        spanResourceClient.updateSpan(spanId, SpanUpdate.builder()
                .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(16))
                .traceId(podamFactory.manufacturePojo(Span.class).traceId())
                .model("gpt-5-mini")
                .provider("openai")
                .usage(ONE_MILLION_IN_100K_OUT)
                .build(), API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(spanId), "2.00", organizationVersion);
    }

    @Test
    void playgroundSpanOfACustomProviderIsPricedByARowWithTheSpansNames() {
        var span = span("custom-llm/local/llama3.2", "ollama:local", ONE_MILLION_EACH);

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(span.id()), "2.00", organizationVersion);
    }

    @Test
    void sdkSpanOfASelfHostedModelIsPricedByARowWithTheSpansNames() {
        var span = span("meta-llama/Llama-3-8B", "openai", ONE_MILLION_EACH);

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        assertCost(storedCost(span.id()), "0.30", organizationVersion);
    }
}
