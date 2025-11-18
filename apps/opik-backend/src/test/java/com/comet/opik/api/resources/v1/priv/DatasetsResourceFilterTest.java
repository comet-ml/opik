package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.filter.DatasetItemField;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Integration tests for dataset item filtering functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Item Filtering:")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsResourceFilterTest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/datasets";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private DatasetResourceClient datasetResourceClient;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId,
                UUID.randomUUID().toString());
    }

    private UUID createAndAssert(Dataset dataset, String apiKey, String workspaceName) {
        return datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
    }

    private void putAndAssert(DatasetItemBatch batch, String workspaceName, String apiKey) {
        datasetResourceClient.createDatasetItems(batch, workspaceName, apiKey);
    }

    private DatasetItem createDatasetItemWithData(Map<String, String> dataMap) {
        Map<String, JsonNode> jsonDataMap = new HashMap<>();
        for (Map.Entry<String, String> entry : dataMap.entrySet()) {
            jsonDataMap.put(entry.getKey(), TextNode.valueOf(entry.getValue()));
        }

        return factory.manufacturePojo(DatasetItem.class).toBuilder()
                .id(null)
                .data(jsonDataMap)
                .build();
    }

    Stream<Arguments> filterTestCases() {
        return Stream.of(
                arguments(
                        Named.of("numeric field with >=", "size >= 20000000"),
                        DatasetItemFilter.builder()
                                .field(DatasetItemField.CUSTOM)
                                .operator(Operator.GREATER_THAN_EQUAL)
                                .key("data.size|number")
                                .value("20000000")
                                .build(),
                        Named.of("expected: 2 items with size >= 20M", 2)),
                arguments(
                        Named.of("string field with contains", "tags contains 'sky'"),
                        DatasetItemFilter.builder()
                                .field(DatasetItemField.CUSTOM)
                                .operator(Operator.CONTAINS)
                                .key("data.tags|string")
                                .value("sky")
                                .build(),
                        Named.of("expected: 2 items containing 'sky'", 2)),
                arguments(
                        Named.of("string field with equals", "type = 'video'"),
                        DatasetItemFilter.builder()
                                .field(DatasetItemField.CUSTOM)
                                .operator(Operator.EQUAL)
                                .key("data.type|string")
                                .value("video")
                                .build(),
                        Named.of("expected: 2 items with type=video", 2)),
                arguments(
                        Named.of("numeric field with <", "duration < 60"),
                        DatasetItemFilter.builder()
                                .field(DatasetItemField.CUSTOM)
                                .operator(Operator.LESS_THAN)
                                .key("data.duration|number")
                                .value("60")
                                .build(),
                        Named.of("expected: 2 items with duration < 60", 2)));
    }

    @ParameterizedTest
    @MethodSource("filterTestCases")
    @DisplayName("filter dataset items by dynamic fields")
    void filterDatasetItems__whenFilteringByDynamicFields__thenReturnMatchingItems(
            String description,
            DatasetItemFilter filter,
            int expectedCount) {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Given: Create a dataset with items
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        var items = List.of(
                createDatasetItemWithData(Map.of("size", "1000000", "type", "video")),
                createDatasetItemWithData(Map.of("size", "20781167", "type", "video")),
                createDatasetItemWithData(Map.of("size", "500000", "type", "image")),
                createDatasetItemWithData(Map.of("size", "30000000", "type", "video")),
                createDatasetItemWithData(Map.of("tags", "clouds, sunlight, view")),
                createDatasetItemWithData(Map.of("tags", "nature, calm, sky")),
                createDatasetItemWithData(Map.of("tags", "urban, city, buildings")),
                createDatasetItemWithData(Map.of("tags", "landscape, sky, mountains")),
                createDatasetItemWithData(Map.of("duration", "120")),
                createDatasetItemWithData(Map.of("duration", "45")),
                createDatasetItemWithData(Map.of("duration", "180")),
                createDatasetItemWithData(Map.of("duration", "30")));

        var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                .items(items)
                .datasetId(datasetId)
                .datasetName(null)
                .build();

        putAndAssert(batch, workspaceName, apiKey);

        // When: Apply filter
        var filters = List.of(filter);

        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .get()) {

            // Then: Should return matching items
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(DatasetItemPage.class);
            assertThat(actualEntity.content()).hasSize(expectedCount);
        }
    }
}
