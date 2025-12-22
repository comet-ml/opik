package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
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
import com.google.common.net.HttpHeaders;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
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
    private ClientSupport client;
    private DatasetResourceClient datasetResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
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
        List<DatasetItem> itemsList = IntStream.range(0, count)
                .mapToObj(i -> {
                    DatasetItem item = factory.manufacturePojo(DatasetItem.class);
                    Map<String, JsonNode> data = Map.of(
                            "input", JsonUtils.getJsonNodeFromString("\"test input " + i + "\""),
                            "output", JsonUtils.getJsonNodeFromString("\"test output " + i + "\""));
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

    private List<DatasetItem> generateDatasetItems(int count) {
        return PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                .limit(count)
                .map(item -> {
                    Map<String, JsonNode> data = Map.of(
                            "input", JsonUtils.getJsonNodeFromString("\"test input " + UUID.randomUUID() + "\""),
                            "output", JsonUtils.getJsonNodeFromString("\"test output " + UUID.randomUUID() + "\""));
                    return item.toBuilder()
                            .id(null) // Use null for new items
                            .data(data)
                            .build();
                })
                .toList();
    }

    private void deleteDatasetItem(UUID datasetId, UUID itemId) {
        try (var actualResponse = client.target("%s/v1/private/datasets".formatted(baseURI))
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.json(DatasetItemsDelete.builder()
                        .itemIds(Set.of(itemId))
                        .build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
        }
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
                                    .tags(List.of("baseline"))
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
            assertThat(version.itemsTotal()).isEqualTo(3);
            assertThat(version.itemsAdded()).isEqualTo(3);
            assertThat(version.itemsModified()).isEqualTo(0);
            assertThat(version.itemsDeleted()).isEqualTo(0);

            // Then - Tag assertions
            assertThat(version.tags()).containsAll(expectedTags);

            // Then - isLatest should be true for newly created versions
            assertThat(version.isLatest()).isTrue();

            // Then - Metadata assertions (if expected)
            if (expectedMetadata != null) {
                assertThat(version.metadata()).isNotNull();
                expectedMetadata.forEach((key, value) -> assertThat(version.metadata().get(key)).isEqualTo(value));
            }
        }

        @Test
        @DisplayName("Success: Create version with multiple tags")
        void createVersion__whenMultipleTags__thenAllTagsCreated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            // When - Create version with multiple tags using the new 'tags' field
            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("baseline", "v1.0", "production"))
                    .changeDescription("Version with multiple tags")
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);

            // Then - Verify all tags were created (including automatic 'latest' tag)
            assertThat(version.tags())
                    .containsAll(List.of("baseline", "v1.0", "production", DatasetVersionService.LATEST_TAG));
            assertThat(version.changeDescription()).isEqualTo("Version with multiple tags");
            assertThat(version.isLatest()).isTrue();
        }

        @Test
        @DisplayName("Success: Duplicate tags in request payload are deduplicated")
        void createVersion__whenDuplicateTagsInPayload__thenDeduplicatedAndCreated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // When - Create version with duplicate tags in the request
            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("v1", "production", "v1", "production", "v1"))
                    .changeDescription("Version with duplicate tags in request")
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);

            // Then - Verify tags were deduplicated (only unique tags created)
            assertThat(version.tags())
                    .containsExactlyInAnyOrder("v1", "production", DatasetVersionService.LATEST_TAG);
            assertThat(version.changeDescription()).isEqualTo("Version with duplicate tags in request");
        }

        @Test
        @DisplayName("Error: Duplicate tag in multiple tags list")
        void createVersion__whenDuplicateTagInList__thenReturnConflict() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // Create first version with a tag
            var versionCreate1 = DatasetVersionCreate.builder()
                    .tags(List.of("v1.0"))
                    .build();
            datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);

            // Modify dataset
            createDatasetItems(datasetId, 3);

            // When - Try to create another version with a tag that already exists
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tags(List.of("v1.0", "v2.0")) // v1.0 already exists
                    .build();

            try (var response = datasetResourceClient.callCommitVersion(datasetId, versionCreate2, API_KEY,
                    TEST_WORKSPACE)) {
                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).contains("One or more tags already exist for this dataset");
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
                    .tags(List.of("v1"))
                    .changeDescription("Version 1")
                    .build();

            var version1 = datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);
            UUID version1Id = version1.id();
            assertThat(version1.tags()).contains("v1", DatasetVersionService.LATEST_TAG);

            // Add more items to change the dataset
            createDatasetItems(datasetId, 2);

            // When - Create second version with different content
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tags(List.of("v2"))
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
                    .tags(List.of("v1.0"))
                    .build();

            // Create first version with tag
            datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);

            // Modify dataset by adding more items
            createDatasetItems(datasetId, 3);

            // Try to create another version with same tag
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tags(List.of("v1.0")) // Duplicate tag
                    .build();

            // When
            try (var response = datasetResourceClient.callCommitVersion(datasetId, versionCreate2, API_KEY,
                    TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).contains("One or more tags already exist for this dataset");
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
                    .tags(List.of("v1"))
                    .changeDescription("First version")
                    .build();

            var version1 = datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);
            String version1Hash = version1.versionHash();
            assertThat(version1.tags()).contains("v1", DatasetVersionService.LATEST_TAG);

            // When - Create second version
            var versionCreate2 = DatasetVersionCreate.builder()
                    .tags(List.of("v2"))
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

            // Verify first version no longer has 'latest' tag and isLatest is false
            assertThat(version1Retrieved.tags()).contains("v1").doesNotContain(DatasetVersionService.LATEST_TAG);
            assertThat(version1Retrieved.isLatest()).isFalse();

            // Verify second version has 'latest' tag and isLatest is true
            assertThat(version2Retrieved.tags()).contains("v2", DatasetVersionService.LATEST_TAG);
            assertThat(version2Retrieved.isLatest()).isTrue();

            // Verify dataset's latestVersion field contains the latest version summary
            var dataset = datasetResourceClient.getDatasetById(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(dataset.latestVersion()).isNotNull();
            assertThat(dataset.latestVersion().id()).isEqualTo(version2Retrieved.id());
            assertThat(dataset.latestVersion().versionHash()).isEqualTo(version2Hash);
            assertThat(dataset.latestVersion().changeDescription()).isEqualTo("Second version");
            assertThat(dataset.latestVersion().tags()).contains("v2", DatasetVersionService.LATEST_TAG);
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
                        .tags(List.of("v" + i))
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
                    .tags(List.of("v1.0"))
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
                    .tags(List.of("staging"))
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
                    .tags(List.of("v1"))
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

    @Nested
    @DisplayName("Update Dataset Version:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateVersion {

        Stream<Arguments> updateVersionSuccessProvider() {
            return Stream.of(
                    // Test case 1: Update change_description only
                    Arguments.of(
                            "Update change_description only",
                            DatasetVersionCreate.builder()
                                    .changeDescription("Original description")
                                    .build(),
                            DatasetVersionUpdate.builder()
                                    .changeDescription("Updated description")
                                    .build(),
                            "Updated description",
                            List.of(DatasetVersionService.LATEST_TAG)),
                    // Test case 2: Add tags only
                    Arguments.of(
                            "Add tags to existing version",
                            DatasetVersionCreate.builder()
                                    .tags(List.of("v1"))
                                    .build(),
                            DatasetVersionUpdate.builder()
                                    .tagsToAdd(List.of("production", "reviewed"))
                                    .build(),
                            null,
                            List.of("v1", "production", "reviewed", DatasetVersionService.LATEST_TAG)),
                    // Test case 3: Update both change_description and add tags
                    Arguments.of(
                            "Update both change_description and add tags",
                            DatasetVersionCreate.builder()
                                    .changeDescription("Original")
                                    .build(),
                            DatasetVersionUpdate.builder()
                                    .changeDescription("Updated description")
                                    .tagsToAdd(List.of("new-tag"))
                                    .build(),
                            "Updated description",
                            List.of("new-tag", DatasetVersionService.LATEST_TAG)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("updateVersionSuccessProvider")
        @DisplayName("Success:")
        void updateVersion__whenValidRequest__thenVersionUpdated(String testName, DatasetVersionCreate versionCreate,
                DatasetVersionUpdate updateRequest, String expectedDescription, List<String> expectedTags) {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When
            var updatedVersion = datasetResourceClient.updateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE);

            // Then
            assertThat(updatedVersion.versionHash()).isEqualTo(versionHash);
            assertThat(updatedVersion.isLatest()).isTrue();
            if (expectedDescription != null) {
                assertThat(updatedVersion.changeDescription()).isEqualTo(expectedDescription);
            }
            assertThat(updatedVersion.tags()).containsAll(expectedTags);
        }

        @Test
        @DisplayName("Error: Update version with duplicate tag")
        void updateVersion__whenDuplicateTag__thenReturnConflict() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("existing-tag"))
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When - Try to add a tag that already exists
            var updateRequest = DatasetVersionUpdate.builder()
                    .tagsToAdd(List.of("existing-tag"))
                    .build();

            try (var response = datasetResourceClient.callUpdateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE)) {
                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).contains("One or more tags already exist for this dataset");
            }
        }

        @Test
        @DisplayName("Success: Update version with duplicate tags in payload are deduplicated")
        void updateVersion__whenDuplicateTagsInPayload__thenDeduplicatedAndAdded() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("v1"))
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);
            String versionHash = version.versionHash();

            // When - Update with duplicate tags in the request
            var updateRequest = DatasetVersionUpdate.builder()
                    .tagsToAdd(List.of("new-tag", "another-tag", "new-tag", "another-tag"))
                    .build();

            var updatedVersion = datasetResourceClient.updateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE);

            // Then - Verify tags were deduplicated
            assertThat(updatedVersion.tags())
                    .containsExactlyInAnyOrder("v1", "new-tag", "another-tag", DatasetVersionService.LATEST_TAG);
        }

        @Test
        @DisplayName("Error: Update non-existent version")
        void updateVersion__whenVersionNotFound__thenReturnNotFound() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            var nonExistentHash = "nonexistent";

            var updateRequest = DatasetVersionUpdate.builder()
                    .changeDescription("Updated")
                    .build();

            // When
            try (var response = datasetResourceClient.callUpdateVersion(datasetId, nonExistentHash, updateRequest,
                    API_KEY, TEST_WORKSPACE)) {
                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("Version Snapshot Tests:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class VersionSnapshotTests {

        @Test
        @DisplayName("Success: Create version and verify snapshot")
        void commitVersion__whenItemsExist__thenCreateSnapshot() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            // When - Commit version
            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("v1"))
                    .changeDescription("Initial version with 3 items")
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);

            // Then - Verify version was created with correct statistics
            assertThat(version.itemsTotal()).isEqualTo(3);
            assertThat(version.itemsAdded()).isEqualTo(3); // First version, all items are new
            assertThat(version.itemsModified()).isEqualTo(0);
            assertThat(version.itemsDeleted()).isEqualTo(0);

            // Verify snapshot items can be fetched by version
            var versionedItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version.versionHash(), API_KEY, TEST_WORKSPACE);

            assertThat(versionedItems.content()).hasSize(3);
            assertThat(versionedItems.total()).isEqualTo(3);
        }

        @Test
        @DisplayName("Success: Fetch items by version hash and tag")
        void getItems__whenVersionSpecified__thenReturnVersionedItems() {
            // Given - Create dataset with items and commit version
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("baseline"))
                    .build();

            var version = datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);

            // When - Fetch by version hash
            var itemsByHash = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version.versionHash(), API_KEY, TEST_WORKSPACE);

            // Then - Verify items returned
            assertThat(itemsByHash.content()).hasSize(2);

            // When - Fetch by version tag
            var itemsByTag = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "baseline", API_KEY, TEST_WORKSPACE);

            // Then - Verify items returned
            assertThat(itemsByTag.content()).hasSize(2);

            // When - Fetch by 'latest' tag
            var itemsByLatest = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE);

            // Then - Verify items returned
            assertThat(itemsByLatest.content()).hasSize(2);
        }

        @Test
        @DisplayName("Success: Calculate diff statistics correctly")
        void commitVersion__whenItemsModified__thenCalculateDiffCorrectly() {
            // Given - Create dataset with 3 items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(3);

            var batch1 = DatasetItemBatch.builder()
                    .items(originalItems)
                    .datasetId(datasetId)
                    .build();
            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // Commit first version
            var version1 = datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v1")).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            assertThat(version1.itemsTotal()).isEqualTo(3);
            assertThat(version1.itemsAdded()).isEqualTo(3);

            // Get created items to obtain their IDs
            var createdItemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 10, null, API_KEY,
                    TEST_WORKSPACE);
            var createdItems = createdItemsPage.content();

            // When - Add 2 new items and delete 1 item
            var newItems = generateDatasetItems(2);
            var batch2 = DatasetItemBatch.builder()
                    .items(newItems)
                    .datasetId(datasetId)
                    .build();
            datasetResourceClient.createDatasetItems(batch2, TEST_WORKSPACE, API_KEY);

            // Delete first item
            var itemToDelete = createdItems.get(0);
            deleteDatasetItem(datasetId, itemToDelete.id());

            // Commit second version
            var version2 = datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v2")).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // Then - Verify diff statistics
            assertThat(version2.itemsTotal()).isEqualTo(4); // 3 - 1 + 2 = 4 items
            assertThat(version2.itemsAdded()).isEqualTo(2);
            assertThat(version2.itemsDeleted()).isEqualTo(1);

            // Verify v1 items still accessible
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE);
            assertThat(v1Items.content()).hasSize(3);

            // Verify v2 items reflect changes
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE);
            assertThat(v2Items.content()).hasSize(4);
        }

        @Test
        @DisplayName("Success: UUID-based hash allows same content in different versions")
        void commitVersion__whenSameContent__thenGenerateUniqueHash() {
            // Given - Create two datasets with identical items
            var dataset1Id = createDataset(UUID.randomUUID().toString());
            var dataset2Id = createDataset(UUID.randomUUID().toString());

            // Create identical items for both datasets
            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .data(Map.of("key", JsonUtils.getJsonNodeFromString("\"value\"")))
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder()
                            .datasetId(dataset1Id)
                            .items(List.of(item))
                            .build(),
                    TEST_WORKSPACE,
                    API_KEY);

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder()
                            .datasetId(dataset2Id)
                            .items(List.of(item))
                            .build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // When - Commit versions for both datasets
            var version1 = datasetResourceClient.commitVersion(
                    dataset1Id,
                    DatasetVersionCreate.builder().build(),
                    API_KEY,
                    TEST_WORKSPACE);

            var version2 = datasetResourceClient.commitVersion(
                    dataset2Id,
                    DatasetVersionCreate.builder().build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // Then - Verify both have different hashes (UUID-based, not content-based)
            // This allows restoring versions with identical content
            assertThat(version1.versionHash()).isNotEqualTo(version2.versionHash());
        }

        @Test
        @DisplayName("Error: Fetch items with non-existent version")
        void getItems__whenVersionNotFound__thenReturnNotFound() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // When - Try to fetch with non-existent version
            try (var response = datasetResourceClient.callGetDatasetItems(
                    datasetId, 1, 10, "nonexistent", API_KEY, TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("Success: Same item in multiple versions should have different IDs")
        void commitVersion__whenSameItemInMultipleVersions__thenGenerateNewIdsPerVersion() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(2);

            var batch = DatasetItemBatch.builder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get the draft item IDs
            var draftItemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 10, null, API_KEY,
                    TEST_WORKSPACE);
            var draftItems = draftItemsPage.content();
            var draftItemIds = draftItems.stream().map(DatasetItem::id).toList();

            // When - Commit version 1 (snapshot the current items)
            datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v1")).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // Get items from version 1
            var v1ItemsPage = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE);
            var v1Items = v1ItemsPage.content();
            var v1ItemIds = v1Items.stream().map(DatasetItem::id).toList();
            var v1DraftItemIds = v1Items.stream().map(DatasetItem::draftItemId).toList();

            // When - Commit version 2 (snapshot the same items without any changes)
            datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v2")).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // Get items from version 2
            var v2ItemsPage = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE);
            var v2Items = v2ItemsPage.content();
            var v2ItemIds = v2Items.stream().map(DatasetItem::id).toList();
            var v2DraftItemIds = v2Items.stream().map(DatasetItem::draftItemId).toList();

            // Then - Verify that:
            // 1. Each version has the correct number of items
            assertThat(v1Items).hasSize(2);
            assertThat(v2Items).hasSize(2);

            // 2. Each version snapshot gets unique IDs (immutable snapshots have their own identifiers)
            assertThat(v1ItemIds).doesNotContainAnyElementsOf(v2ItemIds)
                    .as("Version 1 and version 2 should have different item IDs (unique per snapshot)");

            // 3. The draftItemId field maintains the link to the original draft items
            assertThat(v1DraftItemIds).containsExactlyInAnyOrderElementsOf(draftItemIds)
                    .as("Version 1 draftItemIds should match original draft item IDs");

            assertThat(v2DraftItemIds).containsExactlyInAnyOrderElementsOf(draftItemIds)
                    .as("Version 2 draftItemIds should match original draft item IDs");

            assertThat(v2DraftItemIds).containsExactlyInAnyOrderElementsOf(v1DraftItemIds)
                    .as("Both versions should reference the same draft items via draftItemId");

            // 4. The data content should be identical across all versions
            var v1Data = v1Items.stream().map(DatasetItem::data).toList();
            var v2Data = v2Items.stream().map(DatasetItem::data).toList();
            assertThat(v1Data).containsExactlyInAnyOrderElementsOf(v2Data)
                    .as("Data content should be identical across versions");
        }

        @Test
        @DisplayName("Success: Compare version with draft using diff endpoint")
        void compareVersions__whenTwoVersions__thenReturnDiffStatistics() {
            // Given - Create dataset with 3 items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(3);

            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(originalItems)
                    .build();

            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // Fetch created items to get their IDs
            var createdItems = datasetResourceClient.getDatasetItems(datasetId, 1, 10, null, API_KEY, TEST_WORKSPACE)
                    .content();

            // Commit first version
            var versionCreate1 = DatasetVersionCreate.builder()
                    .tags(List.of("v1"))
                    .build();

            datasetResourceClient.commitVersion(datasetId, versionCreate1, API_KEY, TEST_WORKSPACE);

            // Modify items in draft: delete 1, modify 1, add 2
            var itemToDelete = createdItems.get(0);
            var itemToModify = createdItems.get(1);

            // Delete one item
            datasetResourceClient.deleteDatasetItems(List.of(itemToDelete.id()), API_KEY, TEST_WORKSPACE);

            // Modify one item
            var modifiedItem = itemToModify.toBuilder()
                    .data(Map.of("modified", JsonUtils.getJsonNodeFromString("true")))
                    .build();

            datasetResourceClient.patchDatasetItem(itemToModify.id(), modifiedItem, API_KEY, TEST_WORKSPACE);

            // Add new items
            var newItems = generateDatasetItems(2);
            var batch3 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(newItems)
                    .build();

            datasetResourceClient.createDatasetItems(batch3, TEST_WORKSPACE, API_KEY);

            // When - Compare latest version with draft using diff endpoint
            var diff = datasetResourceClient.compareVersions(datasetId, null, null, API_KEY, TEST_WORKSPACE);

            // Then - Verify diff statistics
            // latest (v1): 3 items (item0, item1, item2)
            // draft: 4 items (item1-modified, item2, newItem1, newItem2)
            // Expected: 2 added, 1 modified, 1 deleted, 1 unchanged
            assertThat(diff.fromVersion()).isEqualTo(DatasetVersionService.LATEST_TAG);
            assertThat(diff.toVersion()).isEqualTo("draft");
            assertThat(diff.statistics().itemsAdded()).isEqualTo(2)
                    .as("2 new items were added");
            assertThat(diff.statistics().itemsModified()).isEqualTo(1)
                    .as("1 item was modified (item1)");
            assertThat(diff.statistics().itemsDeleted()).isEqualTo(1)
                    .as("1 item was deleted (item0)");
            assertThat(diff.statistics().itemsUnchanged()).isEqualTo(1)
                    .as("1 item remained unchanged (item2)");
        }

        @Test
        @DisplayName("Success: Compare version with draft")
        void compareVersions__whenVersionAndDraft__thenReturnDiffStatistics() {
            // Given - Create dataset with 2 items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(2);

            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(originalItems)
                    .build();

            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // Commit version
            var versionCreate = DatasetVersionCreate.builder()
                    .tags(List.of("v1"))
                    .build();

            datasetResourceClient.commitVersion(datasetId, versionCreate, API_KEY, TEST_WORKSPACE);

            // Modify draft: add 1 new item
            var newItem = generateDatasetItems(1).get(0);
            var batch2 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(List.of(newItem))
                    .build();

            datasetResourceClient.createDatasetItems(batch2, TEST_WORKSPACE, API_KEY);

            // When - Compare latest version with draft (no parameters needed)
            var diff = datasetResourceClient.compareVersions(datasetId, null, null, API_KEY, TEST_WORKSPACE);

            // Then - Verify diff statistics
            assertThat(diff.fromVersion()).isEqualTo(DatasetVersionService.LATEST_TAG);
            assertThat(diff.toVersion()).isEqualTo("draft");
            assertThat(diff.statistics().itemsAdded()).isEqualTo(1)
                    .as("1 new item was added to draft");
            assertThat(diff.statistics().itemsModified()).isEqualTo(0)
                    .as("No items were modified");
            assertThat(diff.statistics().itemsDeleted()).isEqualTo(0)
                    .as("No items were deleted");
            assertThat(diff.statistics().itemsUnchanged()).isEqualTo(2)
                    .as("2 items remained unchanged");

            // When - Call again to verify consistency
            var diff2 = datasetResourceClient.compareVersions(datasetId, null, null,
                    API_KEY, TEST_WORKSPACE);

            // Then - Verify same diff statistics
            assertThat(diff2.fromVersion()).isEqualTo(DatasetVersionService.LATEST_TAG);
            assertThat(diff2.toVersion()).isEqualTo("draft");
            assertThat(diff2.statistics().itemsAdded()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Restore Dataset Version:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RestoreVersion {

        @Test
        @DisplayName("Success: Restore to previous version creates new version")
        void restoreVersion__whenNotLatest__thenCreateNewVersion() {
            // Given - Create dataset with 3 items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(3);

            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(originalItems)
                    .build();
            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // Commit version 1
            var version1 = datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v1")).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // Modify draft: delete 1 item
            var createdItemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 10, null, API_KEY,
                    TEST_WORKSPACE);
            var createdItems = createdItemsPage.content();
            var itemToDelete = createdItems.get(0);
            datasetResourceClient.deleteDatasetItems(List.of(itemToDelete.id()), API_KEY, TEST_WORKSPACE);

            // Commit version 2 (now has 2 items)
            var version2 = datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v2")).build(),
                    API_KEY,
                    TEST_WORKSPACE);
            assertThat(version2.itemsTotal()).isEqualTo(2);

            // When - Restore to v1
            var restoredVersion = datasetResourceClient.restoreVersion(datasetId, "v1", API_KEY, TEST_WORKSPACE);

            // Then - Should have created a new version with 3 items
            assertThat(restoredVersion.id()).isNotEqualTo(version1.id()).isNotEqualTo(version2.id());
            assertThat(restoredVersion.itemsTotal()).isEqualTo(3);
            assertThat(restoredVersion.changeDescription()).contains("Restored from version: v1");

            // Verify the items in the latest version
            var latestItems = datasetResourceClient.getDatasetItems(datasetId, 1, 10,
                    DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE);
            assertThat(latestItems.content()).hasSize(3);
        }

        @Test
        @DisplayName("Success: Restore to latest version returns it as-is (no-op)")
        void restoreVersion__whenLatest__thenNoOp() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(2);

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Commit version
            var version = datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().tags(List.of("v1")).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // When - Restore to v1 (which is already latest)
            var restoredVersion = datasetResourceClient.restoreVersion(datasetId, "v1", API_KEY, TEST_WORKSPACE);

            // Then - Should return the same version (no-op)
            assertThat(restoredVersion.id()).isEqualTo(version.id());
            assertThat(restoredVersion.itemsTotal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Success: Restore version by hash instead of tag")
        void restoreVersion__whenByHash__thenSuccess() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(2);

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Commit version 1
            var version1 = datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // Add more items and commit version 2
            var newItems = generateDatasetItems(1);
            var batch2 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(newItems)
                    .build();
            datasetResourceClient.createDatasetItems(batch2, TEST_WORKSPACE, API_KEY);

            datasetResourceClient.commitVersion(
                    datasetId,
                    DatasetVersionCreate.builder().build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // When - Restore to v1 by hash
            var restoredVersion = datasetResourceClient.restoreVersion(datasetId, version1.versionHash(),
                    API_KEY, TEST_WORKSPACE);

            // Then - Verify restore succeeded
            assertThat(restoredVersion.itemsTotal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Failure: Restore to non-existent version returns 404")
        void restoreVersion__whenVersionNotFound__then404() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When/Then - Restore to non-existent version
            try (var response = datasetResourceClient.callRestoreVersion(datasetId, "non-existent", API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(404);
            }
        }
    }
}
