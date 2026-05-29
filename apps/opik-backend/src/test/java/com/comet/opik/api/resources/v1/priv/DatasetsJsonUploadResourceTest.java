package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetStatus;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
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
@DisplayName("Datasets JSON Upload Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsJsonUploadResourceTest {

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
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator uuidV7 = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport registeredClient;
    private DatasetResourceClient datasetResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = "http://localhost:%d".formatted(client.getPort());

        this.registeredClient = client;
        ClientSupportUtils.configMultiPartFeature(client);

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
    @DisplayName("Missing format form field -> 422 Unprocessable Entity (bean validation)")
    void uploadWithoutFormat__rejected() {
        UUID datasetId = createDataset();

        String jsonContent = "[{\"input\": \"q1\"}]";

        try (var response = uploadJsonFile(datasetId, jsonContent, null)) {
            // Dropwizard maps @NotNull form-param violations to 422 Unprocessable Entity.
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        assertThat(getDatasetItems(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Upload JSON array - preserves nested objects, numbers, booleans, nulls")
    void uploadJsonArray__preservesJsonTypes() {
        UUID datasetId = createDataset();

        String jsonContent = """
                [
                  {
                    "input": "What is 2+2?",
                    "expected_output": "4",
                    "metadata": {"difficulty": "easy", "weight": 0.5},
                    "tags_count": 3,
                    "is_active": true,
                    "notes": null
                  },
                  {
                    "input": "What is the capital of France?",
                    "expected_output": "Paris",
                    "metadata": {"difficulty": "medium", "weight": 1.0},
                    "tags_count": 5,
                    "is_active": false,
                    "notes": "geography"
                  }
                ]
                """;

        try (var response = uploadJsonFile(datasetId, jsonContent, "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        assertProcessingThenCompletedWithItems(datasetId, 2, items -> {
            DatasetItem easy = items.stream()
                    .filter(i -> i.data().get("input").asText().equals("What is 2+2?"))
                    .findFirst()
                    .orElseThrow();
            assertThat(easy.data().get("expected_output").asText()).isEqualTo("4");
            assertThat(easy.data().get("metadata").isObject()).isTrue();
            assertThat(easy.data().get("metadata").get("difficulty").asText()).isEqualTo("easy");
            assertThat(easy.data().get("metadata").get("weight").asDouble()).isEqualTo(0.5);
            assertThat(easy.data().get("tags_count").isNumber()).isTrue();
            assertThat(easy.data().get("tags_count").asInt()).isEqualTo(3);
            assertThat(easy.data().get("is_active").isBoolean()).isTrue();
            assertThat(easy.data().get("is_active").asBoolean()).isTrue();
            assertThat(easy.data().get("notes").isNull()).isTrue();
            assertThat(easy.source()).isEqualTo(DatasetItemSource.MANUAL);
        });
    }

    @Test
    @DisplayName("Upload JSONL - blank lines tolerated, line-based parsing")
    void uploadJsonl__blankLinesIgnored() {
        UUID datasetId = createDataset();

        String jsonlContent = """
                {"input": "q1", "expected_output": "a1"}

                {"input": "q2", "expected_output": "a2"}
                {"input": "q3", "expected_output": "a3"}

                """;

        try (var response = uploadJsonFile(datasetId, jsonlContent, "JSONL")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        assertProcessingThenCompletedWithItems(datasetId, 3, items -> {
            assertThat(items)
                    .extracting(i -> i.data().get("input").asText())
                    .containsExactlyInAnyOrder("q1", "q2", "q3");
            assertThat(items).allSatisfy(i -> assertThat(i.source()).isEqualTo(DatasetItemSource.MANUAL));
        });
    }

    @Test
    @DisplayName("Reserved fields - id, source, description, tags flow to top-level (trace/span FKs out of scope)")
    void uploadJsonArray__reservedFieldsRouted() {
        UUID datasetId = createDataset();

        UUID providedId = uuidV7.generate();

        String jsonContent = """
                [
                  {
                    "id": "%s",
                    "source": "sdk",
                    "description": "hand-curated",
                    "tags": ["smoke", "regression"],
                    "input": "hello",
                    "expected_output": "world"
                  }
                ]
                """.formatted(providedId);

        try (var response = uploadJsonFile(datasetId, jsonContent, "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        assertProcessingThenCompletedWithItems(datasetId, 1, items -> {
            DatasetItem item = items.getFirst();
            assertThat(item.id()).isEqualTo(providedId);
            assertThat(item.source()).isEqualTo(DatasetItemSource.SDK);
            assertThat(item.description()).isEqualTo("hand-curated");
            assertThat(item.tags()).containsExactlyInAnyOrder("smoke", "regression");
            // Reserved keys are removed from data
            assertThat(item.data()).doesNotContainKeys(
                    "id", "source", "description", "tags");
            assertThat(item.data().get("input").asText()).isEqualTo("hello");
            assertThat(item.data().get("expected_output").asText()).isEqualTo("world");
        });
    }

    @Test
    @DisplayName("UTF-8 BOM is stripped before parsing")
    void uploadJsonArray__withBom() {
        UUID datasetId = createDataset();

        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String body = """
                [{"input": "如何强制触发4G/5G后台搜索？", "expected_output": "测试答案"}]
                """;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + bodyBytes.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(bodyBytes, 0, withBom, bom.length, bodyBytes.length);

        try (var response = uploadJsonFile(datasetId, withBom, "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        assertProcessingThenCompletedWithItems(datasetId, 1, items -> {
            DatasetItem item = items.getFirst();
            assertThat(item.data().get("input").asText()).isEqualTo("如何强制触发4G/5G后台搜索？");
            assertThat(item.data().get("expected_output").asText()).isEqualTo("测试答案");
        });
    }

    @Test
    @DisplayName("Empty file -> 400 Bad Request")
    void uploadEmptyFile__rejected() {
        UUID datasetId = createDataset();

        try (var response = uploadJsonFile(datasetId, "", "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(String.class)).contains("empty");
        }

        assertThat(getDatasetItems(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Empty JSON array -> 400 Bad Request")
    void uploadEmptyJsonArray__rejected() {
        UUID datasetId = createDataset();

        try (var response = uploadJsonFile(datasetId, "[]", "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }

        assertThat(getDatasetItems(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Garbage content with format=JSON -> 400 Bad Request from head validation")
    void uploadGarbageAsJson__rejected() {
        UUID datasetId = createDataset();

        try (var response = uploadJsonFile(datasetId, "not even close to json", "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }

        assertThat(getDatasetItems(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Garbage content with format=JSONL -> 400 Bad Request from head validation")
    void uploadGarbageAsJsonl__rejected() {
        UUID datasetId = createDataset();

        try (var response = uploadJsonFile(datasetId, "not even close to json", "JSONL")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }

        assertThat(getDatasetItems(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("JSON array with non-object element -> dataset transitions to FAILED")
    void uploadJsonArray__nonObjectElement__failsAsync() {
        UUID datasetId = createDataset();

        String jsonContent = """
                [
                  {"input": "q1", "expected_output": "a1"},
                  "this is a string, not an object",
                  {"input": "q3", "expected_output": "a3"}
                ]
                """;

        try (var response = uploadJsonFile(datasetId, jsonContent, "JSON")) {
            // First element is a valid object, so the head validation passes and the
            // async stream is what fails.
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        assertStatusEventually(datasetId, DatasetStatus.FAILED);
    }

    @Test
    @DisplayName("Invalid UUID in id -> dataset transitions to FAILED")
    void uploadJsonArray__invalidId__failsAsync() {
        UUID datasetId = createDataset();

        String jsonContent = """
                [{"id": "not-a-uuid", "input": "q1", "expected_output": "a1"}]
                """;

        try (var response = uploadJsonFile(datasetId, jsonContent, "JSON")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        assertStatusEventually(datasetId, DatasetStatus.FAILED);
    }

    @Test
    @DisplayName("Explicit format=JSONL forces line-based parsing even for .json content")
    void uploadJsonArray__withJsonlFormat__rejected() {
        UUID datasetId = createDataset();

        // A JSON array file submitted as JSONL: the first non-blank line is the '[' bracket,
        // which is not a JSON object.
        String jsonArray = """
                [
                  {"input": "q1"}
                ]
                """;

        try (var response = uploadJsonFile(datasetId, jsonArray, "JSONL")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }

        assertThat(getDatasetItems(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Large upload exercises multiple batches")
    void uploadJsonl__largeBatch() {
        UUID datasetId = createDataset();

        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            jsonl.append("{\"input\": \"Q").append(i).append("\", \"expected_output\": \"A").append(i).append("\"}\n");
        }

        try (var response = uploadJsonFile(datasetId, jsonl.toString(), "JSONL")) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }

        Dataset afterUpload = datasetResourceClient.getDatasetById(datasetId, API_KEY, TEST_WORKSPACE);
        assertThat(afterUpload.status()).isEqualTo(DatasetStatus.PROCESSING);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var items = getDatasetItems(datasetId);
                    assertThat(items).hasSize(2500);
                    Dataset after = datasetResourceClient.getDatasetById(datasetId, API_KEY, TEST_WORKSPACE);
                    assertThat(after.status()).isEqualTo(DatasetStatus.COMPLETED);
                });
    }

    private UUID createDataset() {
        Dataset dataset = DatasetResourceClient.buildDataset(factory).toBuilder()
                .id(null)
                .createdBy(null)
                .lastUpdatedBy(null)
                .build();
        return datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
    }

    private void assertProcessingThenCompletedWithItems(UUID datasetId, int expectedCount,
            java.util.function.Consumer<List<DatasetItem>> itemsCheck) {
        Dataset afterUpload = datasetResourceClient.getDatasetById(datasetId, API_KEY, TEST_WORKSPACE);
        assertThat(afterUpload.status()).isEqualTo(DatasetStatus.PROCESSING);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var items = getDatasetItems(datasetId);
                    assertThat(items).hasSize(expectedCount);
                    Dataset after = datasetResourceClient.getDatasetById(datasetId, API_KEY, TEST_WORKSPACE);
                    assertThat(after.status()).isEqualTo(DatasetStatus.COMPLETED);
                    itemsCheck.accept(items);
                });
    }

    private void assertStatusEventually(UUID datasetId, DatasetStatus expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Dataset after = datasetResourceClient.getDatasetById(datasetId, API_KEY, TEST_WORKSPACE);
                    assertThat(after.status()).isEqualTo(expected);
                });
    }

    private Response uploadJsonFile(UUID datasetId, String content, String format) {
        return uploadJsonFile(datasetId, content.getBytes(StandardCharsets.UTF_8), format);
    }

    private Response uploadJsonFile(UUID datasetId, byte[] bytes, String format) {
        InputStream input = new ByteArrayInputStream(bytes);

        FormDataMultiPart multiPart = new FormDataMultiPart();
        multiPart.field("dataset_id", datasetId.toString());
        if (format != null) {
            multiPart.field("format", format);
        }
        multiPart.bodyPart(new FormDataBodyPart("file", input, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        return registeredClient.target("%s/v1/private/datasets/items/from-json".formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.entity(multiPart, multiPart.getMediaType()));
    }

    private List<DatasetItem> getDatasetItems(UUID datasetId) {
        return datasetResourceClient.getDatasetItems(datasetId, 1, 10000, null, API_KEY, TEST_WORKSPACE)
                .content();
    }
}
