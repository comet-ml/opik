package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ConditionalGZipFilter;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.domain.DatasetItemDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Datasets CSV Upload Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsCsvUploadResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(new CustomConfig("serviceToggles.csvUploadEnabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private Client registeredClient;
    private DatasetResourceClient datasetResourceClient;
    private DatasetItemDAO datasetItemDAO;

    @BeforeAll
    void setUpAll(ClientSupport client, DatasetItemDAO datasetItemDAO) {
        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.datasetItemDAO = datasetItemDAO;

        // Configure client but DON'T use GrizzlyConnectorProvider for multipart support
        // GrizzlyConnector doesn't properly handle multipart Content-Type headers
        client.getClient().register(new ConditionalGZipFilter());
        client.getClient().property(ClientProperties.READ_TIMEOUT, 35_000);
        // Note: NOT setting connectorProvider - use default HttpUrlConnector for multipart

        // Register MultiPartFeature on the client and capture the registered client
        this.registeredClient = client.getClient().register(MultiPartFeature.class);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetRequests();
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Upload CSV file successfully - should return 202 Accepted and process items asynchronously")
    void uploadCsvFile__success() {
        // Given: Create a dataset
        Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                .id(null)
                .createdBy(null)
                .lastUpdatedBy(null)
                .build();

        UUID createdDatasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

        // Prepare CSV content
        String csvContent = """
                input,output,expected_output
                "What is 2+2?","4","4"
                "What is the capital of France?","Paris","Paris"
                "What is the largest planet?","Jupiter","Jupiter"
                """;

        // When: Upload CSV file
        try (var response = uploadCsvFile(createdDatasetId, csvContent)) {
            // Then: Should return 202 Accepted
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        // Wait for async processing to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var items = getDatasetItems(createdDatasetId);
                    assertThat(items).hasSize(3);

                    // Verify first item
                    DatasetItem item1 = items.stream()
                            .filter(item -> item.data().get("input").asText().equals("What is 2+2?"))
                            .findFirst()
                            .orElseThrow();
                    assertThat(item1.data().get("output").asText()).isEqualTo("4");
                    assertThat(item1.data().get("expected_output").asText()).isEqualTo("4");
                    assertThat(item1.source()).isEqualTo(DatasetItemSource.MANUAL);

                    // Verify second item
                    DatasetItem item2 = items.stream()
                            .filter(item -> item.data().get("input").asText().equals("What is the capital of France?"))
                            .findFirst()
                            .orElseThrow();
                    assertThat(item2.data().get("output").asText()).isEqualTo("Paris");
                    assertThat(item2.source()).isEqualTo(DatasetItemSource.MANUAL);

                    // Verify third item
                    DatasetItem item3 = items.stream()
                            .filter(item -> item.data().get("input").asText().equals("What is the largest planet?"))
                            .findFirst()
                            .orElseThrow();
                    assertThat(item3.data().get("output").asText()).isEqualTo("Jupiter");
                    assertThat(item3.source()).isEqualTo(DatasetItemSource.MANUAL);
                });
    }

    @Test
    @DisplayName("Upload CSV file with large batch - should process in batches")
    void uploadCsvFile__largeBatch() {
        // Given: Create a dataset
        Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                .id(null)
                .createdBy(null)
                .lastUpdatedBy(null)
                .build();

        UUID createdDatasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

        // Prepare CSV with 2500 rows (should be processed in multiple batches)
        StringBuilder csvContent = new StringBuilder("input,output\n");
        for (int i = 0; i < 2500; i++) {
            csvContent.append("\"Question ").append(i).append("\",\"Answer ").append(i).append("\"\n");
        }

        // When: Upload CSV file
        try (var response = uploadCsvFile(createdDatasetId, csvContent.toString())) {
            // Then: Should return 202 Accepted
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        // Wait for async processing to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var items = getDatasetItems(createdDatasetId);
                    assertThat(items).hasSize(2500);
                });
    }

    @Test
    @DisplayName("Upload CSV file with special characters - should handle correctly")
    void uploadCsvFile__specialCharacters() {
        // Given: Create a dataset
        Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                .id(null)
                .createdBy(null)
                .lastUpdatedBy(null)
                .build();

        UUID createdDatasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

        // Prepare CSV with special characters
        String csvContent = "input,output\n" +
                "\"What's the weather?\",\"It's sunny!\"\n" +
                "\"Quote: \"\"Hello\"\"\",\"Response: \"\"Hi\"\"\"\n" +
                "\"Comma, test\",\"Value, with, commas\"\n";

        // When: Upload CSV file
        try (var response = uploadCsvFile(createdDatasetId, csvContent)) {
            // Then: Should return 202 Accepted
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        // Wait for async processing to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var items = getDatasetItems(createdDatasetId);
                    assertThat(items).hasSize(3);

                    // Verify special characters are preserved
                    DatasetItem item1 = items.stream()
                            .filter(item -> item.data().get("input").asText().equals("What's the weather?"))
                            .findFirst()
                            .orElseThrow();
                    assertThat(item1.data().get("output").asText()).isEqualTo("It's sunny!");

                    DatasetItem item2 = items.stream()
                            .filter(item -> item.data().get("input").asText().equals("Quote: \"Hello\""))
                            .findFirst()
                            .orElseThrow();
                    assertThat(item2.data().get("output").asText()).isEqualTo("Response: \"Hi\"");
                });
    }

    private Response uploadCsvFile(UUID datasetId, String csvContent) {
        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        InputStream csvInputStream = new ByteArrayInputStream(csvBytes);

        FormDataMultiPart multiPart = new FormDataMultiPart();
        multiPart.field("dataset_id", datasetId.toString());
        multiPart.bodyPart(new FormDataBodyPart("file", csvInputStream, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        // Use the registered client that has MultiPartFeature enabled
        return registeredClient.target("%s/v1/private/datasets/items/from-csv".formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.entity(multiPart, multiPart.getMediaType()));
    }

    private List<DatasetItem> getDatasetItems(UUID datasetId) {
        return datasetItemDAO.getItems(datasetId, 10000, null)
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .block();
    }
}
