package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.getIdFromLocation;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Version Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetVersionResourceTest {

    private static final String DATASET_RESOURCE_URI = "%s/v1/private/datasets";
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

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
                MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory,
                wireMock.runtimeInfo(),
                REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private DatasetResourceClient datasetResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        datasetResourceClient = new DatasetResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private UUID createDataset(String name) {
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                .id(null)
                .name(name)
                .build();

        try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.json(dataset))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
            return getIdFromLocation(response.getLocation());
        }
    }

    private void createDatasetItems(UUID datasetId, int count) {
        List<DatasetItem> itemsList = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                .limit(count)
                .map(item -> {
                    Map<String, JsonNode> data = Map.of(
                            "input", JsonUtils.getJsonNodeFromString("\"test input\""),
                            "output", JsonUtils.getJsonNodeFromString("\"test output\""));
                    return item.toBuilder()
                            .id(null)
                            .data(data)
                            .build();
                })
                .toList();

        var batch = DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(itemsList)
                .build();

        try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                .path("items")
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .put(Entity.json(batch))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    @Nested
    @DisplayName("Create Dataset Version:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateVersion {

        @Test
        @DisplayName("Success: Create version without tag")
        void createVersion__whenValidRequest__thenReturnCreated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 5);

            var versionCreate = DatasetVersionCreate.builder()
                    .changeDescription("Initial version")
                    .build();

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);

                var version = response.readEntity(DatasetVersion.class);
                assertThat(version.id()).isNotNull();
                assertThat(version.datasetId()).isEqualTo(datasetId);
                assertThat(version.versionHash()).isNotEmpty();
                assertThat(version.tags()).isNullOrEmpty();
                // TODO OPIK-3015: Assert on actual item counts once snapshot creation is implemented
                assertThat(version.itemsCount()).isEqualTo(0);
                assertThat(version.itemsAdded()).isEqualTo(0);
                assertThat(version.itemsModified()).isEqualTo(0);
                assertThat(version.itemsDeleted()).isEqualTo(0);
                assertThat(version.changeDescription()).isEqualTo("Initial version");
                assertThat(version.createdBy()).isEqualTo(USER);
                assertThat(version.createdAt()).isNotNull();
            }
        }

        @Test
        @DisplayName("Success: Create version with tag")
        void createVersion__whenValidRequestWithTag__thenReturnCreatedWithTag() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            var versionCreate = DatasetVersionCreate.builder()
                    .tag("baseline")
                    .changeDescription("Baseline version")
                    .build();

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);

                var version = response.readEntity(DatasetVersion.class);
                assertThat(version.tags()).contains("baseline");
                assertThat(version.changeDescription()).isEqualTo("Baseline version");
            }
        }

        @Test
        @DisplayName("Success: Create version with metadata")
        void createVersion__whenValidRequestWithMetadata__thenReturnCreatedWithMetadata() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            Map<String, String> metadata = Map.of(
                    "author", "test-user",
                    "purpose", "testing",
                    "version_number", "1");

            var versionCreate = DatasetVersionCreate.builder()
                    .changeDescription("Test version")
                    .metadata(metadata)
                    .build();

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);

                var version = response.readEntity(DatasetVersion.class);
                assertThat(version.changeDescription()).isEqualTo("Test version");
                assertThat(version.metadata()).isNotNull();
                assertThat(version.metadata().get("author")).isEqualTo("test-user");
                assertThat(version.metadata().get("purpose")).isEqualTo("testing");
                assertThat(version.metadata().get("version_number")).isEqualTo("1");
            }
        }

        @Test
        @DisplayName("Success: Create multiple versions for same dataset")
        void createVersion__whenMultipleVersions__thenAllCreated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            // When - Create first version
            var versionCreate1 = DatasetVersionCreate.builder()
                    .tag("v1")
                    .changeDescription("Version 1")
                    .build();

            UUID version1Id;
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate1))) {

                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                var version1 = response.readEntity(DatasetVersion.class);
                version1Id = version1.id();
                assertThat(version1.tags()).contains("v1");
            }

            // Add more items to change the dataset
            createDatasetItems(datasetId, 2);

            // When - Create second version with different content
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tag("v2")
                    .changeDescription("Version 2")
                    .build();

            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate2))) {

                // Then - Should create a new version (different hash due to changed items)
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                var version2 = response.readEntity(DatasetVersion.class);
                assertThat(version2.id()).isNotEqualTo(version1Id); // Different version created
                assertThat(version2.tags()).contains("v2");
            }
        }

        @Test
        @DisplayName("Error: Duplicate tag on same dataset")
        void createVersion__whenDuplicateTag__thenReturnConflict() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate1 = DatasetVersionCreate.builder()
                    .tag("v1.0")
                    .build();

            // Create first version with tag
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate1))) {

                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
            }

            // Modify dataset by adding more items
            createDatasetItems(datasetId, 3);

            // Try to create another version with same tag
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tag("v1.0") // Duplicate tag
                    .build();

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate2))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).contains("Tag 'v1.0' already exists for this dataset");
            }
        }
    }

    @Nested
    @DisplayName("List Dataset Versions:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ListVersions {

        @Test
        @DisplayName("Success: List versions with pagination")
        void listVersions__whenMultipleVersions__thenReturnPaginated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // Create 3 versions
            for (int i = 1; i <= 3; i++) {
                var versionCreate = DatasetVersionCreate.builder()
                        .tag("v" + i)
                        .changeDescription("Version " + i)
                        .build();

                try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                        .path(datasetId.toString())
                        .path("versions")
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .post(Entity.json(versionCreate))) {

                    assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                }

                // Add items to change hash for next version
                if (i < 3) {
                    createDatasetItems(datasetId, 1);
                }
            }

            // When - Get first page with size 2
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .queryParam("page", 1)
                    .queryParam("size", 2)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                var page = response.readEntity(DatasetVersionPage.class);
                assertThat(page.page()).isEqualTo(1);
                assertThat(page.size()).isEqualTo(2);
                assertThat(page.total()).isEqualTo(3);
                assertThat(page.content()).hasSize(2);

                // Verify versions are sorted by created_at DESC (newest first)
                assertThat(page.content().get(0).tags()).contains("v3");
                assertThat(page.content().get(1).tags()).contains("v2");
            }

            // When - Get second page
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .queryParam("page", 2)
                    .queryParam("size", 2)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                var page = response.readEntity(DatasetVersionPage.class);
                assertThat(page.content()).hasSize(1);
                assertThat(page.content().get(0).tags()).contains("v1");
            }
        }

        @Test
        @DisplayName("Success: List versions for empty dataset")
        void listVersions__whenNoVersions__thenReturnEmptyPage() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                var page = response.readEntity(DatasetVersionPage.class);
                assertThat(page.content()).isEmpty();
                assertThat(page.total()).isEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Tag Management:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TagManagement {

        @Test
        @DisplayName("Success: Create tag for existing version")
        void createTag__whenValidVersion__thenReturnNoContent() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate = DatasetVersionCreate.builder()
                    .changeDescription("Version without tag")
                    .build();

            String versionHash;
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate))) {

                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                var version = response.readEntity(DatasetVersion.class);
                versionHash = version.versionHash();
            }

            // When - Add tag to version
            var tag = DatasetVersionTag.builder()
                    .tag("production")
                    .build();

            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .path(versionHash)
                    .path("tags")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(tag))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            // Verify tag was added
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var page = response.readEntity(DatasetVersionPage.class);
                assertThat(page.content().get(0).tags()).contains("production");
            }
        }

        @Test
        @DisplayName("Error: Create duplicate tag")
        void createTag__whenDuplicateTag__thenReturnConflict() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate = DatasetVersionCreate.builder()
                    .tag("v1.0")
                    .build();

            String versionHash;
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate))) {

                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                var version = response.readEntity(DatasetVersion.class);
                versionHash = version.versionHash();
            }

            // When - Try to add same tag again
            var tag = DatasetVersionTag.builder()
                    .tag("v1.0")
                    .build();

            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .path(versionHash)
                    .path("tags")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(tag))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).contains("Tag 'v1.0' already exists for this dataset");
            }
        }

        @Test
        @DisplayName("Error: Create tag for non-existent version")
        void createTag__whenVersionNotFound__thenReturnNotFound() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            var nonExistentHash = "nonexistenthash";

            var tag = DatasetVersionTag.builder()
                    .tag("production")
                    .build();

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .path(nonExistentHash)
                    .path("tags")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(tag))) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("Success: Delete tag")
        void deleteTag__whenValidTag__thenReturnNoContent() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate = DatasetVersionCreate.builder()
                    .tag("staging")
                    .build();

            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(versionCreate))) {

                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
            }

            // When - Delete tag
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("tags")
                    .path("staging")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            // Verify tag was removed
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var page = response.readEntity(DatasetVersionPage.class);
                assertThat(page.content().get(0).tags()).doesNotContain("staging");
            }
        }

        @Test
        @DisplayName("Error: Delete non-existent tag")
        void deleteTag__whenTagNotFound__thenReturnNotFound() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When
            try (var response = client.target(DATASET_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("tags")
                    .path("nonexistent")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }
}
