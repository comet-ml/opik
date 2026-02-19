package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Column;
import com.comet.opik.api.CreateDatasetItemsFromSpansRequest;
import com.comet.opik.api.CreateDatasetItemsFromTracesRequest;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemEdit;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemUpdate;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.SpanEnrichmentOptions;
import com.comet.opik.domain.TraceEnrichmentOptions;
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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.api.resources.v1.priv.DatasetsResourceTest.IGNORED_FIELDS_DATA_ITEM;
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
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.datasetVersioningEnabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        datasetResourceClient = new DatasetResourceClient(client, baseURI);
        experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        traceResourceClient = new TraceResourceClient(client, baseURI);
        spanResourceClient = new SpanResourceClient(client, baseURI);
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
                            .source(DatasetItemSource.MANUAL) // Explicitly set source
                            .traceId(null) // MANUAL source must have null traceId
                            .spanId(null) // MANUAL source must have null spanId
                            .data(data)
                            .build();
                })
                .toList();

        var batch = DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(itemsList)
                .batchGroupId(UUID.randomUUID()) // Unique batch_group_id to create new version
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
     * Creates dataset items WITHOUT batch_group_id (simulates old SDK behavior).
     * This will mutate the latest version instead of creating a new one.
     */
    private void createDatasetItemsWithoutBatchGroupId(UUID datasetId, int count) {
        List<DatasetItem> itemsList = IntStream.range(0, count)
                .mapToObj(i -> {
                    DatasetItem item = factory.manufacturePojo(DatasetItem.class);
                    Map<String, JsonNode> data = Map.of(
                            "input", JsonUtils.getJsonNodeFromString("\"test input " + i + "\""),
                            "output", JsonUtils.getJsonNodeFromString("\"test output " + i + "\""));
                    return item.toBuilder()
                            .id(null)
                            .source(DatasetItemSource.MANUAL)
                            .traceId(null)
                            .spanId(null)
                            .data(data)
                            .build();
                })
                .toList();

        var batch = DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(itemsList)
                // NO batch_group_id - simulates old SDK
                .build();

        datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);
    }

    /**
     * Gets the latest version for a dataset.
     */
    private DatasetVersion getLatestVersion(UUID datasetId) {
        var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
        assertThat(versions.content()).isNotEmpty();
        return versions.content().getFirst();
    }

    private void deleteDatasetItem(UUID datasetId, UUID itemId) {
        // Create a delete request with a unique batchGroupId to create a new version
        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(itemId))
                .batchGroupId(UUID.randomUUID())
                .build();
        datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);
    }

    private void deleteDatasetItemsByFilters(UUID datasetId, List<DatasetItemFilter> filters) {
        datasetResourceClient.deleteDatasetItemsByFilters(datasetId, filters, API_KEY, TEST_WORKSPACE);
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
    @DisplayName("Retrieve Version by Name:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetrieveVersion {

        @Test
        @DisplayName("Success: Retrieve version by name")
        void retrieveVersion__whenValidVersionName__thenReturnVersion() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            final int VERSION_COUNT = 3;

            // Create multiple versions
            for (int i = 1; i <= VERSION_COUNT; i++) {
                createDatasetItems(datasetId, 1);
            }

            // When - Retrieve v1 (first version)
            var version = datasetResourceClient.retrieveVersion(datasetId, "v1", API_KEY, TEST_WORKSPACE);

            // Then
            assertThat(version).isNotNull();
            assertThat(version.versionName()).isEqualTo("v1");
            assertThat(version.datasetId()).isEqualTo(datasetId);
        }

        @Test
        @DisplayName("Success: Retrieve latest version by name")
        void retrieveVersion__whenLatestVersionName__thenReturnLatestVersion() {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            final int VERSION_COUNT = 3;

            // Create multiple versions
            for (int i = 1; i <= VERSION_COUNT; i++) {
                createDatasetItems(datasetId, 1);
            }

            // When - Retrieve v3 (should be latest)
            var version = datasetResourceClient.retrieveVersion(datasetId, "v3", API_KEY, TEST_WORKSPACE);

            // Then
            assertThat(version).isNotNull();
            assertThat(version.versionName()).isEqualTo("v3");
            assertThat(version.isLatest()).isTrue();
        }

        static Stream<Arguments> invalidVersionScenarios() {
            return Stream.of(
                    Arguments.of("v999", HttpStatus.SC_NOT_FOUND, "non-existent version"),
                    Arguments.of("invalid", HttpStatus.SC_UNPROCESSABLE_ENTITY, "invalid format"),
                    Arguments.of("v", HttpStatus.SC_UNPROCESSABLE_ENTITY, "missing version number"),
                    Arguments.of("1", HttpStatus.SC_UNPROCESSABLE_ENTITY, "missing 'v' prefix"));
        }

        @ParameterizedTest(name = "Error: {2}")
        @MethodSource("invalidVersionScenarios")
        void retrieveVersion__whenInvalidInput__thenReturnExpectedError(
                String versionName, int expectedStatus, String scenario) {
            // Given
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 1);

            // When
            try (var response = datasetResourceClient.callRetrieveVersion(datasetId, versionName, API_KEY,
                    TEST_WORKSPACE)) {
                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
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
        @DisplayName("Success: Update change_description and add tags")
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
        @DisplayName("Success: Version name is auto-incremented and formatted as 'v1', 'v2', etc.")
        void putItems__whenMultipleVersions__thenVersionNameAutoIncremented() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When - Create multiple versions
            createDatasetItems(datasetId, 2);
            var version1 = getLatestVersion(datasetId);

            createDatasetItems(datasetId, 1);
            var version2 = getLatestVersion(datasetId);

            createDatasetItems(datasetId, 1);
            var version3 = getLatestVersion(datasetId);

            // Then - Verify version names are correctly formatted
            assertThat(version1.versionName()).isEqualTo("v1");
            assertThat(version2.versionName()).isEqualTo("v2");
            assertThat(version3.versionName()).isEqualTo("v3");

            // Verify all versions in list have correct names
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(3);

            // Versions are ordered by creation time DESC (v3, v2, v1)
            assertThat(versions.content().get(0).versionName()).isEqualTo("v3");
            assertThat(versions.content().get(1).versionName()).isEqualTo("v2");
            assertThat(versions.content().get(2).versionName()).isEqualTo("v1");
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
                            .batchGroupId(UUID.randomUUID())
                            .build(),
                    TEST_WORKSPACE,
                    API_KEY);

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder()
                            .datasetId(dataset2Id)
                            .items(List.of(item))
                            .batchGroupId(UUID.randomUUID())
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
                    .batchGroupId(UUID.randomUUID())
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
            var v1DatasetItemIds = v1Items.stream().map(DatasetItem::datasetItemId).toList();

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
            var v2DatasetItemIds = v2Items.stream().map(DatasetItem::datasetItemId).toList();

            // Then - Verify that:
            // 1. Each version has the expected number of items
            assertThat(v1Items).hasSize(2);
            assertThat(v2Items).hasSize(3); // 2 original + 1 new

            // 2. Each version snapshot gets unique IDs
            assertThat(v1ItemIds).doesNotContainAnyElementsOf(v2ItemIds)
                    .as("Version 1 and version 2 should have different item IDs (unique per snapshot)");

            // 3. The datasetItemId field maintains the link between versions
            assertThat(v2DatasetItemIds).containsAll(v1DatasetItemIds)
                    .as("Version 2 should contain all datasetItemIds from version 1 (plus new ones)");
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
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // Tag version 1
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get v1 items to identify item to delete via applyDeltaChanges
            var v1Items = datasetResourceClient.getDatasetItems(datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE)
                    .content();
            var itemToDelete = v1Items.getFirst();

            // Create version 2 by applying delta (deleting one item)
            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .deletedIds(Set.of(itemToDelete.id()))
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
                    .batchGroupId(UUID.randomUUID())
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
            var newItem = generateDatasetItems(1).getFirst();
            var editedItem = DatasetItemEdit.builder()
                    .id(itemToEdit.id()) // Row ID from API response
                    .data(Map.of("edited", JsonUtils.getJsonNodeFromString("true"),
                            "description", JsonUtils.getJsonNodeFromString("\"Modified item data\"")))
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .editedItems(List.of(editedItem))
                    .deletedIds(Set.of(itemToDelete.id()))
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
                    .filter(item -> item.datasetItemId().equals(itemToEdit.datasetItemId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Edited item not found in v2"));
            assertThat(editedInV2.data().get("edited")).isNotNull();
            assertThat(editedInV2.data().get("description")).isNotNull();

            // Verify the deleted item is not in v2
            var deletedInV2 = v2Items.stream()
                    .filter(item -> item.datasetItemId().equals(itemToDelete.datasetItemId()))
                    .findFirst();
            assertThat(deletedInV2).isEmpty();

            // Verify the kept item is still in v2
            var keptInV2 = v2Items.stream()
                    .filter(item -> item.datasetItemId().equals(itemToKeep.datasetItemId()))
                    .findFirst();
            assertThat(keptInV2).isPresent();

            // Verify v1 is still intact (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(3);
            assertThat(v1ItemsAfter)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                    .isEqualTo(v1Items);
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
                    .addedItems(List.of(generateDatasetItems(1).getFirst()))
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
                    .addedItems(List.of(generateDatasetItems(1).getFirst()))
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
        @DisplayName("Error: Apply changes to non-existent dataset returns 404")
        void applyChanges__whenDatasetNotFound__thenReturn404() {
            // Given - Non-existent dataset ID
            var nonExistentDatasetId = UUID.randomUUID();
            var someVersionId = UUID.randomUUID();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(someVersionId)
                    .addedItems(List.of(generateDatasetItems(1).getFirst()))
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
                    .addedItems(List.of(generateDatasetItems(1).getFirst()))
                    .build();

            // When
            try (var response = datasetResourceClient.callApplyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE)) {

                // Then
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("Success: Added and edited items appear before unchanged items in ordering")
        void applyChanges__whenAddingAndEditing__thenNewItemsAppearFirst() {
            // Given - Create dataset with 3 original items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            var originalItems = generateDatasetItems(3);

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(originalItems)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get version 1 and tag it
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items from v1 to obtain their IDs for editing
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(3);

            var itemToEdit = v1Items.get(0); // Will be edited
            var unchangedItem1 = v1Items.get(1); // Will remain unchanged
            var unchangedItem2 = v1Items.get(2); // Will remain unchanged

            // Prepare changes: add 1 new item, edit 1 item
            var newItem = generateDatasetItems(1).getFirst();

            var editedItem = DatasetItemEdit.builder()
                    .id(itemToEdit.id()) // Row ID from API response
                    .data(Map.of("edited", JsonUtils.getJsonNodeFromString("true"),
                            "description", JsonUtils.getJsonNodeFromString("\"Modified item data\"")))
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .editedItems(List.of(editedItem))
                    .tags(List.of("v2"))
                    .changeDescription("Add and edit items - testing ordering")
                    .build();

            // When - Apply changes
            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created with correct item count
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(4); // 3 original + 1 added
            assertThat(version2.itemsAdded()).isEqualTo(1);
            assertThat(version2.itemsModified()).isEqualTo(1);

            // Verify v2 items ordering: added and edited items should appear before unchanged items
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(4);

            // Extract actual ordering of datasetItemIds from v2
            var actualOrder = v2Items.stream()
                    .map(DatasetItem::datasetItemId)
                    .toList();

            // Find the newly added item in v2 (it won't be in v1)
            var v1ItemIds = v1Items.stream()
                    .map(DatasetItem::datasetItemId)
                    .toList();

            var addedItemId = v2Items.stream()
                    .filter(item -> !v1ItemIds.contains(item.datasetItemId()))
                    .map(DatasetItem::datasetItemId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Added item not found in v2"));

            // Expected order: added item first, edited item second, then unchanged items in their original order
            // Unchanged items should maintain their order from v1
            var expectedOrder = List.of(
                    addedItemId,
                    itemToEdit.datasetItemId(),
                    unchangedItem1.datasetItemId(),
                    unchangedItem2.datasetItemId());

            // Verify ordering matches expected
            assertThat(actualOrder)
                    .as("Items should be ordered: added, edited, then unchanged in their original v1 order")
                    .isEqualTo(expectedOrder);

            // Verify the edited item has the new data
            var editedInV2 = v2Items.stream()
                    .filter(item -> item.datasetItemId().equals(itemToEdit.datasetItemId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Edited item not found in v2"));
            assertThat(editedInV2.data().get("edited")).isNotNull();
            assertThat(editedInV2.data().get("description")).isNotNull();
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

            var itemToDelete = v1Items.getFirst();

            // When - Delete one item
            deleteDatasetItem(datasetId, itemToDelete.id());

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
            assertThat(v2Items.stream().map(DatasetItem::datasetItemId))
                    .doesNotContain(itemToDelete.datasetItemId());
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
                deleteDatasetItem(datasetId, item.id());
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
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of("category", factory.manufacturePojo(JsonNode.class),
                            "status", factory.manufacturePojo(JsonNode.class)))
                    .build();
            var item2 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of("category", factory.manufacturePojo(JsonNode.class),
                            "status", factory.manufacturePojo(JsonNode.class)))
                    .build();
            var item3 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of("category", factory.manufacturePojo(JsonNode.class),
                            "status", factory.manufacturePojo(JsonNode.class)))
                    .build();

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(List.of(item1, item2, item3))
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // Tag v1 so we can reference it later
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // When - Delete all items using empty filters with batchGroupId (creates new version)
            var deleteRequest = DatasetItemsDelete.builder()
                    .datasetId(datasetId)
                    .filters(List.of())
                    .batchGroupId(UUID.randomUUID()) // Provide batchGroupId to create new version
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

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
    }

    @Nested
    @DisplayName("Mutate Latest Version (no batch_group_id):")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MutateLatestVersion {

        @Test
        @DisplayName("Success: Insert items without batch_group_id mutates latest version")
        void insertItems__whenNoBatchGroupId__thenMutateLatestVersion() {
            // Given - Create dataset with initial items (creates v1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.versionName()).isEqualTo("v1");
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // When - Insert more items without batch_group_id (mutates latest version)
            var newItems = generateDatasetItems(2);
            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(newItems)
                    // No batchGroupId - mutates latest version
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Then - Verify no new version was created, v1 was mutated
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            var latestVersion = getLatestVersion(datasetId);
            assertThat(latestVersion.id()).isEqualTo(version1.id()); // Same version ID
            assertThat(latestVersion.versionName()).isEqualTo("v1");
            assertThat(latestVersion.itemsTotal()).isEqualTo(5); // 3 + 2 = 5

            // Verify all 5 items are present in the latest version
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, latestVersion.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(5);
        }

        @Test
        @DisplayName("Success: Delete items without batch_group_id mutates latest version")
        void deleteItems__whenNoBatchGroupId__thenMutateLatestVersion() {
            // Given - Create dataset with items (creates v1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 5);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.versionName()).isEqualTo("v1");
            assertThat(version1.itemsTotal()).isEqualTo(5);

            // Get items to identify one to delete
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(5);
            var itemToDelete = v1Items.getFirst();

            // When - Delete item without batch_group_id (mutates latest version)
            var deleteRequest = DatasetItemsDelete.builder()
                    .itemIds(Set.of(itemToDelete.datasetItemId()))
                    // No batchGroupId - mutates latest version
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

            // Then - Verify no new version was created, v1 was mutated
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            var latestVersion = getLatestVersion(datasetId);
            assertThat(latestVersion.id()).isEqualTo(version1.id()); // Same version ID
            assertThat(latestVersion.versionName()).isEqualTo("v1");
            assertThat(latestVersion.itemsTotal()).isEqualTo(4); // 5 - 1 = 4

            // Verify only 4 items remain in the latest version
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, latestVersion.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1ItemsAfter).hasSize(4);
            assertThat(v1ItemsAfter.stream().map(DatasetItem::datasetItemId))
                    .doesNotContain(itemToDelete.datasetItemId());
        }

        @Test
        @DisplayName("Success: Multiple inserts without batch_group_id accumulate in same version")
        void insertItems__whenMultipleInsertsWithoutBatchGroupId__thenAccumulateInSameVersion() {
            // Given - Create dataset with initial items (creates v1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 2);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(2);

            // When - Insert more items twice without batch_group_id (mutates latest version)
            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(generateDatasetItems(3))
                    // No batchGroupId
                    .build();
            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            var batch2 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(generateDatasetItems(2))
                    // No batchGroupId
                    .build();
            datasetResourceClient.createDatasetItems(batch2, TEST_WORKSPACE, API_KEY);

            // Then - Verify still only 1 version with all items
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            var latestVersion = getLatestVersion(datasetId);
            assertThat(latestVersion.id()).isEqualTo(version1.id());
            assertThat(latestVersion.itemsTotal()).isEqualTo(7); // 2 + 3 + 2 = 7
        }

        @Test
        @DisplayName("Success: Insert without batch_group_id on empty dataset creates first version")
        void insertItems__whenNoBatchGroupIdOnEmptyDataset__thenCreateFirstVersion() {
            // Given - Create empty dataset (no items yet)
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When - Insert items without batch_group_id
            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(generateDatasetItems(3))
                    // No batchGroupId
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Then - Verify first version was created (no version to mutate, so create one)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.versionName()).isEqualTo("v1");
            assertThat(version1.itemsTotal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Success: Delete without batch_group_id on empty dataset does nothing")
        void deleteItems__whenNoBatchGroupIdOnEmptyDataset__thenDoNothing() {
            // Given - Create empty dataset (no items yet)
            var datasetId = createDataset(UUID.randomUUID().toString());

            // When - Delete items without batch_group_id (no items to delete)
            var deleteRequest = DatasetItemsDelete.builder()
                    .itemIds(Set.of(UUID.randomUUID()))
                    // No batchGroupId
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

            // Then - Verify no version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).isEmpty();
        }

        @Test
        @DisplayName("Success: Update items without batch_group_id mutates latest version (no duplicates)")
        void insertItems__whenUpdatingSameItemsWithoutBatchGroupId__thenMutateLatestVersionNoDuplicates() {
            // Given - Create dataset with initial items (creates v1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(5);

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    // No batchGroupId - mutates latest version
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(5);

            // Get the items to preserve their IDs
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(5);

            // When - Update the same items with new data (same IDs, different data)
            var updatedItems = v1Items.stream()
                    .map(item -> DatasetItem.builder()
                            .id(item.id()) // Preserve the ID
                            .datasetItemId(item.datasetItemId()) // Preserve stable ID
                            .source(item.source())
                            .data(Map.of("updated", JsonUtils.getJsonNodeFromString("true"),
                                    "newField", JsonUtils.getJsonNodeFromString("\"new value\"")))
                            .build())
                    .toList();

            var updatedBatch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(updatedItems)
                    // No batchGroupId - mutates latest version
                    .build();
            datasetResourceClient.createDatasetItems(updatedBatch, TEST_WORKSPACE, API_KEY);

            // Then - Verify still only 1 version (mutated, not new version)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            var latestVersion = getLatestVersion(datasetId);
            assertThat(latestVersion.id()).isEqualTo(version1.id()); // Same version ID
            assertThat(latestVersion.itemsTotal()).isEqualTo(5); // Still 5 items (no duplicates)

            // Verify the items have the updated data
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, latestVersion.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(5); // No duplicates

            // Verify all items have the updated data
            for (var item : latestItems) {
                assertThat(item.data()).containsKey("updated");
                assertThat(item.data()).containsKey("newField");
            }
        }
    }

    @Nested
    @DisplayName("Get Items Response Structure:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetItemsResponseStructure {

        @Test
        @DisplayName("Success: GET items response includes columns metadata for versioned items")
        void getItems__whenVersioningEnabled__thenIncludesColumnsMetadata() {
            // Given - Create dataset with items that have specific data fields
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create items with known data fields to test columns extraction
            var item1 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of(
                            "input", JsonUtils.readTree("{\"query\": \"test query\"}"),
                            "output", JsonUtils.readTree("{\"response\": \"test response\"}"),
                            "score", JsonUtils.readTree("0.95")))
                    .build();
            var item2 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of(
                            "input", JsonUtils.readTree("{\"query\": \"another query\"}"),
                            "tags", JsonUtils.readTree("[\"tag1\", \"tag2\"]")))
                    .build();

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(List.of(item1, item2))
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // When - Get items from the latest version
            var itemPage = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE);

            // Then - Verify columns are populated (not empty)
            assertThat(itemPage.columns()).isNotNull();
            assertThat(itemPage.columns()).isNotEmpty();

            // Verify expected column names are present
            var columnNames = itemPage.columns().stream()
                    .map(col -> col.name())
                    .toList();
            assertThat(columnNames).contains("input", "output");
        }

        @Test
        @DisplayName("Success: GET single item by row ID (id field) works with versioning")
        void getItemById__whenUsingRowIdFromApiResponse__thenReturnsItem() {
            // Given - Create dataset with items (auto-creates version 1)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            // Get items from the latest version
            var items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(items).hasSize(3);

            var itemFromList = items.getFirst();
            var rowId = itemFromList.id();

            // When - Get single item by row ID (what the frontend does)
            var fetchedItem = datasetResourceClient.getDatasetItem(rowId, API_KEY, TEST_WORKSPACE);

            // Then - Verify item is returned correctly
            assertThat(fetchedItem).isNotNull();
            assertThat(fetchedItem.id()).isEqualTo(rowId);
            assertThat(fetchedItem.datasetItemId()).isEqualTo(itemFromList.datasetItemId());
            assertThat(fetchedItem.datasetId()).isEqualTo(datasetId);
        }

        @Test
        @DisplayName("Success: GET datasets list shows correct item count from latest version")
        void getDatasets__whenVersioningEnabled__thenItemsCountFromLatestVersion() {
            // Given - Create dataset with initial items (creates version 1 with 3 items)
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);
            createDatasetItems(datasetId, 3);

            // Verify initial count
            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // Get the dataset from list API and verify items count
            var datasetsPage = datasetResourceClient.getDatasets(TEST_WORKSPACE, API_KEY);
            var dataset = datasetsPage.content().stream()
                    .filter(d -> d.id().equals(datasetId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Dataset not found in list"));
            assertThat(dataset.datasetItemsCount()).isEqualTo(3L);
            assertThat(dataset.latestVersion()).isNotNull();
            assertThat(dataset.latestVersion().versionName()).isEqualTo("v1");

            // Now delete an item via applyDeltaChanges (creates version 2 with 2 items)
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            var itemToDelete = v1Items.getFirst();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .deletedIds(Set.of(itemToDelete.id()))
                    .changeDescription("Delete one item")
                    .build();
            datasetResourceClient.applyDatasetItemChanges(datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            // When - Get datasets list again
            var datasetsPageAfter = datasetResourceClient.getDatasets(TEST_WORKSPACE, API_KEY);

            // Then - Dataset should show 2 items (from latest version, not legacy table)
            var datasetAfter = datasetsPageAfter.content().stream()
                    .filter(d -> d.id().equals(datasetId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Dataset not found in list after update"));
            assertThat(datasetAfter.datasetItemsCount()).isEqualTo(2L);
            assertThat(datasetAfter.latestVersion()).isNotNull();
            assertThat(datasetAfter.latestVersion().versionName()).isEqualTo("v2");

            // Verify latest version has 2 items
            var version2 = getLatestVersion(datasetId);
            assertThat(version2.itemsTotal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Success: Filter versioned dataset items by data field")
        void getItems__whenFilteringVersionedItems__thenReturnMatchingItems() {
            // Given - Create dataset with items that have specific data fields
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create items with different descriptions
            var item1 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of(
                            "Name", JsonUtils.readTree("\"Cat\""),
                            "Description", JsonUtils.readTree("\"Cat looking at camera\"")))
                    .build();
            var item2 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of(
                            "Name", JsonUtils.readTree("\"Dog\""),
                            "Description", JsonUtils.readTree("\"Dog at the garden\"")))
                    .build();
            var item3 = DatasetItem.builder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .data(Map.of(
                            "Name", JsonUtils.readTree("\"Bird\""),
                            "Description", JsonUtils.readTree("\"Blue jay perched on a wooden fence\"")))
                    .build();

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(List.of(item1, item2, item3))
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Ensure version is created
            getLatestVersion(datasetId);

            // When - Filter by Description field that does NOT contain "Dog"
            var filter = new DatasetItemFilter(DatasetItemField.DATA, Operator.NOT_CONTAINS, "Description", "Dog");
            var filteredItems = datasetResourceClient.getDatasetItems(
                    datasetId,
                    Map.of("filters", TestUtils.toURLEncodedQueryParam(List.of(filter))),
                    API_KEY,
                    TEST_WORKSPACE);

            // Then - Should return only items without "Dog" in Description (Cat and Bird)
            assertThat(filteredItems.content()).hasSize(2);
            assertThat(filteredItems.total()).isEqualTo(2);

            // Verify all returned items have the expected structure and properties
            filteredItems.content().forEach(item -> {
                assertThat(item.id()).isNotNull();
                assertThat(item.source()).isEqualTo(DatasetItemSource.MANUAL);
                assertThat(item.data()).isNotNull();
                assertThat(item.data()).containsKeys("Name", "Description");
                assertThat(item.createdAt()).isNotNull();
                assertThat(item.lastUpdatedAt()).isNotNull();
            });

            // Verify the returned items are Cat and Bird (filter worked correctly)
            // Note: JsonNode toString() includes escaped quotes for string values
            var names = filteredItems.content().stream()
                    .map(item -> item.data().get("Name").toString())
                    .toList();
            assertThat(names).containsExactlyInAnyOrder("\"\\\"Cat\\\"\"", "\"\\\"Bird\\\"\"");

            var descriptions = filteredItems.content().stream()
                    .map(item -> item.data().get("Description").toString())
                    .toList();
            assertThat(descriptions)
                    .containsExactlyInAnyOrder(
                            "\"\\\"Cat looking at camera\\\"\"",
                            "\"\\\"Blue jay perched on a wooden fence\\\"\"");

            // Verify Dog item is not in the results
            assertThat(names).doesNotContain("Dog");
            assertThat(descriptions).noneMatch(desc -> desc.contains("Dog"));
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
            var itemToPatch = v1Items.getFirst();

            // When - Patch the item with new data
            var newData = Map.of("patched", factory.manufacturePojo(JsonNode.class));
            var patchItem = DatasetItem.builder()
                    .data(newData)
                    .build();
            datasetResourceClient.patchDatasetItem(itemToPatch.id(), patchItem, API_KEY, TEST_WORKSPACE);

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
                    .filter(i -> i.datasetItemId().equals(itemToPatch.datasetItemId()))
                    .findFirst().orElseThrow();
            assertThat(originalItem.data()).isEqualTo(itemToPatch.data());

            // Verify latest version has patched data
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(3);
            var patchedItem = latestItems.stream()
                    .filter(i -> i.datasetItemId().equals(itemToPatch.datasetItemId()))
                    .findFirst().orElseThrow();
            assertThat(patchedItem.data()).isEqualTo(newData);
        }

        @Test
        @DisplayName("Success: Tagging items creates new version with modified count in change summary")
        void batchUpdate__whenTaggingItems__thenChangeSummaryShowsModified() {
            // Given - Create dataset with items (no tags initially)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 3);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Get items to update
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();

            // Select 1 item to tag (using row ID)
            var itemToTag = Set.of(v1Items.get(0).id());

            // When - Batch update to add tags to one item
            var newTags = Set.of("important");
            var batchUpdate = DatasetItemBatchUpdate.builder()
                    .ids(itemToTag)
                    .update(DatasetItemUpdate.builder().tags(newTags).build())
                    .build();
            datasetResourceClient.batchUpdateDatasetItems(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created with correct change summary
            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(3);
            assertThat(version2.itemsModified()).as("Tagged item should be counted as modified").isEqualTo(1);
            assertThat(version2.itemsAdded()).isEqualTo(0);
            assertThat(version2.itemsDeleted()).isEqualTo(0);

            // Verify the tagged item has the new tag
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            var taggedItem = v2Items.stream()
                    .filter(item -> item.datasetItemId().equals(v1Items.get(0).datasetItemId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(taggedItem.tags()).containsExactly("important");
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

            // Select 3 items to batch update (using row IDs as frontend would)
            var itemsToUpdate = Set.of(
                    v1Items.get(0).id(),
                    v1Items.get(1).id(),
                    v1Items.get(2).id());

            // Keep track of stable IDs for verification
            var stableIdsToUpdate = Set.of(
                    v1Items.get(0).datasetItemId(),
                    v1Items.get(1).datasetItemId(),
                    v1Items.get(2).datasetItemId());

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

            // Verify v1 items don't have the new tags (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            for (var item : v1ItemsAfter) {
                if (item.tags() != null) {
                    assertThat(item.tags()).doesNotContain("batch-updated");
                }
            }

            // Verify latest version has updated tags on the 3 items
            // Note: Compare using datasetItemId (stable ID) since row IDs change across versions
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();

            int updatedCount = 0;
            for (var item : latestItems) {
                if (stableIdsToUpdate.contains(item.datasetItemId())) {
                    assertThat(item.tags()).containsAll(newTags);
                    updatedCount++;
                }
            }
            assertThat(updatedCount).isEqualTo(3);
        }

        @Test
        @DisplayName("Success: Batch update by filters creates new version with edits")
        void batchUpdateByFilters__whenVersioningEnabled__thenCreateNewVersionWithEdits() {
            // Given - Create dataset with items having different tags
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create items with specific tags for filtering
            var item1 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("filter-me", "tag1"))
                    .build();
            var item2 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("filter-me", "tag2"))
                    .build();
            var item3 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("keep-me", "tag3"))
                    .build();
            var item4 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("filter-me", "tag4"))
                    .build();
            var item5 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("keep-me", "tag5"))
                    .build();

            var batch = DatasetItemBatch.builder().datasetId(datasetId)
                    .items(List.of(item1, item2, item3, item4, item5)).batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(5);

            // Tag v1 for later reference
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // When - Batch update by filters (update items with "filter-me" tag)
            var filter = new DatasetItemFilter(DatasetItemField.TAGS, Operator.CONTAINS, null, "filter-me");

            var newTags = Set.of("batch-updated-by-filter", "new-tag");
            var batchUpdate = DatasetItemBatchUpdate.builder()
                    .datasetId(datasetId)
                    .filters(List.of(filter))
                    .update(DatasetItemUpdate.builder().tags(newTags).build())
                    .mergeTags(true) // Merge tags (note: for filter-based updates, mergeTags is always true per DatasetItemBatchUpdate accessor)
                    .build();
            datasetResourceClient.batchUpdateDatasetItems(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(5);

            // Verify v1 items still have original tags (immutable)
            var v1ItemsAfter = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            int v1FilterMeCount = 0;
            for (var item : v1ItemsAfter) {
                if (item.tags() != null && item.tags().contains("filter-me")) {
                    v1FilterMeCount++;
                    assertThat(item.tags()).doesNotContain("batch-updated-by-filter");
                }
            }
            assertThat(v1FilterMeCount).isEqualTo(3); // 3 items had "filter-me" tag

            // Verify latest version has updated tags only on filtered items
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();

            int updatedCount = 0;
            int unchangedCount = 0;
            for (var item : latestItems) {
                if (item.tags() != null) {
                    if (item.tags().contains("batch-updated-by-filter")) {
                        // These are the items that matched the filter - tags should be merged
                        assertThat(item.tags()).containsAll(newTags);
                        assertThat(item.tags()).contains("filter-me"); // Original tags preserved (merged)
                        updatedCount++;
                    } else if (item.tags().contains("keep-me")) {
                        // These items should remain unchanged
                        assertThat(item.tags()).doesNotContain("batch-updated-by-filter");
                        unchangedCount++;
                    }
                }
            }
            assertThat(updatedCount).isEqualTo(3); // 3 items matched the filter
            assertThat(unchangedCount).isEqualTo(2); // 2 items were not filtered
        }

        @Test
        @DisplayName("Batch update with EMPTY filters should update ALL items (select all)")
        void batchUpdateByEmptyFilters__shouldUpdateAllItems() {
            // Given - Create dataset with items having different tags
            var datasetId = createDataset(UUID.randomUUID().toString());

            var item1 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .datasetItemId(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("tag1"))
                    .build();
            var item2 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .datasetItemId(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("tag2"))
                    .build();
            var item3 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .datasetItemId(null)
                    .source(DatasetItemSource.MANUAL)
                    .traceId(null)
                    .spanId(null)
                    .tags(Set.of("tag3"))
                    .build();

            var batch = DatasetItemBatch.builder().datasetId(datasetId).items(List.of(item1, item2, item3))
                    .batchGroupId(UUID.randomUUID()).build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // When - Batch update with EMPTY filters (should select ALL items)
            var newTag = "all-items-tag";
            var batchUpdate = DatasetItemBatchUpdate.builder()
                    .datasetId(datasetId)
                    .filters(List.of()) // Empty filters = select all items
                    .update(DatasetItemUpdate.builder().tags(Set.of(newTag)).build())
                    .mergeTags(true)
                    .build();
            datasetResourceClient.batchUpdateDatasetItems(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created with ALL items updated
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(3);

            // Verify ALL items in latest version have the new tag (merged with existing tags)
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();

            assertThat(latestItems).hasSize(3);
            for (var item : latestItems) {
                assertThat(item.tags()).contains(newTag); // All items should have the new tag
                // Original tags should be preserved (merge behavior)
                assertThat(item.tags().size()).isGreaterThan(1); // Should have both old and new tags
            }

            // Specifically verify each item
            var itemsWithTag1 = latestItems.stream()
                    .filter(i -> i.tags().contains("tag1"))
                    .toList();
            var itemsWithTag2 = latestItems.stream()
                    .filter(i -> i.tags().contains("tag2"))
                    .toList();
            var itemsWithTag3 = latestItems.stream()
                    .filter(i -> i.tags().contains("tag3"))
                    .toList();

            assertThat(itemsWithTag1).hasSize(1);
            assertThat(itemsWithTag1.get(0).tags()).containsExactlyInAnyOrder("tag1", newTag);

            assertThat(itemsWithTag2).hasSize(1);
            assertThat(itemsWithTag2.get(0).tags()).containsExactlyInAnyOrder("tag2", newTag);

            assertThat(itemsWithTag3).hasSize(1);
            assertThat(itemsWithTag3.get(0).tags()).containsExactlyInAnyOrder("tag3", newTag);
        }
    }

    @Nested
    @DisplayName("Create Items From Traces/Spans With Versioning:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateFromTracesAndSpans {

        private final com.fasterxml.uuid.impl.TimeBasedEpochGenerator traceIdGenerator = com.fasterxml.uuid.Generators
                .timeBasedEpochGenerator();

        @Test
        @DisplayName("Success: Create dataset items from traces creates new version")
        void createFromTraces__whenVersioningEnabled__thenCreateNewVersion() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create some initial items to establish version 1
            createDatasetItems(datasetId, 2);
            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(2);

            // Tag version 1
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Create traces using proper client helpers
            String projectName = traceIdGenerator.generate().toString();
            var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 1\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 1\"}"))
                    .tags(Set.of("trace-tag1"))
                    .build();

            var trace2 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 2\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 2\"}"))
                    .tags(Set.of("trace-tag2"))
                    .build();

            traceResourceClient.createTrace(trace1, API_KEY, TEST_WORKSPACE);
            traceResourceClient.createTrace(trace2, API_KEY, TEST_WORKSPACE);

            // When - Create dataset items from traces
            var enrichmentOptions = TraceEnrichmentOptions.builder()
                    .includeSpans(false)
                    .includeTags(true)
                    .includeFeedbackScores(false)
                    .includeComments(false)
                    .includeUsage(false)
                    .includeMetadata(false)
                    .build();

            var request = CreateDatasetItemsFromTracesRequest.builder()
                    .traceIds(Set.of(trace1.id(), trace2.id()))
                    .enrichmentOptions(enrichmentOptions)
                    .build();

            datasetResourceClient.createDatasetItemsFromTraces(datasetId, request, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(4); // 2 original + 2 from traces
            assertThat(version2.itemsAdded()).isEqualTo(2); // 2 new items from traces
            // Note: changeDescription is null when adding via traces (not using applyDatasetItemChanges)

            // Verify version 1 is unchanged (immutable)
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(2);

            // Verify latest version has all 4 items
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE)
                    .content();
            assertThat(latestItems).hasSize(4);

            // Verify the new items have the correct source (at least 2 from traces)
            long traceSourceCount = latestItems.stream()
                    .filter(item -> item.source() == DatasetItemSource.TRACE)
                    .count();
            assertThat(traceSourceCount).isGreaterThanOrEqualTo(2);

            // Verify the trace items have trace IDs set
            var traceItems = latestItems.stream()
                    .filter(item -> item.source() == DatasetItemSource.TRACE)
                    .toList();
            assertThat(traceItems).allMatch(item -> item.traceId() != null);
        }

        @Test
        @DisplayName("Success: Create dataset items from spans creates new version")
        void createFromSpans__whenVersioningEnabled__thenCreateNewVersion() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create some initial items to establish version 1
            createDatasetItems(datasetId, 3);
            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // Tag version 1
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Create traces and spans using proper client helpers
            String projectName = traceIdGenerator.generate().toString();
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"parent trace\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"parent response\"}"))
                    .build();

            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var span1 = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(trace.id())
                    .name("span1")
                    .input(JsonUtils.getJsonNodeFromString("{\"input\": \"span input 1\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"span output 1\"}"))
                    .tags(Set.of("span-tag1"))
                    .build();

            var span2 = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(trace.id())
                    .name("span2")
                    .input(JsonUtils.getJsonNodeFromString("{\"input\": \"span input 2\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"span output 2\"}"))
                    .tags(Set.of("span-tag2"))
                    .build();

            spanResourceClient.createSpan(span1, API_KEY, TEST_WORKSPACE);
            spanResourceClient.createSpan(span2, API_KEY, TEST_WORKSPACE);

            // When - Create dataset items from spans
            var enrichmentOptions = SpanEnrichmentOptions.builder()
                    .includeTags(true)
                    .includeFeedbackScores(false)
                    .includeComments(false)
                    .includeUsage(false)
                    .includeMetadata(false)
                    .build();

            var request = CreateDatasetItemsFromSpansRequest.builder()
                    .spanIds(Set.of(span1.id(), span2.id()))
                    .enrichmentOptions(enrichmentOptions)
                    .build();

            datasetResourceClient.createDatasetItemsFromSpans(datasetId, request, API_KEY, TEST_WORKSPACE);

            // Then - Verify new version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.id()).isNotEqualTo(version1.id());
            assertThat(version2.itemsTotal()).isEqualTo(5); // 3 original + 2 from spans
            assertThat(version2.itemsAdded()).isEqualTo(2); // 2 new items from spans
            // Note: changeDescription is null when adding via spans (not using applyDatasetItemChanges)

            // Verify version 1 is unchanged (immutable)
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(3);

            // Verify latest version has all 5 items
            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE)
                    .content();
            assertThat(latestItems).hasSize(5);

            // Verify the new items have the correct source and span IDs
            var spanItems = latestItems.stream()
                    .filter(item -> item.source() == DatasetItemSource.SPAN)
                    .toList();
            assertThat(spanItems).hasSize(2);
            assertThat(spanItems).allMatch(item -> item.spanId() != null);
        }
    }

    @Nested
    @DisplayName("Experiment Dataset Version Linking:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ExperimentDatasetVersionLinking {

        private Experiment getExperiment(UUID id) {
            return experimentResourceClient.getExperiment(id, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("Success: Create experiment with explicit dataset version ID")
        void createExperiment_whenExplicitVersionId_thenVersionIdPersisted() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);
            createDatasetItems(datasetId, 1);

            var version1 = getLatestVersion(datasetId);

            // when - create experiment with explicit version ID
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(version1.id())
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // then - experiment should have the specified version ID and version summary
            var createdExperiment = getExperiment(experimentId);
            assertThat(createdExperiment.datasetVersionId()).isEqualTo(version1.id());
            assertThat(createdExperiment.datasetVersionSummary()).isNotNull();
            assertThat(createdExperiment.datasetVersionSummary().id()).isEqualTo(version1.id());
            assertThat(createdExperiment.datasetVersionSummary().versionHash()).isEqualTo(version1.versionHash());
        }

        @Test
        @DisplayName("Success: Create experiment without version ID uses latest version")
        void createExperiment_whenNoVersionId_thenLatestVersionUsed() {
            // given - create dataset with custom fields (not "input"/"output")
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);

            // Create dataset items with custom fields different from trace input/output
            List<DatasetItem> itemsList = List.of(
                    factory.manufacturePojo(DatasetItem.class).toBuilder()
                            .id(null)
                            .source(DatasetItemSource.MANUAL)
                            .traceId(null)
                            .spanId(null)
                            .data(Map.of(
                                    "job_title", JsonUtils.getJsonNodeFromString("\"Software Engineer\""),
                                    "salary", JsonUtils.getJsonNodeFromString("\"100000\"")))
                            .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(itemsList)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // when - create experiment WITHOUT specifying version ID
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // then - experiment should have a version ID and version summary from the dataset
            var createdExperiment = getExperiment(experimentId);
            assertThat(createdExperiment.datasetVersionId()).isNotNull();
            assertThat(createdExperiment.datasetVersionSummary()).isNotNull();
            assertThat(createdExperiment.datasetId()).isEqualTo(datasetId);

            // Verify the version belongs to this dataset
            var allVersions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            var versionIds = allVersions.content().stream().map(DatasetVersion::id).toList();
            assertThat(versionIds).contains(createdExperiment.datasetVersionId());

            // Verify version summary matches the version
            var matchingVersion = allVersions.content().stream()
                    .filter(v -> v.id().equals(createdExperiment.datasetVersionId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(createdExperiment.datasetVersionSummary().id()).isEqualTo(matchingVersion.id());
            assertThat(createdExperiment.datasetVersionSummary().versionHash())
                    .isEqualTo(matchingVersion.versionHash());

            // Now test creating experiment items (this validates the dataset item IDs)
            var datasetItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(datasetItems).hasSize(1);

            var datasetItem = datasetItems.getFirst();

            // Create a trace first (experiment items require a trace ID)
            // The trace's output field should have "input" and "output" keys so they appear in output_keys
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(UUID.randomUUID().toString())
                    .output(JsonUtils.getJsonNodeFromString(
                            "{\"input\": {\"prompt\": \"test prompt\"}, \"output\": {\"response\": \"test response\"}}"))
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            // Create experiment item using the stable dataset item ID
            // Copy input/output from the trace
            var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experimentId)
                    .datasetItemId(datasetItem.id())
                    .traceId(trace.id())
                    .input(null)
                    .output(trace.output()) // Copy output from trace
                    .usage(null)
                    .feedbackScores(null)
                    .build();

            experimentResourceClient.createExperimentItem(Set.of(experimentItem), API_KEY, TEST_WORKSPACE);

            // Verify experiment item was created successfully
            var experimentItems = experimentResourceClient.getExperimentItems(experiment.name(), API_KEY,
                    TEST_WORKSPACE);
            assertThat(experimentItems).hasSize(1);
            assertThat(experimentItems.getFirst().datasetItemId()).isEqualTo(datasetItem.id());

            // Verify the frontend endpoint returns experiment_items array populated
            var datasetItemsWithExperiments = datasetResourceClient.getDatasetItemsWithExperimentItems(
                    datasetId, List.of(experimentId), API_KEY, TEST_WORKSPACE);

            assertThat(datasetItemsWithExperiments.content()).hasSize(1);
            var itemWithExperiments = datasetItemsWithExperiments.content().getFirst();

            assertThat(itemWithExperiments.experimentItems())
                    .as("experiment_items array must be populated for frontend to work")
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(1);

            // Verify the experiment item details are correct
            var returnedExperimentItem = itemWithExperiments.experimentItems().getFirst();
            assertThat(returnedExperimentItem.experimentId()).isEqualTo(experimentId);
            assertThat(returnedExperimentItem.datasetItemId()).isEqualTo(datasetItem.id());

            var columnNames = datasetItemsWithExperiments.columns().stream()
                    .map(Column::name)
                    .collect(Collectors.toSet());
            assertThat(columnNames)
                    .as("Columns should include dataset item data fields (job_title, salary)")
                    .contains("job_title", "salary");
        }

        @Test
        @DisplayName("Success: List experiments returns correct version IDs")
        void listExperiments_whenLinkedToVersion_thenCorrectVersionIdReturned() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);
            createDatasetItems(datasetId, 1);

            var version1 = getLatestVersion(datasetId);

            // Create experiment with explicit version
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(version1.id())
                    .build();
            experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - list experiments
            var experimentsList = experimentResourceClient.findExperiments(
                    1, 10, datasetId, null, null, null, false, null, null, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);

            // then - version ID and version summary should be present in the list
            assertThat(experimentsList.content())
                    .hasSize(1)
                    .first()
                    .satisfies(exp -> {
                        assertThat(exp.datasetVersionId()).isEqualTo(version1.id());
                        assertThat(exp.datasetVersionSummary()).isNotNull();
                        assertThat(exp.datasetVersionSummary().id()).isEqualTo(version1.id());
                        assertThat(exp.datasetVersionSummary().versionHash()).isEqualTo(version1.versionHash());
                    });
        }

        @Test
        @DisplayName("Success: Multiple experiments linked to different versions")
        void listExperiments_whenMultipleVersions_thenCorrectVersionIdsReturned() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);
            createDatasetItems(datasetId, 1);

            var version1 = getLatestVersion(datasetId);

            // Create another version
            createDatasetItems(datasetId, 1);
            var version2 = getLatestVersion(datasetId);

            // Create experiment 1 linked to version 1
            var experiment1 = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(version1.id())
                    .build();
            var experimentId1 = experimentResourceClient.create(experiment1, API_KEY, TEST_WORKSPACE);

            // Create experiment 2 linked to version 2
            var experiment2 = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(version2.id())
                    .build();
            var experimentId2 = experimentResourceClient.create(experiment2, API_KEY, TEST_WORKSPACE);

            // when - list experiments
            var experimentsList = experimentResourceClient.findExperiments(
                    1, 10, datasetId, null, null, null, false, null, null, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);

            // then - each experiment should have its correct version ID and version summary
            assertThat(experimentsList.content()).hasSize(2);

            var exp1FromList = experimentsList.content().stream()
                    .filter(e -> e.id().equals(experimentId1))
                    .findFirst()
                    .orElseThrow();
            assertThat(exp1FromList.datasetVersionId()).isEqualTo(version1.id());
            assertThat(exp1FromList.datasetVersionSummary()).isNotNull();
            assertThat(exp1FromList.datasetVersionSummary().id()).isEqualTo(version1.id());
            assertThat(exp1FromList.datasetVersionSummary().versionHash()).isEqualTo(version1.versionHash());

            var exp2FromList = experimentsList.content().stream()
                    .filter(e -> e.id().equals(experimentId2))
                    .findFirst()
                    .orElseThrow();
            assertThat(exp2FromList.datasetVersionId()).isEqualTo(version2.id());
            assertThat(exp2FromList.datasetVersionSummary()).isNotNull();
            assertThat(exp2FromList.datasetVersionSummary().id()).isEqualTo(version2.id());
            assertThat(exp2FromList.datasetVersionSummary().versionHash()).isEqualTo(version2.versionHash());
        }

        @Test
        @DisplayName("Error: Create experiment with non-existent version ID")
        void createExperiment_whenInvalidVersionId_thenConflict() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);
            createDatasetItems(datasetId, 1);

            var nonExistentVersionId = UUID.randomUUID();

            // when - create experiment with non-existent version ID
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(nonExistentVersionId)
                    .build();

            // then - should fail with 409 (aligned with legacy behavior)
            try (var response = experimentResourceClient.callCreate(experiment, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("Error: Create experiment with version ID from different dataset")
        void createExperiment_whenVersionFromDifferentDataset_thenConflict() {
            // given - create two datasets with versions
            var dataset1Name = UUID.randomUUID().toString();
            var dataset1Id = createDataset(dataset1Name);
            createDatasetItems(dataset1Id, 1);
            var version1 = getLatestVersion(dataset1Id);

            var dataset2Name = UUID.randomUUID().toString();
            createDataset(dataset2Name);

            // when - try to create experiment on dataset2 with version from dataset1
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset2Name)
                    .datasetVersionId(version1.id())
                    .build();

            // then - should fail with 409 (aligned with legacy behavior - version doesn't belong to dataset2)
            try (var response = experimentResourceClient.callCreate(experiment, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("Success: Pagination count matches filtered results for experiment items")
        void getDatasetItemsWithExperiments_whenFilteredByExperiment_thenCountMatchesFilteredResults() {
            // given - create dataset with 100 items
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);
            createDatasetItems(datasetId, 100);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(100);

            // Create two experiments
            var experiment1 = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(version1.id())
                    .build();
            var experimentId1 = experimentResourceClient.create(experiment1, API_KEY, TEST_WORKSPACE);

            var experiment2 = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .datasetVersionId(version1.id())
                    .build();
            var experimentId2 = experimentResourceClient.create(experiment2, API_KEY, TEST_WORKSPACE);

            // Fetch dataset items
            var datasetItems = datasetResourceClient.getDatasetItems(datasetId, 1, 100, null, API_KEY, TEST_WORKSPACE)
                    .content();

            // Link only 10 items to experiment1 (items 0-9) and 5 items to experiment2 (items 10-14)
            linkExperimentItems(experimentId1, datasetItems, 0, 10);
            linkExperimentItems(experimentId2, datasetItems, 10, 15);

            // when - get dataset items filtered by experiment1 (should return 10 items)
            var datasetItemsWithExperiment1 = datasetResourceClient.getDatasetItemsWithExperimentItems(
                    datasetId, List.of(experimentId1), API_KEY, TEST_WORKSPACE);

            // then - pagination count should match filtered results (10), not total dataset items (100)
            assertThat(datasetItemsWithExperiment1.total())
                    .as("Total should be 10 (items linked to experiment1), not 100 (all items in dataset)")
                    .isEqualTo(10L);
            assertThat(datasetItemsWithExperiment1.content())
                    .as("Should return all 10 items in one page")
                    .hasSize(10);

            // when - get dataset items filtered by experiment2 (should return 5 items)
            var datasetItemsWithExperiment2 = datasetResourceClient.getDatasetItemsWithExperimentItems(
                    datasetId, List.of(experimentId2), API_KEY, TEST_WORKSPACE);

            // then - pagination count should match filtered results (5), not total dataset items (100)
            assertThat(datasetItemsWithExperiment2.total())
                    .as("Total should be 5 (items linked to experiment2), not 100 (all items in dataset)")
                    .isEqualTo(5L);
            assertThat(datasetItemsWithExperiment2.content())
                    .as("Should return all 5 items in one page")
                    .hasSize(5);

            // when - get dataset items filtered by both experiments (should return 15 items total)
            var datasetItemsWithBothExperiments = datasetResourceClient.getDatasetItemsWithExperimentItems(
                    datasetId, List.of(experimentId1, experimentId2), API_KEY, TEST_WORKSPACE);

            // then - pagination count should be 15 (combined unique items from both experiments)
            assertThat(datasetItemsWithBothExperiments.total())
                    .as("Total should be 15 (items linked to either experiment), not 100 (all items in dataset)")
                    .isEqualTo(15L);
            // Note: content size will be limited by page size (default 10), but total count should be correct
            assertThat(datasetItemsWithBothExperiments.content().size())
                    .as("Should return items up to page size limit")
                    .isLessThanOrEqualTo(15);
        }

        /**
         * Helper method to link experiment items to dataset items.
         * Creates traces and experiment items for a range of dataset items.
         *
         * @param experimentId The experiment to link items to
         * @param datasetItems The list of dataset items
         * @param startIndex The starting index (inclusive)
         * @param endIndex The ending index (exclusive)
         */
        private void linkExperimentItems(UUID experimentId, List<DatasetItem> datasetItems, int startIndex,
                int endIndex) {
            for (int i = startIndex; i < endIndex; i++) {
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(UUID.randomUUID().toString())
                        .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                        .output(JsonUtils.getJsonNodeFromString(
                                "{\"input\": {\"prompt\": \"test prompt\"}, \"output\": {\"response\": \"response " + i
                                        + "\"}}"))
                        .build();
                traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

                var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experimentId)
                        .datasetItemId(datasetItems.get(i).id())
                        .traceId(trace.id())
                        .input(trace.input())
                        .output(trace.output())
                        .usage(null)
                        .feedbackScores(null)
                        .build();

                experimentResourceClient.createExperimentItem(Set.of(experimentItem), API_KEY, TEST_WORKSPACE);
            }
        }

        @Test
        @DisplayName("Success: PUT /items without query param returns 204 (backward compatibility)")
        void putItems_whenRespondWithLatestVersionNotSet_thenReturnsNoContent() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);

            var items = IntStream.range(0, 2)
                    .mapToObj(i -> {
                        DatasetItem item = factory.manufacturePojo(DatasetItem.class);
                        Map<String, JsonNode> data = Map.of(
                                "input", JsonUtils.getJsonNodeFromString("\"test input " + i + "\""),
                                "output", JsonUtils.getJsonNodeFromString("\"test output " + i + "\""));
                        return item.toBuilder()
                                .id(null)
                                .source(DatasetItemSource.MANUAL)
                                .traceId(null)
                                .spanId(null)
                                .data(data)
                                .build();
                    })
                    .toList();

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();

            // when - PUT without query param (default behavior)
            try (var actualResponse = datasetResourceClient.callCreateDatasetItems(batch, TEST_WORKSPACE, API_KEY)) {
                // then - should return 204 No Content (backward compatible)
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Stream Dataset Items With Versioning:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamDatasetItemsWithVersioning {

        @Test
        @DisplayName("Success: Stream items uses latest version when toggle is ON")
        void streamItems_whenVersioningEnabled_thenUsesLatestVersion() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);

            // Create initial version with 2 items
            createDatasetItems(datasetId, 2);
            var version1 = getLatestVersion(datasetId);

            // Create second version with 1 more item (total 3)
            createDatasetItems(datasetId, 1);
            var version2 = getLatestVersion(datasetId);

            assertThat(version1.id()).isNotEqualTo(version2.id());
            assertThat(version2.itemsTotal()).isEqualTo(3);

            // when - stream items without specifying version
            var streamRequest = DatasetItemStreamRequest.builder()
                    .datasetName(datasetName)
                    .steamLimit(100)
                    .build();
            var streamedItems = datasetResourceClient.streamDatasetItems(streamRequest, API_KEY, TEST_WORKSPACE);

            // then - should get items from latest version (3 items)
            assertThat(streamedItems).hasSize(3);
        }

        @Test
        @DisplayName("Success: Stream items with explicit version parameter")
        void streamItems_whenVersionSpecified_thenUsesSpecifiedVersion() {
            // given
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);

            // Create version 1 with 2 items
            createDatasetItems(datasetId, 2);
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Create version 2 with 1 more item (total 3)
            createDatasetItems(datasetId, 1);
            var version2 = getLatestVersion(datasetId);

            assertThat(version1.id()).isNotEqualTo(version2.id());
            assertThat(version2.itemsTotal()).isEqualTo(3);

            // when - stream items from version 1 using tag
            var streamRequestV1 = DatasetItemStreamRequest.builder()
                    .datasetName(datasetName)
                    .steamLimit(100)
                    .datasetVersion("v1")
                    .build();
            var streamedItemsV1 = datasetResourceClient.streamDatasetItems(streamRequestV1, API_KEY, TEST_WORKSPACE);

            // then - should get 2 items from version 1
            assertThat(streamedItemsV1).hasSize(2);

            // when - stream items from version 2 using 'latest' tag
            var streamRequestV2 = DatasetItemStreamRequest.builder()
                    .datasetName(datasetName)
                    .steamLimit(100)
                    .datasetVersion(DatasetVersionService.LATEST_TAG)
                    .build();
            var streamedItemsV2 = datasetResourceClient.streamDatasetItems(streamRequestV2, API_KEY, TEST_WORKSPACE);

            // then - should get 3 items from version 2
            assertThat(streamedItemsV2).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Delete Versioned Dataset:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteVersionedDataset {

        @Test
        @DisplayName("Success: Delete dataset with versions and tags")
        void deleteDataset__whenVersionedDatasetWithTags__thenReturnNoContent() {
            // given - create a dataset with versions and tags
            var datasetName = UUID.randomUUID().toString();
            var datasetId = createDataset(datasetName);

            // Create version 1 with items
            createDatasetItems(datasetId, 2);
            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            // Create version 2 with more items
            createDatasetItems(datasetId, 1);
            var version2 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version2.versionHash(),
                    DatasetVersionTag.builder().tag("production").build(), API_KEY, TEST_WORKSPACE);

            // Verify dataset and versions exist
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE, 1, 10);
            assertThat(versions.content()).hasSize(2);

            // when - delete the dataset
            try (var actualResponse = datasetResourceClient.callDeleteDataset(datasetId, API_KEY, TEST_WORKSPACE)) {
                // then - should return 204 No Content
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            // Verify dataset is deleted
            try (var getResponse = datasetResourceClient.callGetDatasetById(datasetId, API_KEY, TEST_WORKSPACE)) {
                assertThat(getResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Batch Versioning with batch_group_id:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchVersioningTests {

        @Test
        @DisplayName("Success: Multiple INSERT batches with same batch_group_id create single version")
        void putItems_whenSameBatchId_thenSingleVersion() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());
            var batchGroupId = UUID.randomUUID();

            // When - Send 3 batches with same batch_group_id
            var batch1Items = generateDatasetItems(2);
            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(batch1Items)
                    .batchGroupId(batchGroupId)
                    .build();

            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            var batch2Items = generateDatasetItems(3);
            var batch2 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(batch2Items)
                    .batchGroupId(batchGroupId)
                    .build();

            datasetResourceClient.createDatasetItems(batch2, TEST_WORKSPACE, API_KEY);

            var batch3Items = generateDatasetItems(1);
            var batch3 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(batch3Items)
                    .batchGroupId(batchGroupId)
                    .build();

            datasetResourceClient.createDatasetItems(batch3, TEST_WORKSPACE, API_KEY);

            // Then - Verify only ONE version was created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            // Verify the version has all 6 items (2 + 3 + 1)
            var version = getLatestVersion(datasetId);
            assertThat(version.itemsTotal()).isEqualTo(6);
            assertThat(version.itemsAdded()).isEqualTo(6);
            assertThat(version.versionName()).isEqualTo("v1");

            // Verify items can be fetched
            var items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE);
            assertThat(items.content()).hasSize(6);
        }

        @Test
        @DisplayName("Success: Different batch_group_ids create different versions")
        void putItems_whenDifferentBatchIds_thenMultipleVersions() {
            // Given - Create dataset
            var datasetId = createDataset(UUID.randomUUID().toString());
            var batchGroupId1 = UUID.randomUUID();
            var batchGroupId2 = UUID.randomUUID();

            // When - Send batch with batchGroupId1
            var batch1Items = generateDatasetItems(2);
            var batch1 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(batch1Items)
                    .batchGroupId(batchGroupId1)
                    .build();

            datasetResourceClient.createDatasetItems(batch1, TEST_WORKSPACE, API_KEY);

            // When - Send batch with batchGroupId2
            var batch2Items = generateDatasetItems(3);
            var batch2 = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(batch2Items)
                    .batchGroupId(batchGroupId2)
                    .build();

            datasetResourceClient.createDatasetItems(batch2, TEST_WORKSPACE, API_KEY);

            // Then - Verify TWO versions were created
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            // Verify version 1 has 2 items
            var version1 = versions.content().getLast(); // Oldest first
            assertThat(version1.itemsTotal()).isEqualTo(2);
            assertThat(version1.versionName()).isEqualTo("v1");

            // Verify version 2 has 5 items (2 from v1 + 3 new)
            var version2 = versions.content().getFirst(); // Latest first
            assertThat(version2.itemsTotal()).isEqualTo(5);
            assertThat(version2.versionName()).isEqualTo("v2");
        }
    }

    @Nested
    @DisplayName("Batch Versioning - DELETE Operations")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchVersioningDeleteTests {

        @Test
        @DisplayName("Success: Same batch_group_id for multiple DELETE batches creates single version")
        void deleteItems_whenSameBatchGroupId_thenSingleVersion() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(10);
            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get the version to retrieve item IDs
            var version1 = getLatestVersion(datasetId);
            var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 20, version1.versionHash(), API_KEY,
                    TEST_WORKSPACE);
            var itemIds = itemsPage.content().stream()
                    .map(DatasetItem::datasetItemId)
                    .toList();

            var batchGroupId = UUID.randomUUID();

            // When - Delete items in multiple batches with same batch_group_id
            // First batch: delete 3 items (itemIds is mutually exclusive with datasetId)
            var deleteRequest1 = DatasetItemsDelete.builder()
                    .itemIds(Set.copyOf(itemIds.subList(0, 3)))
                    .batchGroupId(batchGroupId)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest1, TEST_WORKSPACE, API_KEY);

            // Second batch: delete 2 more items
            var deleteRequest2 = DatasetItemsDelete.builder()
                    .itemIds(Set.copyOf(itemIds.subList(3, 5)))
                    .batchGroupId(batchGroupId)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest2, TEST_WORKSPACE, API_KEY);

            // Then - Verify only TWO versions exist (v1 = initial insert, v2 = all deletions)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            // Verify v1 has 10 items
            var versionAfter1 = versions.content().getLast(); // Oldest first
            assertThat(versionAfter1.itemsTotal()).isEqualTo(10);
            assertThat(versionAfter1.versionName()).isEqualTo("v1");

            // Verify v2 has 5 items (10 - 5 deleted)
            var version2 = versions.content().getFirst(); // Latest first
            assertThat(version2.itemsTotal()).isEqualTo(5);
            assertThat(version2.itemsDeleted()).isEqualTo(5);
            assertThat(version2.versionName()).isEqualTo("v2");
        }

        @Test
        @DisplayName("Success: Different batch_group_ids for DELETE create different versions")
        void deleteItems_whenDifferentBatchGroupIds_thenMultipleVersions() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(10);
            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get the version to retrieve item IDs
            var version1 = getLatestVersion(datasetId);
            var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 20, version1.versionHash(), API_KEY,
                    TEST_WORKSPACE);
            var itemIds = itemsPage.content().stream()
                    .map(DatasetItem::datasetItemId)
                    .toList();

            var batchGroupId1 = UUID.randomUUID();
            var batchGroupId2 = UUID.randomUUID();

            // When - Delete with first batch_group_id (itemIds is mutually exclusive with datasetId)
            var deleteRequest1 = DatasetItemsDelete.builder()
                    .itemIds(Set.copyOf(itemIds.subList(0, 3)))
                    .batchGroupId(batchGroupId1)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest1, TEST_WORKSPACE, API_KEY);

            // When - Delete with second batch_group_id
            var deleteRequest2 = DatasetItemsDelete.builder()
                    .itemIds(Set.copyOf(itemIds.subList(3, 5)))
                    .batchGroupId(batchGroupId2)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest2, TEST_WORKSPACE, API_KEY);

            // Then - Verify THREE versions exist (v1 = insert, v2 = first delete, v3 = second delete)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(3);

            // Verify v1 has 10 items
            var versionAfter1 = versions.content().get(2); // Oldest
            assertThat(versionAfter1.itemsTotal()).isEqualTo(10);
            assertThat(versionAfter1.versionName()).isEqualTo("v1");

            // Verify v2 has 7 items (10 - 3)
            var version2 = versions.content().get(1);
            assertThat(version2.itemsTotal()).isEqualTo(7);
            assertThat(version2.itemsDeleted()).isEqualTo(3);
            assertThat(version2.versionName()).isEqualTo("v2");

            // Verify v3 has 5 items (7 - 2)
            var version3 = versions.content().getFirst(); // Latest
            assertThat(version3.itemsTotal()).isEqualTo(5);
            assertThat(version3.itemsDeleted()).isEqualTo(2);
            assertThat(version3.versionName()).isEqualTo("v3");
        }

        @Test
        @DisplayName("Success: Old SDK without batch_group_id creates version per delete")
        void deleteItems_whenNoBatchGroupId_thenVersionPerDelete() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = generateDatasetItems(10);
            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get the version to retrieve item IDs
            var version1 = getLatestVersion(datasetId);
            var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 20, version1.versionHash(), API_KEY,
                    TEST_WORKSPACE);
            var itemIds = itemsPage.content().stream()
                    .map(DatasetItem::datasetItemId)
                    .toList();

            // When - Delete items in multiple batches WITHOUT batch_group_id (old SDK)
            // itemIds is mutually exclusive with datasetId
            var deleteRequest1 = DatasetItemsDelete.builder()
                    .itemIds(Set.copyOf(itemIds.subList(0, 3)))
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest1, TEST_WORKSPACE, API_KEY);

            var deleteRequest2 = DatasetItemsDelete.builder()
                    .itemIds(Set.copyOf(itemIds.subList(3, 5)))
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest2, TEST_WORKSPACE, API_KEY);

            // Then - Verify THREE versions (v1 = insert, v2 = first delete, v3 = second delete)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(3);

            // Each delete should create its own version
            var version2 = versions.content().get(1);
            assertThat(version2.itemsTotal()).isEqualTo(7);
            assertThat(version2.itemsDeleted()).isEqualTo(3);

            var version3 = versions.content().get(0);
            assertThat(version3.itemsTotal()).isEqualTo(5);
            assertThat(version3.itemsDeleted()).isEqualTo(2);
        }

        @Test
        @DisplayName("Success: Filter-based deletion without batch_group_id mutates latest version")
        void deleteItems_whenFilterBasedDeletionWithoutBatchGroupId_thenMutatesLatestVersion() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = List.of(
                    DatasetItem.builder()
                            .source(DatasetItemSource.MANUAL)
                            .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test1\""),
                                    "expected", JsonUtils.getJsonNodeFromString("\"output1\"")))
                            .build(),
                    DatasetItem.builder()
                            .source(DatasetItemSource.MANUAL)
                            .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test2\""),
                                    "expected", JsonUtils.getJsonNodeFromString("\"output2\"")))
                            .build(),
                    DatasetItem.builder()
                            .source(DatasetItemSource.MANUAL)
                            .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test3\""),
                                    "expected", JsonUtils.getJsonNodeFromString("\"output3\"")))
                            .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get the initial version
            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // When - Delete items using filter WITHOUT batch_group_id (should mutate in-place)
            var filter = DatasetItemFilter.builder()
                    .field(DatasetItemField.DATA)
                    .key("input")
                    .operator(Operator.CONTAINS)
                    .value("test1")
                    .build();

            var deleteRequest = DatasetItemsDelete.builder()
                    .datasetId(datasetId)
                    .filters(List.of(filter))
                    .batchGroupId(null) // No batch_group_id = mutate in-place
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

            // Then - Verify STILL ONE version (mutated in-place)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            // Version should have updated counts
            var updatedVersion = versions.content().get(0);
            assertThat(updatedVersion.id()).isEqualTo(version1.id()); // Same version ID
            assertThat(updatedVersion.itemsTotal()).isEqualTo(2); // 3 - 1 deleted
            assertThat(updatedVersion.itemsDeleted()).isEqualTo(1);

            // Verify the correct item was deleted
            var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 20, updatedVersion.versionHash(),
                    API_KEY, TEST_WORKSPACE);
            assertThat(itemsPage.content()).hasSize(2);
            assertThat(itemsPage.content())
                    .extracting(item -> item.data().get("input").asText())
                    .containsExactlyInAnyOrder("test2", "test3");
        }

        @Test
        @DisplayName("Success: Empty filter list (delete all) without batch_group_id mutates latest version")
        void deleteItems_whenEmptyFilterListWithoutBatchGroupId_thenDeletesAllAndMutatesLatestVersion() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            var items = List.of(
                    DatasetItem.builder()
                            .source(DatasetItemSource.MANUAL)
                            .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test1\""),
                                    "expected", JsonUtils.getJsonNodeFromString("\"output1\"")))
                            .build(),
                    DatasetItem.builder()
                            .source(DatasetItemSource.MANUAL)
                            .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test2\""),
                                    "expected", JsonUtils.getJsonNodeFromString("\"output2\"")))
                            .build(),
                    DatasetItem.builder()
                            .source(DatasetItemSource.MANUAL)
                            .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test3\""),
                                    "expected", JsonUtils.getJsonNodeFromString("\"output3\"")))
                            .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Get the initial version
            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(3);

            // When - Delete all items using empty filter list WITHOUT batch_group_id (should mutate in-place)
            var deleteRequest = DatasetItemsDelete.builder()
                    .datasetId(datasetId)
                    .filters(List.of()) // Empty filters = delete all
                    .batchGroupId(null) // No batch_group_id = mutate in-place
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

            // Then - Verify STILL ONE version (mutated in-place)
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(1);

            // Version should have all items deleted
            var updatedVersion = versions.content().get(0);
            assertThat(updatedVersion.id()).isEqualTo(version1.id()); // Same version ID
            assertThat(updatedVersion.itemsTotal()).isEqualTo(0); // All items deleted
            assertThat(updatedVersion.itemsDeleted()).isEqualTo(3);

            // Verify no items remain
            var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 20, updatedVersion.versionHash(),
                    API_KEY, TEST_WORKSPACE);
            assertThat(itemsPage.content()).isEmpty();
        }

        @Test
        @DisplayName("Bug OPIK-3894: Batched deletion with same batch_group_id should append to same version")
        void deleteItems_whenBatchedDeletionWithSameBatchGroupId_thenAppendsToSameVersion() {
            // Given - Create dataset with 20 items (simulating larger dataset)
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 20);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(20);

            // Get items from version 1
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 30, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(20);

            // Simulate SDK batching: SDK breaks 10-item deletion into 2 batches with same batch_group_id
            var batchGroupId = UUID.randomUUID();

            // When - Batch 1: Delete first 5 items with batch_group_id
            var batch1Ids = v1Items.subList(0, 5).stream()
                    .map(DatasetItem::id)
                    .collect(Collectors.toSet());

            var deleteRequest1 = DatasetItemsDelete.builder()
                    .itemIds(batch1Ids)
                    .batchGroupId(batchGroupId) // Same batch_group_id for all batches
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest1, TEST_WORKSPACE, API_KEY);

            // Then - Version 2 created with 5 items deleted
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versions.content()).hasSize(2);

            var version2AfterBatch1 = getLatestVersion(datasetId);
            assertThat(version2AfterBatch1.itemsTotal()).isEqualTo(15); // 20 - 5 = 15
            assertThat(version2AfterBatch1.itemsDeleted()).isEqualTo(5);

            // When - Batch 2: Delete next 5 items with SAME batch_group_id
            var batch2Ids = v1Items.subList(5, 10).stream()
                    .map(DatasetItem::id)
                    .collect(Collectors.toSet());

            var deleteRequest2 = DatasetItemsDelete.builder()
                    .itemIds(batch2Ids)
                    .batchGroupId(batchGroupId) // SAME batch_group_id - should append to version 2
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest2, TEST_WORKSPACE, API_KEY);

            // Then - STILL only 2 versions (batch 2 appended to version 2, not created version 3)
            var versionsAfterBatch2 = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfterBatch2.content())
                    .as("Should still have 2 versions - batch 2 appends to version 2")
                    .hasSize(2);

            // Version 2 should now have 10 items deleted total (5 from batch 1 + 5 from batch 2)
            var version2AfterBatch2 = getLatestVersion(datasetId);
            assertThat(version2AfterBatch2.id())
                    .as("Should be the same version ID")
                    .isEqualTo(version2AfterBatch1.id());
            assertThat(version2AfterBatch2.itemsTotal())
                    .as("Should have 10 items remaining (20 - 10 deleted)")
                    .isEqualTo(10);
            assertThat(version2AfterBatch2.itemsDeleted())
                    .as("Should show 10 total items deleted")
                    .isEqualTo(10);

            // Verify the actual items in version 2
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 30, version2AfterBatch2.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(10);
        }

        @Test
        @DisplayName("Deletion correctly filters out non-existent IDs during row ID mapping")
        void deleteItems_whenFirstItemDoesNotExist_thenFiltersNonExistentAndDeletesValid() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 10);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(10);

            // Get items from version 1
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 20, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(10);

            // When - Try to delete items where the FIRST ID is non-existent, but the rest are valid
            var nonExistentId = UUID.randomUUID(); // Completely made-up ID that doesn't exist
            var validIds = v1Items.subList(0, 5).stream()
                    .map(DatasetItem::datasetItemId) // Use stable dataset_item_id (SDK path)
                    .collect(Collectors.toSet());

            // Create a list where non-existent ID is FIRST (order matters!)
            var idsWithNonExistentFirst = new java.util.LinkedHashSet<UUID>();
            idsWithNonExistentFirst.add(nonExistentId); // NON-EXISTENT ID FIRST
            idsWithNonExistentFirst.addAll(validIds); // Valid IDs after

            var batchGroupId = UUID.randomUUID();
            var deleteRequest = DatasetItemsDelete.builder()
                    .itemIds(idsWithNonExistentFirst)
                    .batchGroupId(batchGroupId)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

            // Then - System correctly handles non-existent first ID by filtering it during mapping
            var versionsAfter = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfter.content())
                    .as("Version 2 should be created with 5 valid items deleted")
                    .hasSize(2);

            // Version 2 was created with 5 items deleted (non-existent ID was filtered out)
            var version2 = getLatestVersion(datasetId);
            assertThat(version2.itemsTotal()).isEqualTo(5); // 10 - 5 = 5 items remaining
            assertThat(version2.itemsDeleted()).isEqualTo(5); // 5 valid items deleted
        }

        @Test
        @DisplayName("Bug OPIK-3894 FIX: When some IDs are non-existent, resolve dataset from any existing ID")
        void deleteItems_whenSomeIdsAreNonExistentDatasetItemIds_thenResolvesFromExistingIds() {
            // Given - Create dataset with items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 10);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(10);

            // Get items from version 1
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 20, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(10);

            // When - Delete with mix: non-existent IDs FIRST, then valid IDs
            // Before fix: would fail because first ID doesn't resolve to a dataset
            // After fix: should try all IDs until one resolves successfully
            var nonExistentIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
            var validIds = v1Items.subList(0, 5).stream()
                    .map(DatasetItem::datasetItemId)
                    .collect(Collectors.toSet());

            var mixedIds = new java.util.LinkedHashSet<UUID>();
            mixedIds.addAll(nonExistentIds); // Non-existent first
            mixedIds.addAll(validIds); // Valid after

            var batchGroupId = UUID.randomUUID();
            var deleteRequest = DatasetItemsDelete.builder()
                    .itemIds(mixedIds)
                    .batchGroupId(batchGroupId)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest, TEST_WORKSPACE, API_KEY);

            // Then - FIX: Version 2 should be created with 5 items deleted
            var versionsAfter = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfter.content())
                    .as("FIX: Should create version 2 by resolving dataset from valid IDs")
                    .hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.itemsTotal()).isEqualTo(5); // 10 - 5 = 5
            assertThat(version2.itemsDeleted()).isEqualTo(5);
        }

        @Test
        @DisplayName("Bug OPIK-3894: After first batch deletes items, second batch with same batch_group_id but deleted IDs fails")
        void deleteItems_whenSecondBatchContainsAlreadyDeletedIds_thenSecondBatchFails() {
            // Given - Create dataset with 20 items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 20);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(20);

            // Get items from version 1 - save their stable dataset_item_ids
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 30, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(20);

            var allItemIds = v1Items.stream()
                    .map(DatasetItem::datasetItemId)
                    .collect(Collectors.toList());

            // Simulate SDK batching: same batch_group_id for related batches
            var batchGroupId = UUID.randomUUID();

            // When - Batch 1: Delete first 10 items (simulating partial deletion of 2500 items)
            var batch1Ids = new HashSet<>(allItemIds.subList(0, 10));
            var deleteRequest1 = DatasetItemsDelete.builder()
                    .itemIds(batch1Ids)
                    .batchGroupId(batchGroupId)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest1, TEST_WORKSPACE, API_KEY);

            // Version 2 created with 10 items deleted
            var version2 = getLatestVersion(datasetId);
            assertThat(version2.itemsTotal()).isEqualTo(10);
            assertThat(version2.itemsDeleted()).isEqualTo(10);

            // When - Batch 2: SDK retries with SAME IDs (already deleted) + new IDs
            // This simulates SDK bug where it resends some already-deleted IDs
            var batch2Ids = new java.util.HashSet<UUID>();
            batch2Ids.addAll(allItemIds.subList(5, 10)); // 5 already deleted
            batch2Ids.addAll(allItemIds.subList(10, 15)); // 5 new items to delete

            var deleteRequest2 = DatasetItemsDelete.builder()
                    .itemIds(batch2Ids) // Mix of deleted + valid IDs
                    .batchGroupId(batchGroupId) // SAME batch_group_id
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest2, TEST_WORKSPACE, API_KEY);

            // Then - Version 2 should be updated with 5 more items deleted (the valid ones)
            var version2Updated = getLatestVersion(datasetId);
            assertThat(version2Updated.id()).isEqualTo(version2.id()); // Same version
            assertThat(version2Updated.itemsTotal())
                    .as("Should have 5 items remaining (20 - 10 - 5)")
                    .isEqualTo(5);
            assertThat(version2Updated.itemsDeleted())
                    .as("Should show 15 total items deleted (10 + 5)")
                    .isEqualTo(15);

            // Verify actual items
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 30, version2Updated.versionHash(), API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(5);
        }

        @Test
        @DisplayName("Bug OPIK-3894: Complete ticket reproduction - old SDK update, new SDK insert, batched delete")
        void deleteItems_whenCompleteTicketScenario_thenAllOperationsSucceed() {
            // STEP 1: Create dataset with initial items
            var datasetId = createDataset(UUID.randomUUID().toString());
            createDatasetItems(datasetId, 100);

            var version1 = getLatestVersion(datasetId);
            assertThat(version1.itemsTotal()).isEqualTo(100);

            // STEP 2: Update via OLD SDK (<0.83) - no batch_group_id, mutates latest version
            // Use helper method without batch_group_id to simulate old SDK
            createDatasetItemsWithoutBatchGroupId(datasetId, 1);

            // Verify STILL version 1 (mutated in-place)
            var versionsAfterUpdate = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfterUpdate.content()).hasSize(1);
            assertThat(versionsAfterUpdate.content().get(0).itemsTotal()).isEqualTo(101);

            // STEP 3: Insert 150 items with NEW SDK (with batch_group_id) - creates version 2
            createDatasetItems(datasetId, 150);

            var versionsAfterInsert = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfterInsert.content()).hasSize(2);

            var version2 = getLatestVersion(datasetId);
            assertThat(version2.itemsTotal()).isEqualTo(251); // 101 + 150

            // STEP 4: Delete 300 items with NEW SDK in batches (SDK sends row IDs)
            // Note: We only have 251 items, so SDK will send all 251 row IDs in batches
            var allItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 300, version2.versionHash(), API_KEY, TEST_WORKSPACE).content();

            var allRowIds = allItems.stream()
                    .map(DatasetItem::id) // SDK sends row IDs, not dataset_item_ids
                    .collect(Collectors.toList());

            // batches: 100, 151 (non-overlapping batches with same batch_group_id)
            var batchGroupId2 = UUID.randomUUID();

            // Batch 1: First 1000 items
            var batch1Ids = new HashSet<>(allRowIds.subList(0, 100));
            var deleteRequest1 = DatasetItemsDelete.builder()
                    .itemIds(batch1Ids)
                    .batchGroupId(batchGroupId2)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest1, TEST_WORKSPACE, API_KEY);

            // Verify version 3 created
            var versionsAfterBatch1 = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfterBatch1.content()).hasSize(3);

            var version3 = getLatestVersion(datasetId);
            assertThat(version3.itemsDeleted()).isEqualTo(100);
            assertThat(version3.itemsTotal()).isEqualTo(151);

            // Batch 2: Remaining 151 items (SAME batch_group_id - should append to version 3)
            var batch2Ids = new HashSet<>(allRowIds.subList(100, allRowIds.size()));
            var deleteRequest2 = DatasetItemsDelete.builder()
                    .itemIds(batch2Ids)
                    .batchGroupId(batchGroupId2)
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest2, TEST_WORKSPACE, API_KEY);

            // Verify STILL version 3 (appended)
            var versionsAfterBatch2 = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfterBatch2.content()).hasSize(3);

            var version3Updated = getLatestVersion(datasetId);
            assertThat(version3Updated.id()).isEqualTo(version3.id());
            assertThat(version3Updated.itemsDeleted()).isEqualTo(251);
            assertThat(version3Updated.itemsTotal()).isEqualTo(0);

            // STEP 5: Try another deletion with new batch_group_id (no items left)
            var deleteRequest3 = DatasetItemsDelete.builder()
                    .itemIds(Set.of(UUID.randomUUID(), UUID.randomUUID()))
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.deleteDatasetItems(deleteRequest3, TEST_WORKSPACE, API_KEY);

            // Should not create new version (no items to delete)
            var versionsAfterDelete3 = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            assertThat(versionsAfterDelete3.content()).hasSize(3);

            // STEP 6: Try dataset.clear() (no items left)
            var clearRequest = DatasetItemsDelete.builder()
                    .datasetId(datasetId)
                    .filters(List.of())
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.deleteDatasetItems(clearRequest, TEST_WORKSPACE, API_KEY);

            var finalVersion = getLatestVersion(datasetId);
            assertThat(finalVersion.itemsTotal()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Evaluators and Execution Policy:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class EvaluatorsAndExecutionPolicy {

        @Test
        @DisplayName("Success: Create items with evaluators and executionPolicy, then read them back")
        void createItems__whenEvaluatorsAndExecutionPolicy__thenFieldsReturned() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var evaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build(),
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.CODE_METRIC)
                            .config(JsonUtils.getJsonNodeFromString("{\"threshold\":0.5}"))
                            .build());

            var executionPolicy = ExecutionPolicy.builder()
                    .runsPerItem(3)
                    .passThreshold(2)
                    .build();

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .evaluators(evaluators)
                    .executionPolicy(executionPolicy)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Read back
            var returnedItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "latest", API_KEY, TEST_WORKSPACE).content();
            assertThat(returnedItems).hasSize(1);

            var returnedItem = returnedItems.getFirst();
            assertThat(returnedItem.evaluators()).isEqualTo(evaluators);
            assertThat(returnedItem.executionPolicy()).isEqualTo(executionPolicy);
        }

        @Test
        @DisplayName("Success: Editing only evaluators bumps dataset version as modified")
        void applyChanges__whenOnlyEvaluatorsChanged__thenItemIsModified() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var originalEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build());

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .evaluators(originalEvaluators)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(1);

            // Edit only evaluators — data stays the same
            var newEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.CODE_METRIC)
                            .config(JsonUtils.getJsonNodeFromString("{\"threshold\":0.8}"))
                            .build());

            var editedItem = DatasetItemEdit.builder()
                    .id(v1Items.getFirst().id())
                    .evaluators(newEvaluators)
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .editedItems(List.of(editedItem))
                    .tags(List.of("v2"))
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            assertThat(version2.itemsTotal()).isEqualTo(1);
            assertThat(version2.itemsModified()).isEqualTo(1);
            assertThat(version2.itemsAdded()).isEqualTo(0);
            assertThat(version2.itemsDeleted()).isEqualTo(0);

            // Verify the evaluator was actually updated
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items.getFirst().evaluators()).isEqualTo(newEvaluators);
        }

        @Test
        @DisplayName("Success: Editing only executionPolicy bumps dataset version as modified")
        void applyChanges__whenOnlyExecutionPolicyChanged__thenItemIsModified() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var originalPolicy = ExecutionPolicy.builder()
                    .runsPerItem(1)
                    .passThreshold(1)
                    .build();

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .executionPolicy(originalPolicy)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(1);

            // Edit only executionPolicy — data stays the same
            var newPolicy = ExecutionPolicy.builder()
                    .runsPerItem(5)
                    .passThreshold(3)
                    .build();

            var editedItem = DatasetItemEdit.builder()
                    .id(v1Items.getFirst().id())
                    .executionPolicy(newPolicy)
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .editedItems(List.of(editedItem))
                    .tags(List.of("v2"))
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            assertThat(version2.itemsTotal()).isEqualTo(1);
            assertThat(version2.itemsModified()).isEqualTo(1);
            assertThat(version2.itemsAdded()).isEqualTo(0);
            assertThat(version2.itemsDeleted()).isEqualTo(0);

            // Verify the execution policy was actually updated
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items.getFirst().executionPolicy()).isEqualTo(newPolicy);
        }

        @Test
        @DisplayName("Success: Batch update evaluators and executionPolicy on items")
        void batchUpdate__whenEvaluatorsAndExecutionPolicy__thenFieldsUpdated() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();

            var newEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build());
            var newPolicy = ExecutionPolicy.builder()
                    .runsPerItem(4)
                    .passThreshold(2)
                    .build();

            var batchUpdate = DatasetItemBatchUpdate.builder()
                    .ids(Set.of(v1Items.getFirst().id()))
                    .update(DatasetItemUpdate.builder()
                            .evaluators(newEvaluators)
                            .executionPolicy(newPolicy)
                            .build())
                    .build();
            datasetResourceClient.batchUpdateDatasetItems(batchUpdate, API_KEY, TEST_WORKSPACE);

            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(1);

            var updatedItem = latestItems.getFirst();
            assertThat(updatedItem.evaluators()).isEqualTo(newEvaluators);
            assertThat(updatedItem.executionPolicy()).isEqualTo(newPolicy);
        }

        @Test
        @DisplayName("Error: Create items with invalid executionPolicy (runsPerItem > 100) should be rejected")
        void createItems__whenInvalidExecutionPolicy__thenReturn422() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var invalidPolicy = ExecutionPolicy.builder()
                    .runsPerItem(999)
                    .passThreshold(1)
                    .build();

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"test\"")))
                    .executionPolicy(invalidPolicy)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();

            try (var response = datasetResourceClient.callCreateDatasetItems(batch, TEST_WORKSPACE, API_KEY)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(422);
            }
        }

        @Test
        @DisplayName("Success: Apply changes with version-level evaluators and executionPolicy, then fetch version")
        void applyChanges__whenVersionLevelEvaluatorsAndPolicy__thenVersionFieldsReturned() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create initial items to get a base version
            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);

            // Apply changes with version-level evaluators and executionPolicy
            var versionEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build(),
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.CODE_METRIC)
                            .config(JsonUtils.getJsonNodeFromString("{\"threshold\":0.5}"))
                            .build());

            var versionPolicy = ExecutionPolicy.builder()
                    .runsPerItem(3)
                    .passThreshold(2)
                    .build();

            var newItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .evaluators(versionEvaluators)
                    .executionPolicy(versionPolicy)
                    .tags(List.of("with-evaluators"))
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            assertThat(version2.evaluators()).isEqualTo(versionEvaluators);
            assertThat(version2.executionPolicy()).isEqualTo(versionPolicy);

            // Also verify via list versions API
            var versions = datasetResourceClient.listVersions(datasetId, API_KEY, TEST_WORKSPACE);
            var latestVersion = versions.content().getFirst();
            assertThat(latestVersion.evaluators()).isEqualTo(versionEvaluators);
            assertThat(latestVersion.executionPolicy()).isEqualTo(versionPolicy);
        }

        @Test
        @DisplayName("Success: Version-level evaluators/executionPolicy carry forward when bumping via item change")
        void applyChanges__whenBumpWithoutEvaluators__thenCarriedForwardFromBase() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            // Create initial items to get a base version
            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);

            // Set evaluators/executionPolicy on version via apply changes
            var versionEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build());

            var versionPolicy = ExecutionPolicy.builder()
                    .runsPerItem(5)
                    .passThreshold(3)
                    .build();

            var newItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes1 = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .evaluators(versionEvaluators)
                    .executionPolicy(versionPolicy)
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes1, false, API_KEY, TEST_WORKSPACE);

            assertThat(version2.evaluators()).isEqualTo(versionEvaluators);
            assertThat(version2.executionPolicy()).isEqualTo(versionPolicy);

            // Now bump again WITHOUT setting evaluators/executionPolicy — they should carry forward
            var anotherItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes2 = DatasetItemChanges.builder()
                    .baseVersion(version2.id())
                    .addedItems(List.of(anotherItem))
                    .build();

            var version3 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes2, false, API_KEY, TEST_WORKSPACE);

            assertThat(version3.evaluators()).isEqualTo(versionEvaluators);
            assertThat(version3.executionPolicy()).isEqualTo(versionPolicy);
        }

        @Test
        @DisplayName("Success: clearExecutionPolicy removes item-level execution policy")
        void applyChanges__whenClearExecutionPolicy__thenPolicyIsRemoved() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var originalPolicy = ExecutionPolicy.builder()
                    .runsPerItem(5)
                    .passThreshold(3)
                    .build();

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .executionPolicy(originalPolicy)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(1);
            assertThat(v1Items.getFirst().executionPolicy()).isEqualTo(originalPolicy);

            // Edit with clearExecutionPolicy=true
            var editedItem = DatasetItemEdit.builder()
                    .id(v1Items.getFirst().id())
                    .clearExecutionPolicy(true)
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .editedItems(List.of(editedItem))
                    .tags(List.of("v2"))
                    .build();

            datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(1);
            assertThat(v2Items.getFirst().executionPolicy()).isNull();
        }

        @Test
        @DisplayName("Success: clearExecutionPolicy removes version-level execution policy")
        void applyChanges__whenClearExecutionPolicyOnVersion__thenVersionPolicyIsRemoved() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);

            // Set execution policy on version
            var versionPolicy = ExecutionPolicy.builder()
                    .runsPerItem(5)
                    .passThreshold(3)
                    .build();

            var newItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes1 = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .executionPolicy(versionPolicy)
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes1, false, API_KEY, TEST_WORKSPACE);
            assertThat(version2.executionPolicy()).isEqualTo(versionPolicy);

            // Now clear the version-level execution policy
            var anotherItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes2 = DatasetItemChanges.builder()
                    .baseVersion(version2.id())
                    .addedItems(List.of(anotherItem))
                    .clearExecutionPolicy(true)
                    .build();

            var version3 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes2, false, API_KEY, TEST_WORKSPACE);
            assertThat(version3.executionPolicy()).isNull();
        }

        @Test
        @DisplayName("Success: empty evaluators list on item clears evaluators")
        void applyChanges__whenEmptyEvaluatorsOnItem__thenEvaluatorsIsNull() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var originalEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build());

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .evaluators(originalEvaluators)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(1);
            assertThat(v1Items.getFirst().evaluators()).isEqualTo(originalEvaluators);

            // Edit with empty evaluators list to clear them
            var editedItem = DatasetItemEdit.builder()
                    .id(v1Items.getFirst().id())
                    .evaluators(List.of())
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .editedItems(List.of(editedItem))
                    .tags(List.of("v2"))
                    .build();

            datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items).hasSize(1);
            assertThat(v2Items.getFirst().evaluators()).isNull();
        }

        @Test
        @DisplayName("Success: empty evaluators list on version clears evaluators")
        void applyChanges__whenEmptyEvaluatorsOnVersion__thenVersionEvaluatorsIsNull() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);

            // Set evaluators on version
            var versionEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build());

            var newItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes1 = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .addedItems(List.of(newItem))
                    .evaluators(versionEvaluators)
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes1, false, API_KEY, TEST_WORKSPACE);
            assertThat(version2.evaluators()).isEqualTo(versionEvaluators);

            // Now pass empty evaluators list to clear them
            var anotherItem = DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build();

            var changes2 = DatasetItemChanges.builder()
                    .baseVersion(version2.id())
                    .addedItems(List.of(anotherItem))
                    .evaluators(List.of())
                    .build();

            var version3 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes2, false, API_KEY, TEST_WORKSPACE);
            assertThat(version3.evaluators()).isNull();
        }

        @Test
        @DisplayName("Success: Apply changes with null baseVersion creates first version with evaluators and executionPolicy")
        void applyChanges__whenNullBaseVersion__thenFirstVersionCreatedWithEvaluatorsAndPolicy() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var versionEvaluators = List.of(
                    EvaluatorItem.builder()
                            .name(UUID.randomUUID().toString())
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-4\"}"))
                            .build());

            var versionPolicy = ExecutionPolicy.builder()
                    .runsPerItem(3)
                    .passThreshold(2)
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(null)
                    .evaluators(versionEvaluators)
                    .executionPolicy(versionPolicy)
                    .tags(List.of("initial"))
                    .changeDescription("First version with evaluators")
                    .build();

            var version1 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, true, API_KEY, TEST_WORKSPACE);

            assertThat(version1).isNotNull();
            assertThat(version1.itemsTotal()).isZero();
            assertThat(version1.evaluators()).isEqualTo(versionEvaluators);
            assertThat(version1.executionPolicy()).isEqualTo(versionPolicy);
            assertThat(version1.tags()).contains("initial", DatasetVersionService.LATEST_TAG);
        }
    }

    @Nested
    @DisplayName("Description Field:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DescriptionField {

        @Test
        @DisplayName("Success: Create items with description, then read them back")
        void createItems__whenDescription__thenFieldReturned() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var description = "This is a test case description for " + UUID.randomUUID();

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .description(description)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            // Read back
            var returnedItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "latest", API_KEY, TEST_WORKSPACE).content();
            assertThat(returnedItems).hasSize(1);

            var returnedItem = returnedItems.getFirst();
            var expectedItem = items.getFirst().toBuilder()
                    .id(returnedItem.id())
                    .build();
            assertThat(returnedItem)
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS_DATA_ITEM)
                    .isEqualTo(expectedItem);
        }

        @Test
        @DisplayName("Success: Editing only description bumps dataset version as modified")
        void applyChanges__whenOnlyDescriptionChanged__thenItemIsModified() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var originalDescription = "Original description " + UUID.randomUUID();

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .description(originalDescription)
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            datasetResourceClient.createVersionTag(datasetId, version1.versionHash(),
                    DatasetVersionTag.builder().tag("v1").build(), API_KEY, TEST_WORKSPACE);

            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v1", API_KEY, TEST_WORKSPACE).content();
            assertThat(v1Items).hasSize(1);
            assertThat(v1Items.getFirst().description()).isEqualTo(originalDescription);

            // Edit only description — data stays the same
            var newDescription = "Updated description " + UUID.randomUUID();

            var editedItem = DatasetItemEdit.builder()
                    .id(v1Items.getFirst().id())
                    .description(newDescription)
                    .build();

            var changes = DatasetItemChanges.builder()
                    .baseVersion(version1.id())
                    .editedItems(List.of(editedItem))
                    .tags(List.of("v2"))
                    .build();

            var version2 = datasetResourceClient.applyDatasetItemChanges(
                    datasetId, changes, false, API_KEY, TEST_WORKSPACE);

            assertThat(version2.itemsTotal()).isEqualTo(1);
            assertThat(version2.itemsModified()).isEqualTo(1);
            assertThat(version2.itemsAdded()).isEqualTo(0);
            assertThat(version2.itemsDeleted()).isEqualTo(0);

            // Verify the description was actually updated
            var v2Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, "v2", API_KEY, TEST_WORKSPACE).content();
            assertThat(v2Items.getFirst().description()).isEqualTo(newDescription);
        }

        @Test
        @DisplayName("Success: Batch update description on items")
        void batchUpdate__whenDescription__thenFieldUpdated() {
            var datasetId = createDataset(UUID.randomUUID().toString());

            var items = List.of(DatasetItem.builder()
                    .source(DatasetItemSource.SDK)
                    .data(Map.of("input", JsonUtils.getJsonNodeFromString("\"" + UUID.randomUUID() + "\"")))
                    .build());

            var batch = DatasetItemBatch.builder()
                    .datasetId(datasetId)
                    .items(items)
                    .batchGroupId(UUID.randomUUID())
                    .build();
            datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

            var version1 = getLatestVersion(datasetId);
            var v1Items = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, version1.versionHash(), API_KEY, TEST_WORKSPACE).content();

            var newDescription = "Batch updated description " + UUID.randomUUID();

            var batchUpdate = DatasetItemBatchUpdate.builder()
                    .ids(Set.of(v1Items.getFirst().id()))
                    .update(DatasetItemUpdate.builder()
                            .description(newDescription)
                            .build())
                    .build();
            datasetResourceClient.batchUpdateDatasetItems(batchUpdate, API_KEY, TEST_WORKSPACE);

            var latestItems = datasetResourceClient.getDatasetItems(
                    datasetId, 1, 10, DatasetVersionService.LATEST_TAG, API_KEY, TEST_WORKSPACE).content();
            assertThat(latestItems).hasSize(1);

            var updatedItem = latestItems.getFirst();
            assertThat(updatedItem.description()).isEqualTo(newDescription);
        }
    }
}
