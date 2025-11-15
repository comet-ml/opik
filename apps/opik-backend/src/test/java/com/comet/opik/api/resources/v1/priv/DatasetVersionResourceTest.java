package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
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
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Version Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetVersionResourceTest {

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
    private DatasetResourceClient datasetResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

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

        return datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
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

        datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);
    }

    @Nested
    @DisplayName("Create Dataset Version:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateVersion {

        static Stream<Arguments> versionCreateTestCases() {
            return Stream.of(
                    // Test case 1: Version without tag
                    Arguments.of(
                            "Create version without tag",
                            DatasetVersionCreate.builder()
                                    .changeDescription("Initial version")
                                    .build(),
                            List.of(DatasetVersionService.LATEST_TAG),
                            null),
                    // Test case 2: Version with tag
                    Arguments.of(
                            "Create version with tag",
                            DatasetVersionCreate.builder()
                                    .tag("baseline")
                                    .changeDescription("Baseline version")
                                    .build(),
                            List.of("baseline", DatasetVersionService.LATEST_TAG),
                            null),
                    // Test case 3: Version with metadata
                    Arguments.of(
                            "Create version with metadata",
                            DatasetVersionCreate.builder()
                                    .changeDescription("Test version")
                                    .metadata(Map.of(
                                            "author", "test-user",
                                            "purpose", "testing",
                                            "version_number", "1"))
                                    .build(),
                            List.of(DatasetVersionService.LATEST_TAG),
                            Map.of(
                                    "author", "test-user",
                                    "purpose", "testing",
                                    "version_number", "1")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("versionCreateTestCases")
        @DisplayName("Success: Create version with various inputs")
        void createVersion__whenValidRequest__thenReturnCreated(
                String testName,
                DatasetVersionCreate versionCreate,
                List<String> expectedTags,
                Map<String, String> expectedMetadata) {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            // When
            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);

            // Then - Common assertions
            assertThat(version.id()).isNotNull();
            assertThat(version.datasetId()).isEqualTo(datasetId);
            assertThat(version.versionHash()).isNotEmpty();
            assertThat(version.changeDescription()).isEqualTo(versionCreate.changeDescription());
            assertThat(version.createdBy()).isEqualTo(USER);
            assertThat(version.createdAt()).isNotNull();
            // TODO OPIK-3015: Assert on actual item counts once snapshot creation is implemented
            assertThat(version.itemsCount()).isEqualTo(0);
            assertThat(version.itemsAdded()).isEqualTo(0);
            assertThat(version.itemsModified()).isEqualTo(0);
            assertThat(version.itemsDeleted()).isEqualTo(0);

            // Then - Tag assertions
            assertThat(version.tags()).containsAll(expectedTags);

            // Then - Metadata assertions (if expected)
            if (expectedMetadata != null) {
                assertThat(version.metadata()).isNotNull();
                expectedMetadata.forEach((key, value) -> assertThat(version.metadata().get(key)).isEqualTo(value));
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

            var version1 = datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);
            UUID version1Id = version1.id();
            assertThat(version1.tags()).contains("v1", DatasetVersionService.LATEST_TAG);

            // Add more items to change the dataset
            createDatasetItems(datasetId, 2);

            // When - Create second version with different content
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tag("v2")
                    .changeDescription("Version 2")
                    .build();

            // Then - Should create a new version (different hash due to changed items)
            var version2 = datasetResourceClient.commitVersion(datasetId, versionCreate2, API_KEY, TEST_WORKSPACE);
            assertThat(version2.id()).isNotEqualTo(version1Id); // Different version created
            assertThat(version2.tags()).contains("v2", DatasetVersionService.LATEST_TAG); // Latest tag moved to version 2
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
            datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);

            // Modify dataset by adding more items
            createDatasetItems(datasetId, 3);

            // Try to create another version with same tag
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tag("v1.0") // Duplicate tag
                    .build();

            // When
            try (var response = datasetResourceClient.callCommitVersion(datasetId, versionCreate2, API_KEY,
                    TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors())
                        .contains(DatasetVersionService.ERROR_TAG_EXISTS.formatted("v1.0"));
            }
        }

        @Test
        @DisplayName("Success: Latest tag automatically moves to new version")
        void createVersion__whenMultipleVersions__thenLatestTagMovesToNewestVersion() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // When - Create first version
            var versionCreate1 = DatasetVersionCreate.builder()
                    .tag("v1")
                    .changeDescription("First version")
                    .build();

            var version1 = datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);
            String version1Hash = version1.versionHash();
            assertThat(version1.tags()).contains("v1", DatasetVersionService.LATEST_TAG);

            // When - Create second version
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tag("v2")
                    .changeDescription("Second version")
                    .build();

            var version2 = datasetResourceClient.commitVersion(datasetId, versionCreate2, API_KEY, TEST_WORKSPACE);
            String version2Hash = version2.versionHash();
            assertThat(version2.tags()).contains("v2", DatasetVersionService.LATEST_TAG);

            // Then - List versions and verify tag assignments
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(page.content()).hasSize(2);

            // Find versions by hash
            var version1Retrieved = page.content().stream()
                    .filter(v -> v.versionHash().equals(version1Hash))
                    .findFirst()
                    .orElseThrow();
            var version2Retrieved = page.content().stream()
                    .filter(v -> v.versionHash().equals(version2Hash))
                    .findFirst()
                    .orElseThrow();

            // Verify first version no longer has 'latest' tag
            assertThat(version1Retrieved.tags()).contains("v1").doesNotContain(DatasetVersionService.LATEST_TAG);

            // Verify second version has 'latest' tag
            assertThat(version2Retrieved.tags()).contains("v2", DatasetVersionService.LATEST_TAG);
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
            final int VERSION_COUNT = 3;

            // Create multiple versions
            for (int i = 1; i <= VERSION_COUNT; i++) {
                createDatasetItems(datasetId, 1);

                var versionCreate = DatasetVersionCreate.builder()
                        .tag("v" + i)
                        .changeDescription("Version " + i)
                        .build();

                datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            }

            // When - Get first page with size 2
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE, 1, 2);

            // Then
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(2);
            assertThat(page.total()).isEqualTo(VERSION_COUNT);
            assertThat(page.content()).hasSize(2);

            // Verify versions are sorted by created_at DESC (newest first)
            assertThat(page.content().getFirst().tags()).contains("v3");
            assertThat(page.content().get(1).tags()).contains("v2");

            // When - Get second page
            var page2 = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE, 2, 2);

            // Then
            assertThat(page2.content()).hasSize(1);
            assertThat(page2.content().getFirst().tags()).contains("v1");
        }

        @Test
        @DisplayName("Success: List versions for empty dataset")
        void listVersions__whenNoVersions__thenReturnEmptyPage() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);

            // Then
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isEqualTo(0);
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

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When - Add tag to version
            var tag = DatasetVersionTag.builder()
                    .tag("production")
                    .build();

            datasetResourceClient.createVersionTag(datasetId, versionHash, tag, API_KEY, TEST_WORKSPACE);

            // Then - Verify tag was added (along with automatic 'latest' tag)
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(page.content().getFirst().tags()).contains("production", DatasetVersionService.LATEST_TAG);
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

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When - Try to add same tag again
            var tag = DatasetVersionTag.builder()
                    .tag("v1.0")
                    .build();

            try (var response = datasetResourceClient.callCreateVersionTag(datasetId, versionHash, tag, API_KEY,
                    TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors())
                        .contains(DatasetVersionService.ERROR_TAG_EXISTS.formatted("v1.0"));
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
            try (var response = datasetResourceClient.callCreateVersionTag(datasetId, nonExistentHash, tag, API_KEY,
                    TEST_WORKSPACE)) {

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

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When - Delete tag
            datasetResourceClient.deleteVersionTag(datasetId, versionHash, "staging", API_KEY, TEST_WORKSPACE);

            // Then - Verify tag was removed
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(page.content().getFirst().tags()).doesNotContain("staging");
        }

        @Test
        @DisplayName("Success: Delete non-existent tag is idempotent")
        void deleteTag__whenTagNotFound__thenReturnNoContent() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // Create a version to get a valid versionHash
            var versionCreate = DatasetVersionCreate.builder().build();
            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When - Try to delete a non-existent tag (should be idempotent)
            datasetResourceClient.deleteVersionTag(datasetId, versionHash, "nonexistent", API_KEY, TEST_WORKSPACE);

            // Then - Should succeed without error (idempotent operation)
        }

        @Test
        @DisplayName("Error: Cannot delete 'latest' tag")
        void deleteTag__whenLatestTag__thenReturnBadRequest() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // Create a version (which automatically gets 'latest' tag)
            var versionCreate = DatasetVersionCreate.builder()
                    .tag("v1")
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();
            assertThat(version.tags()).contains("v1", DatasetVersionService.LATEST_TAG);

            // When - Try to delete 'latest' tag
            try (var response = datasetResourceClient.callDeleteVersionTag(datasetId, versionHash,
                    DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).contains(
                        DatasetVersionService.ERROR_CANNOT_DELETE_LATEST_TAG
                                .formatted(DatasetVersionService.LATEST_TAG));
            }

            // Verify 'latest' tag still exists on the version by listing versions
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(page.content()).hasSize(1);

            var versionFromList = page.content().getFirst();
            assertThat(versionFromList.versionHash()).isEqualTo(versionHash);
            assertThat(versionFromList.tags()).contains("v1", DatasetVersionService.LATEST_TAG);
        }
    }
}
