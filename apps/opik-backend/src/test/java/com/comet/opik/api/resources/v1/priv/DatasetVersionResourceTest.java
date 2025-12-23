package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemUpdate;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.DatasetItemFilter;
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
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    Map<String, JsonNode> data = Map.of(
                            "input", JsonUtils.getJsonNodeFromString("\"test input " + UUID.randomUUID() + "\""),
                            "output", JsonUtils.getJsonNodeFromString("\"test output " + UUID.randomUUID() + "\""));
                    return DatasetItem.builder()
                            .source(DatasetItemSource.SDK) // Required field
                            .data(data)
                            .build();
                })
                .toList();
    }

    /**
     * Creates items and returns the auto-created version (when versioning toggle is ON).
     * This replaces the commitVersion pattern: items are created and version is fetched.
     */
    private com.comet.opik.api.DatasetVersion createItemsAndGetVersion(UUID datasetId, int itemCount) {
        createDatasetItems(datasetId, itemCount);
        var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
        assertThat(versions.content()).isNotEmpty();
        return versions.content().get(0); // Latest version is first
    }

    /**
     * Gets the latest version for a dataset.
     */
    private com.comet.opik.api.DatasetVersion getLatestVersion(UUID datasetId) {
        var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
        assertThat(versions.content()).isNotEmpty();
        return versions.content().get(0);
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

    private void deleteDatasetItemsByFilters(UUID datasetId, List<DatasetItemFilter> filters) {
        try (var actualResponse = client.target("%s/v1/private/datasets".formatted(baseURI))
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.json(DatasetItemsDelete.builder()
                        .datasetId(datasetId)
                        .filters(filters)
                        .build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
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
            final int VERSION_COUNT = 3;

            // Create multiple versions (each createDatasetItems call creates a version with toggle ON)
            for (int i = 1; i <= VERSION_COUNT; i++) {
                createDatasetItems(datasetId, 1);
            }

            // When - Get first page with size 2
            var page = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE, 1, 2);

            // Then
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(2);
            assertThat(page.total()).isEqualTo(VERSION_COUNT);
            assertThat(page.content()).hasSize(2);

            // Verify versions are sorted by created_at DESC (newest first)
            // With auto-created versions, the latest should be first
            assertThat(page.content().getFirst().isLatest()).isTrue();

            // When - Get second page
            var page2 = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE, 2, 2);

            // Then
            assertThat(page2.content()).hasSize(1);
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
            var version = getLatestVersion(datasetId);
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
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // Add a tag first
            var tag = DatasetVersionTag.builder()
                    .tag("v1.0")
                    .build();
            datasetResourceClient.createVersionTag(datasetId, versionHash, tag, API_KEY, TEST_WORKSPACE);

            // When - Try to add same tag again
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
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // First add a tag to delete
            var tag = DatasetVersionTag.builder()
                    .tag("staging")
                    .build();
            datasetResourceClient.createVersionTag(datasetId, versionHash, tag, API_KEY, TEST_WORKSPACE);

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
            var version = getLatestVersion(datasetId);
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
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();
            assertThat(version.tags()).contains(DatasetVersionService.LATEST_TAG);

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
            assertThat(versionFromList.tags()).contains(DatasetVersionService.LATEST_TAG);
        }
    }

    @Nested
    @DisplayName("Update Dataset Version:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateVersion {

        @Test
        @DisplayName("Success: Update change_description")
        void updateVersion__whenUpdateDescription__thenVersionUpdated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // When
            var updateRequest = DatasetVersionUpdate.builder()
                    .changeDescription("Updated description")
                    .build();

            var updatedVersion = datasetResourceClient.updateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE);

            // Then
            assertThat(updatedVersion.versionHash()).isEqualTo(versionHash);
            assertThat(updatedVersion.isLatest()).isTrue();
            assertThat(updatedVersion.changeDescription()).isEqualTo("Updated description");
        }

        @Test
        @DisplayName("Success: Add tags to existing version")
        void updateVersion__whenAddTags__thenVersionUpdated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // When - Add tags via update
            var updateRequest = DatasetVersionUpdate.builder()
                    .tagsToAdd(List.of("v1", "production", "reviewed"))
                    .build();

            var updatedVersion = datasetResourceClient.updateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE);

            // Then
            assertThat(updatedVersion.versionHash()).isEqualTo(versionHash);
            assertThat(updatedVersion.isLatest()).isTrue();
            assertThat(updatedVersion.tags()).containsAll(
                    List.of("v1", "production", "reviewed", DatasetVersionService.LATEST_TAG));
        }

        @Test
        @DisplayName("Success: Update both change_description and add tags")
        void updateVersion__whenUpdateDescriptionAndAddTags__thenVersionUpdated() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // When
            var updateRequest = DatasetVersionUpdate.builder()
                    .changeDescription("Updated description")
                    .tagsToAdd(List.of("new-tag"))
                    .build();

            var updatedVersion = datasetResourceClient.updateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE);

            // Then
            assertThat(updatedVersion.versionHash()).isEqualTo(versionHash);
            assertThat(updatedVersion.isLatest()).isTrue();
            assertThat(updatedVersion.changeDescription()).isEqualTo("Updated description");
            assertThat(updatedVersion.tags()).containsAll(List.of("new-tag", DatasetVersionService.LATEST_TAG));
        }

        @Test
        @DisplayName("Error: Update version with duplicate tag")
        void updateVersion__whenDuplicateTag__thenReturnConflict() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // Add a tag first
            var addTagRequest = DatasetVersionUpdate.builder()
                    .tagsToAdd(List.of("existing-tag"))
                    .build();
            datasetResourceClient.updateVersion(datasetId, versionHash, addTagRequest, API_KEY, TEST_WORKSPACE);

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
            var version = getLatestVersion(datasetId);
            String versionHash = version.versionHash();

            // When - Update with duplicate tags in the request
            var updateRequest = DatasetVersionUpdate.builder()
                    .tagsToAdd(List.of("new-tag", "another-tag", "new-tag", "another-tag"))
                    .build();

            var updatedVersion = datasetResourceClient.updateVersion(datasetId, versionHash, updateRequest, API_KEY,
                    TEST_WORKSPACE);

            // Then - Verify tags were deduplicated
            assertThat(updatedVersion.tags())
                    .containsExactlyInAnyOrder("new-tag", "another-tag", DatasetVersionService.LATEST_TAG);
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
        void putItems__whenItemsExist__thenCreateVersion() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When - Create items (this creates a version automatically with toggle ON)
            createDatasetItems(datasetId, 3);
            var version = getLatestVersion(datasetId);

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
            // Given - Create dataset with items (creates a version automatically)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);
            var version = getLatestVersion(datasetId);

            // Add a custom tag for testing
            var tag = DatasetVersionTag.builder().tag("baseline").build();
            datasetResourceClient.createVersionTag(datasetId, version.versionHash(), tag, API_KEY, TEST_WORKSPACE);

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
        @DisplayName("Success: Multiple PUT calls create multiple versions")
        void putItems__whenMultipleCalls__thenCreateMultipleVersions() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When - Create items multiple times (each creates a version)
            createDatasetItems(datasetId, 3);
            var version1 = getLatestVersion(datasetId);

            createDatasetItems(datasetId, 2);
            var version2 = getLatestVersion(datasetId);

            // Then - Verify two versions were created
            assertThat(version2.id()).isNotEqualTo(version1.id());

            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            // Version 2 has more items (built on top of version 1)
            assertThat(version2.itemsTotal()).isEqualTo(5); // 3 + 2
        }

        @Test
        @DisplayName("Success: UUID-based hash allows same content in different versions")
        void putItems__whenSameContent__thenGenerateUniqueHash() {
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

            // When - Get versions for both datasets
            var version1 = getLatestVersion(dataset1Id);
            var version2 = getLatestVersion(dataset2Id);

            // Then - Verify both have different hashes (UUID-based, not content-based)
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
        void putItems__whenSameItemInMultipleVersions__thenGenerateNewIdsPerVersion() {
            // Given - Create dataset with items (creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(2);

            var batch = DatasetItemBatch.builder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Add tag to first version
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items from version 1
            var v1ItemsPage = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE);
            var v1Items = v1ItemsPage.content();
            var v1ItemIds = v1Items.stream().map(DatasetItem::id).toList();
            var v1DraftItemIds = v1Items.stream().map(DatasetItem::draftItemId).toList();

            // When - Add more items (creates version 2 on top of version 1)
            createDatasetItems(datasetId, 1);

            // Add tag to version 2
            var version2 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version2.versionHash(),
                    DatasetVersionTag.builder().tag("v2").build(), API_KEY, TEST_WORKSPACE);

            // Get items from version 2
            var v2ItemsPage = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE);
            var v2Items = v2ItemsPage.content();
            var v2ItemIds = v2Items.stream().map(DatasetItem::id).toList();
            var v2DraftItemIds = v2Items.stream().map(DatasetItem::draftItemId).toList();

            // Then - Verify that:
            // 1. Each version has the expected number of items
            assertThat(v1Items).hasSize(2);
            assertThat(v2Items).hasSize(3); // 2 original + 1 new

            // 2. Each version snapshot gets unique IDs
            assertThat(v1ItemIds).doesNotContainAnyElementsOf(v2ItemIds)
                    .as("Version 1 and version 2 should have different item IDs (unique per snapshot)");

            // 3. The draftItemId field maintains the link between versions
            assertThat(v2DraftItemIds).containsAll(v1DraftItemIds)
                    .as("Version 2 should contain all draftItemIds from version 1 (plus new ones)");
        }
    }

    @Nested
    @DisplayName("Restore Dataset Version:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RestoreVersion {

        @Test
        @DisplayName("Success: Restore to previous version creates new version")
        void restoreVersion__whenNotLatest__thenCreateNewVersion() {
            // Given - Create dataset with 3 items (creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(3);

            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(originalItems)
                    .build();
            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // Tag version 1
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get v1 items to identify item to delete via applyDeltaChanges
            var v1Items = datasetResourceClient.getDatasetItems(datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE)
                    .content();
            var itemToDelete = v1Items.get(0);

            // Create version 2 by applying delta (deleting one item)
            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .deletedIds(Set.of(itemToDelete.draftItemId()))
                    .tags(List.of("v2"))
                    .build();
            var version2 = datasetResourceClient.applyDatasetItemChanges(datasetId, changes, false, API_KEY,
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
            createDatasetItems(datasetId, 2);

            // Tag as v1
            var version = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

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
            createDatasetItems(datasetId, 2);
            var version1 = getLatestVersion(datasetId);

            // Add more items (creates version 2)
            createDatasetItems(datasetId, 1);

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

    @Nested
    @DisplayName("Apply Dataset Item Changes:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApplyDatasetItemChanges {

        @Test
        @DisplayName("Success: Apply combined changes (add, edit, delete) creates new version")
        void applyChanges__whenCombinedAddEditDelete__thenCreateNewVersion() {
            // Given - Create dataset with initial items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(3);

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(originalItems)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get version 1 and tag it
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items from v1 to obtain their IDs for editing/deleting
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(3);

            var itemToEdit = v1Items.get(0);
            var itemToDelete = v1Items.get(1);
            var itemToKeep = v1Items.get(2);

            // Prepare changes: add 1 new item, edit 1 item, delete 1 item
            var newItem = generateDatasetItems(1).get(0);
            var editedItem = itemToEdit.toBuilder()
                    .data(Map.of("edited", JsonUtils.getJsonNodeFromString("true"),
                            "description", JsonUtils.getJsonNodeFromString("\"Modified item data\"")))
                    .source(DatasetItemSource.SDK)
                    .traceId(null)
                    .spanId(null)
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .editedItems(List.of(editedItem))
                    .deletedIds(Set.of(itemToDelete.draftItemId()))
                    .tags(List.of("v2"))
                    .changeDescription("Combined changes: add, edit, delete")
                    .build();

            // When - Apply changes
            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.tags()).contains("v2", DatasetVersionService.LATEST_TAG);
            assertThat(version2.changeDescription()).isEqualTo("Combined changes: add, edit, delete");
            assertThat(version2.itemsTotal()).isEqualTo(3); // 3 - 1 deleted + 1 added = 3
            assertThat(version2.itemsAdded()).isEqualTo(1);
            assertThat(version2.itemsModified()).isEqualTo(1);
            assertThat(version2.itemsDeleted()).isEqualTo(1);

            // Verify v2 items reflect changes
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(3);

            // Verify the edited item has new data
            var editedInV2 = v2Items.stream()
                    .filter(item -> item.draftItemId().equals(itemToEdit.draftItemId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Edited item not found in v2"));
            assertThat(editedInV2.data().get("edited")).isNotNull();
            assertThat(editedInV2.data().get("description")).isNotNull();

            // Verify the deleted item is not in v2
            var deletedInV2 = v2Items.stream()
                    .filter(item -> item.draftItemId().equals(itemToDelete.draftItemId()))
                    .findFirst();
            assertThat(deletedInV2).isEmpty();

            // Verify the kept item is still in v2
            var keptInV2 = v2Items.stream()
                    .filter(item -> item.draftItemId().equals(itemToKeep.draftItemId()))
                    .findFirst();
            assertThat(keptInV2).isPresent();

            // Verify v1 is still intact (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(3);
        }

        @Test
        @DisplayName("Error: Apply changes with stale baseVersion returns 409 Conflict")
        void applyChanges__whenBaseVersionIsStale__thenReturn409() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // Get version 1
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Add more items (auto-creates version 2, so v1 becomes stale)
            createDatasetItems(datasetId, 1);

            // When - Try to apply changes with stale baseVersion (v1 instead of v2)
            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id()) // Stale version
                    .addedItems(List.of(generateDatasetItems(1).get(0)))
                    .tags(List.of("v3"))
                    .changeDescription("Should fail - stale base version")
                    .build();

            try (var response = datasetResourceClient.callApplyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE)) {

                // Then - Should return 409 Conflict
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var error = response.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anyMatch(msg -> msg.toLowerCase().contains("base version")
                        || msg.toLowerCase().contains("conflict"));
            }
        }

        @Test
        @DisplayName("Success: Apply changes with stale baseVersion but override=true succeeds")
        void applyChanges__whenBaseVersionIsStaleButOverride__thenSucceed() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            // Get version 1
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Add more items (auto-creates version 2, so v1 becomes stale)
            createDatasetItems(datasetId, 1);

            // When - Apply changes with stale baseVersion but override=true
            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id()) // Stale version, but override=true
                    .addedItems(List.of(generateDatasetItems(1).get(0)))
                    .tags(List.of("v3-override"))
                    .changeDescription("Override stale base version")
                    .build();

            var version3 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, true, API_KEY, TEST_WORKSPACE);

            // Then - Should succeed with override
            assertThat(version3.id()).isNotEqualTo(version1.id());
            assertThat(version3.tags()).contains("v3-override", DatasetVersionService.LATEST_TAG);
            assertThat(version3.changeDescription()).isEqualTo("Override stale base version");
            // When overriding, changes are applied to the stale baseVersion (v1)
            // v1 had 2 items + 1 added = 3 items
            assertThat(version3.itemsTotal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Success: Apply changes with matching baseVersion succeeds")
        void applyChanges__whenBaseVersionMatchesLatest__thenSucceed() {
            // Given - Create dataset with items (auto-creates version)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            // Get version and tag it
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get v1 items for editing
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();

            // When - Apply changes with current baseVersion (which is the latest)
            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id()) // Current latest version
                    .editedItems(List.of(v1Items.get(0).toBuilder()
                            .data(Map.of("updated", JsonUtils.getJsonNodeFromString("true")))
                            .source(DatasetItemSource.SDK) // Required field
                            .traceId(null)
                            .spanId(null)
                            .build()))
                    .tags(List.of("v2"))
                    .changeDescription("Update with matching base version")
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            // Then - Should succeed
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.tags()).contains("v2", DatasetVersionService.LATEST_TAG);
            assertThat(version2.itemsModified()).isEqualTo(1);
            assertThat(version2.itemsTotal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Error: Apply changes to non-existent dataset returns 404")
        void applyChanges__whenDatasetNotFound__thenReturn404() {
            // Given - Non-existent dataset ID
            var nonExistentDatasetId = UUID.randomUUID();
            var someVersionId = UUID.randomUUID();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(someVersionId)
                    .addedItems(List.of(generateDatasetItems(1).get(0)))
                    .build();

            // When
            try (var response = datasetResourceClient.callApplyDatasetItemChanges(
                    nonExistentDatasetId, changes, false, API_KEY, TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("Error: Apply changes with non-existent baseVersion returns 404")
        void applyChanges__whenBaseVersionNotFound__thenReturn404() {
            // Given - Create dataset (auto-creates version)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var nonExistentVersionId = UUID.randomUUID();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(nonExistentVersionId) // Non-existent version
                    .addedItems(List.of(generateDatasetItems(1).get(0)))
                    .build();

            // When
            try (var response = datasetResourceClient.callApplyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("Delete Items With Versioning:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteItemsWithVersioning {

        @Test
        @DisplayName("Success: Delete items creates new version without deleted items")
        void deleteItems__whenVersioningEnabled__thenCreateNewVersionWithoutDeletedItems() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 5);

            // Get version 1 and tag it
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items from v1 to identify item to delete
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(5);

            var itemToDelete = v1Items.get(0);

            // When - Delete one item
            deleteDatasetItem(datasetId, itemToDelete.draftItemId());

            // Then - Verify a new version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            // Verify the new version has 4 items (5 - 1 deleted)
            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(4);
            assertThat(version2.itemsDeleted()).isEqualTo(1);
            assertThat(version2.itemsAdded()).isEqualTo(0);
            assertThat(version2.itemsModified()).isEqualTo(0);

            // Verify v1 still has 5 items (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(5);

            // Verify v2 (latest) has 4 items and doesn't contain the deleted item
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(4);
            assertThat(v2Items.stream().map(DatasetItem::draftItemId))
                    .doesNotContain(itemToDelete.draftItemId());
        }

        @Test
        @DisplayName("Success: Delete multiple items creates new version")
        void deleteItems__whenMultipleItemsDeleted__thenCreateNewVersionWithoutDeletedItems() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 5);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items to delete
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            var itemsToDelete = v1Items.subList(0, 3); // Delete first 3 items

            // When - Delete multiple items one by one (each creates a version)
            for (var item : itemsToDelete) {
                deleteDatasetItem(datasetId, item.draftItemId());
            }

            // Then - Verify versions were created (1 original + 3 deletions = 4 versions)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(4);

            // Verify the latest version has 2 items (5 - 3 deleted)
            var latestVersion = getLatestVersion(datasetId);
            assertThat(latestVersion.itemsTotal()).isEqualTo(2);

            // Verify latest items don't contain deleted items
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(2);

            var deletedDraftItemIds = itemsToDelete.stream().map(DatasetItem::draftItemId).toList();
            assertThat(latestItems.stream().map(DatasetItem::draftItemId))
                    .doesNotContainAnyElementsOf(deletedDraftItemIds);
        }

        @Test
        @DisplayName("Success: Delete all items leaves empty version")
        void deleteItems__whenAllItemsDeleted__thenCreateEmptyVersion() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var version1 = getLatestVersion(datasetId);
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();

            // When - Delete all items
            for (var item : v1Items) {
                deleteDatasetItem(datasetId, item.draftItemId());
            }

            // Then - Verify latest version has 0 items
            var latestVersion = getLatestVersion(datasetId);
            assertThat(latestVersion.itemsTotal()).isEqualTo(0);

            // Verify v1 still has 2 items (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(2);

            // Verify latest has 0 items
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).isEmpty();
        }

        @Test
        @DisplayName("Success: Delete by filters creates new version without matching items")
        void deleteItems__whenDeleteByFilters__thenCreateNewVersionWithoutMatchingItems() {
            // Given - Create dataset with items that have specific data values
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create items with different data values to enable filtering
            var item1 = DatasetItem.builder()
                    .id(UUID.randomUUID())
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of("category", factory.manufacturePojo(JsonNode.class),
                            "status", factory.manufacturePojo(JsonNode.class)))
                    .build();
            var item2 = DatasetItem.builder()
                    .id(UUID.randomUUID())
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of("category", factory.manufacturePojo(JsonNode.class),
                            "status", factory.manufacturePojo(JsonNode.class)))
                    .build();
            var item3 = DatasetItem.builder()
                    .id(UUID.randomUUID())
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of("category", factory.manufacturePojo(JsonNode.class),
                            "status", factory.manufacturePojo(JsonNode.class)))
                    .build();

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(List.of(item1, item2, item3))
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // Tag v1 so we can reference it later
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // When - Delete all items using empty filters (delete all in dataset)
            deleteDatasetItemsByFilters(datasetId, List.of());

            // Then - Verify a new version was created with 0 items
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(0);
            assertThat(version2.itemsDeleted()).isEqualTo(3);

            // Verify v1 still has 3 items (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(3);
        }

        @Test
        @DisplayName("Success: Combined add and delete operations maintain correct state")
        void addAndDelete__whenMixedOperations__thenVersionsReflectChangesCorrectly() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get an item to delete
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            var itemToDelete = v1Items.get(0);

            // When - Delete one item (creates version 2 with 2 items)
            deleteDatasetItem(datasetId, itemToDelete.draftItemId());

            // Verify version 2 has 2 items
            var version2 = getLatestVersion(datasetId);
            assertThat(version2.itemsTotal()).isEqualTo(2);

            // Then - Add new items (creates version 3)
            createDatasetItems(datasetId, 2);

            // Verify version 3 has 4 items (2 + 2 added)
            var version3 = getLatestVersion(datasetId);
            assertThat(version3.itemsTotal()).isEqualTo(4);

            // Verify v1 still has 3 items (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(3);

            // Verify v2 still has 2 items (immutable)
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version2.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(2);

            // Verify latest has 4 items
            var v3Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(v3Items).hasSize(4);
        }
    }

    @Nested
    @DisplayName("Patch Items With Versioning:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PatchItemsWithVersioning {

        @Test
        @DisplayName("Success: Patch single item creates new version with edit")
        void patchItem__whenVersioningEnabled__thenCreateNewVersionWithEdit() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // Tag v1 for later reference
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get first item to patch
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            var itemToPatch = v1Items.get(0);

            // When - Patch the item with new data
            var newData = Map.of("patched", factory.manufacturePojo(JsonNode.class));
            var patchItem = DatasetItem.builder()
                    .data(newData)
                    .build();
            datasetResourceClient.patchDatasetItem(itemToPatch.draftItemId(), patchItem, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(3); // Same count, just edited
            assertThat(version2.itemsModified()).isEqualTo(1);
            assertThat(version2.itemsAdded()).isEqualTo(0);
            assertThat(version2.itemsDeleted()).isEqualTo(0);

            // Verify v1 still has original data (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(3);
            var originalItem = v1ItemsAfter.stream()
                    .filter(i -> i.draftItemId().equals(itemToPatch.draftItemId()))
                    .findFirst().orElseThrow();
            assertThat(originalItem.data()).isEqualTo(itemToPatch.data());

            // Verify latest version has patched data
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(3);
            var patchedItem = latestItems.stream()
                    .filter(i -> i.draftItemId().equals(itemToPatch.draftItemId()))
                    .findFirst().orElseThrow();
            assertThat(patchedItem.data()).isEqualTo(newData);
        }

        @Test
        @DisplayName("Success: Multiple patches create multiple versions")
        void patchItem__whenMultiplePatches__thenCreateMultipleVersions() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var version1 = getLatestVersion(datasetId);
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            var item = v1Items.get(0);

            // When - Patch the same item multiple times
            var patchData1 = Map.of("version", factory.manufacturePojo(JsonNode.class));
            datasetResourceClient.patchDatasetItem(item.draftItemId(),
                    DatasetItem.builder().data(patchData1).build(), API_KEY, TEST_WORKSPACE);

            var patchData2 = Map.of("version", factory.manufacturePojo(JsonNode.class));
            datasetResourceClient.patchDatasetItem(item.draftItemId(),
                    DatasetItem.builder().data(patchData2).build(), API_KEY, TEST_WORKSPACE);

            var patchData3 = Map.of("version", factory.manufacturePojo(JsonNode.class));
            datasetResourceClient.patchDatasetItem(item.draftItemId(),
                    DatasetItem.builder().data(patchData3).build(), API_KEY, TEST_WORKSPACE);

            // Then - Verify 4 versions exist (initial + 3 patches)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(4);

            // Verify latest has the most recent patch data
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            var latestItem = latestItems.stream()
                    .filter(i -> i.draftItemId().equals(item.draftItemId()))
                    .findFirst().orElseThrow();
            assertThat(latestItem.data()).isEqualTo(patchData3);
        }

        @Test
        @DisplayName("Success: Batch update creates new version with edits")
        void batchUpdate__whenVersioningEnabled__thenCreateNewVersionWithEdits() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 5);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(5);

            // Tag v1 for later reference
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items to update
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();

            // Select 3 items to batch update
            var itemsToUpdate = Set.of(
                    v1Items.get(0).draftItemId(),
                    v1Items.get(1).draftItemId(),
                    v1Items.get(2).draftItemId());

            // When - Batch update with new tags
            var newTags = Set.of("batch-updated", "test-tag");
            var batchUpdate = DatasetItemBatchUpdate.builder()
                    .ids(itemsToUpdate)
                    .update(DatasetItemUpdate.builder().tags(newTags).build())
                    .build();
            datasetResourceClient.batchUpdateDatasetItems(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(5);
            // Note: itemsModified is calculated by comparing data hashes.
            // Since we only updated tags (not data), the hash stays the same,
            // so items appear as "unchanged" to the diff calculation.
            // This test verifies that the version was created and tags were applied correctly.

            // Verify v1 items don't have the new tags (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            for (var item : v1ItemsAfter) {
                if (item.tags() != null) {
                    assertThat(item.tags()).doesNotContain("batch-updated");
                }
            }

            // Verify latest version has updated tags on the 3 items
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            int updatedCount = 0;
            for (var item : latestItems) {
                if (itemsToUpdate.contains(item.draftItemId())) {
                    assertThat(item.tags()).containsAll(newTags);
                    updatedCount++;
                }
            }
            assertThat(updatedCount).isEqualTo(3);
        }

        @Test
        @DisplayName("Success: Patch then delete maintains correct state")
        void patchThenDelete__whenMixedOperations__thenVersionsReflectChangesCorrectly() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            var itemToPatch = v1Items.get(0);
            var itemToDelete = v1Items.get(1);

            // When - First patch an item
            var patchData = Map.of("patched", factory.manufacturePojo(JsonNode.class));
            datasetResourceClient.patchDatasetItem(itemToPatch.draftItemId(),
                    DatasetItem.builder().data(patchData).build(), API_KEY, TEST_WORKSPACE);

            var version2 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version2.versionHash(),
                    DatasetVersionTag.builder().tag("v2").build(), API_KEY, TEST_WORKSPACE);

            // Then delete another item
            deleteDatasetItem(datasetId, itemToDelete.draftItemId());

            // Then - Verify 3 versions exist
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(3);

            var version3 = getLatestVersion(datasetId);
            assertThat(version3.itemsTotal()).isEqualTo(2); // 3 - 1 deleted
            assertThat(version3.itemsDeleted()).isEqualTo(1);

            // v1 still has 3 items
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(3);

            // v2 has 3 items with the patch applied
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(3);

            // latest has 2 items (patch + delete applied)
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(2);

            // Verify the patched item still has the patched data in latest
            var patchedItemInLatest = latestItems.stream()
                    .filter(i -> i.draftItemId().equals(itemToPatch.draftItemId()))
                    .findFirst().orElseThrow();
            assertThat(patchedItemInLatest.data()).isEqualTo(patchData);
        }
    }
}
