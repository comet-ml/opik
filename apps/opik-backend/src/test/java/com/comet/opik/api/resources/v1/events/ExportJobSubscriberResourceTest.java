package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetExportParams;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.ExportJob;
import com.comet.opik.api.ExportStatus;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.domain.CsvExportService;
import com.comet.opik.domain.ExportJobService;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.ExportConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.test.StepVerifier;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link ExportJobSubscriber} using real Redis, MySQL, and ClickHouse containers.
 * Tests verify end-to-end processing of dataset export jobs through the Redis stream.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Export Job Subscriber Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExportJobSubscriberResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "test-workspace";

    private static final int AWAIT_TIMEOUT_SECONDS = 30;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DatasetResourceClient datasetResourceClient;
    private ExportJobService exportJobService;
    private CsvExportService csvExportService;
    private OpikConfiguration opikConfig;
    private FileService fileService;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER, MINIO).join();

        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }

    @BeforeAll
    void setUpAll(ClientSupport client, ExportJobService exportJobService,
            CsvExportService csvExportService, OpikConfiguration opikConfig, FileService fileService) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.exportJobService = exportJobService;
        this.csvExportService = csvExportService;
        this.opikConfig = opikConfig;
        this.fileService = fileService;

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        datasetResourceClient = new DatasetResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Success Tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SuccessTests {

        @Test
        @Disabled
        @DisplayName("should process export job successfully when dataset has items and verify CSV content")
        void shouldProcessExportJobSuccessfully_whenDatasetHasItems() {
            // Given - Create a dataset with items and track expected columns and row count
            Set<String> expectedColumns = Set.of("col1", "col2", "col3");
            int expectedRowCount = 5;
            Dataset dataset = createDatasetWithItemsAndColumns(expectedColumns, expectedRowCount);

            // When - Use CsvExportService to create job and publish to Redis stream
            ExportJob job = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(dataset.id()).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            UUID jobId = job.id();

            // Then - Wait for job to be processed and verify completion
            ExportJob completedJob = await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> exportJobService.getJob(jobId)
                            .contextWrite(ctx -> ctx
                                    .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                    .put(RequestContext.USER_NAME, USER))
                            .block(),
                            j -> j.status() == ExportStatus.COMPLETED);

            assertThat(completedJob.filePath()).isNotNull();
            assertThat(completedJob.filePath()).contains("exports/");
            assertThat(completedJob.filePath()).contains(dataset.id().toString());
            assertThat(completedJob.filePath()).endsWith(".csv");

            // Verify CSV content
            assertCsvFile(completedJob.filePath(), expectedColumns, expectedRowCount);
        }

        @Test
        @DisplayName("should process export job successfully for empty dataset")
        void shouldProcessExportJobSuccessfully_forEmptyDataset() {
            // Given - Create a dataset without items
            Dataset dataset = buildDataset().toBuilder()
                    .name("empty-dataset-" + UUID.randomUUID())
                    .build();
            datasetResourceClient.createDataset(dataset, API_KEY, WORKSPACE_NAME);

            // When - Use CsvExportService to create job and publish to Redis stream
            ExportJob job = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(dataset.id()).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            UUID jobId = job.id();

            // Then - Wait for job to be processed and verify completion
            ExportJob completedJob = await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> exportJobService.getJob(jobId)
                            .contextWrite(ctx -> ctx
                                    .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                    .put(RequestContext.USER_NAME, USER))
                            .block(),
                            j -> j.status() == ExportStatus.COMPLETED);

            assertThat(completedJob.filePath()).isNotNull();

            // Verify CSV is empty (no headers, no rows since no columns discovered)
            assertCsvFileIsEmpty(completedJob.filePath());
        }

        @Test
        @Disabled
        @DisplayName("should process multiple export jobs in parallel and verify each CSV")
        void shouldProcessMultipleExportJobsInParallel() {
            // Given - Create multiple datasets with different columns and row counts
            Set<String> columns1 = Set.of("a", "b");
            Set<String> columns2 = Set.of("x", "y", "z");
            Set<String> columns3 = Set.of("field1");
            int rowCount1 = 3;
            int rowCount2 = 5;
            int rowCount3 = 2;

            Dataset dataset1 = createDatasetWithItemsAndColumns(columns1, rowCount1);
            Dataset dataset2 = createDatasetWithItemsAndColumns(columns2, rowCount2);
            Dataset dataset3 = createDatasetWithItemsAndColumns(columns3, rowCount3);

            // When - Use CsvExportService to create jobs and publish to Redis stream
            ExportJob job1 = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(dataset1.id()).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            ExportJob job2 = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(dataset2.id()).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            ExportJob job3 = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(dataset3.id()).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            // Then - All jobs should complete successfully and verify CSV content
            ExportJob completedJob1 = await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> exportJobService.getJob(job1.id())
                            .contextWrite(ctx -> ctx
                                    .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                    .put(RequestContext.USER_NAME, USER))
                            .block(),
                            j -> j.status() == ExportStatus.COMPLETED);

            ExportJob completedJob2 = await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> exportJobService.getJob(job2.id())
                            .contextWrite(ctx -> ctx
                                    .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                    .put(RequestContext.USER_NAME, USER))
                            .block(),
                            j -> j.status() == ExportStatus.COMPLETED);

            ExportJob completedJob3 = await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> exportJobService.getJob(job3.id())
                            .contextWrite(ctx -> ctx
                                    .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                    .put(RequestContext.USER_NAME, USER))
                            .block(),
                            j -> j.status() == ExportStatus.COMPLETED);

            // Verify each CSV content
            assertCsvFile(completedJob1.filePath(), columns1, rowCount1);
            assertCsvFile(completedJob2.filePath(), columns2, rowCount2);
            assertCsvFile(completedJob3.filePath(), columns3, rowCount3);
        }

        @Test
        @Disabled
        @DisplayName("should process export job with large dataset and verify row count")
        void shouldProcessExportJobWithLargeDataset() {
            // Given - Create a dataset with many items
            Set<String> expectedColumns = Set.of("data1", "data2");
            int expectedRowCount = 50; // Larger dataset
            Dataset dataset = createDatasetWithItemsAndColumns(expectedColumns, expectedRowCount);

            // When - Use CsvExportService to create job and publish to Redis stream
            ExportJob job = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(dataset.id()).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            UUID jobId = job.id();

            // Then - Wait for job to be processed and verify completion
            ExportJob completedJob = await().atMost(AWAIT_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
                    .until(() -> exportJobService.getJob(jobId)
                            .contextWrite(ctx -> ctx
                                    .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                    .put(RequestContext.USER_NAME, USER))
                            .block(),
                            j -> j.status() == ExportStatus.COMPLETED);

            assertThat(completedJob.filePath()).isNotNull();

            // Verify CSV content
            assertCsvFile(completedJob.filePath(), expectedColumns, expectedRowCount);
        }

    }

    private Dataset buildDataset() {
        return DatasetResourceClient.buildDataset(podamFactory);
    }

    @Nested
    @DisplayName("Edge Case Tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class EdgeCaseTests {

        @Test
        @DisplayName("should complete export for non-existent dataset with empty file")
        void shouldCompleteExport_whenDatasetDoesNotExist() {
            // Given - A non-existent dataset ID
            UUID nonExistentDatasetId = UUID.randomUUID();

            // When - Use CsvExportService to start export for non-existent dataset
            // The export should complete successfully with an empty file (no columns, no items)
            ExportJob job = csvExportService
                    .startExport(DatasetExportParams.builder().datasetId(nonExistentDatasetId).build(), "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            UUID jobId = job.id();

            // Then - Job should complete successfully with an empty file
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        ExportJob completedJob = exportJobService.getJob(jobId)
                                .contextWrite(ctx -> ctx
                                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                                        .put(RequestContext.USER_NAME, USER))
                                .block();
                        assertThat(completedJob.status()).isEqualTo(ExportStatus.COMPLETED);
                        assertThat(completedJob.filePath()).isNotNull();
                    });
        }
    }

    @Nested
    @DisplayName("User Scope Tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UserScopeTests {

        private static final String OTHER_USER = "another-workspace-member";

        @Test
        @DisplayName("should not return another user's job by id")
        void getJob_shouldNotFindJobStartedByAnotherUser() {
            UUID jobId = startExportAs(USER).id();

            var otherUserRead = exportJobService.getJob(jobId)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, OTHER_USER));

            StepVerifier.create(otherUserRead)
                    .expectError(NotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("should list only the calling user's jobs")
        void findAllJobs_shouldReturnOnlyOwnJobs() {
            UUID ownJobId = startExportAs(USER).id();
            UUID otherJobId = startExportAs(OTHER_USER).id();

            List<ExportJob> ownJobs = exportJobService.findAllJobs()
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER))
                    .block();

            assertThat(ownJobs).extracting(ExportJob::id).contains(ownJobId).doesNotContain(otherJobId);
        }

        @Test
        @DisplayName("should not hand one user's in-progress job to another user")
        void startExport_shouldNotReuseAnotherUsersJob() {
            var params = DatasetExportParams.builder().datasetId(UUID.randomUUID()).build();

            ExportJob first = startExport(params, USER);
            ExportJob second = startExport(params, OTHER_USER);

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(second.createdBy()).isEqualTo(OTHER_USER);
        }

        private ExportJob startExportAs(String userName) {
            return startExport(DatasetExportParams.builder().datasetId(UUID.randomUUID()).build(), userName);
        }

        private ExportJob startExport(DatasetExportParams params, String userName) {
            return csvExportService.startExport(params, "test-dataset")
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, userName))
                    .block();
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ConfigurationTests {

        @Test
        @DisplayName("should verify subscriber is enabled by default")
        void shouldVerifySubscriberIsEnabled() {
            // Verify the subscriber is enabled in the test configuration
            ExportConfig config = opikConfig.getExportJobs();
            assertThat(config.isDatasetEnabled()).isTrue();
            assertThat(config.isExperimentItemsEnabled()).isTrue();
        }

        @Test
        @DisplayName("should verify stream configuration")
        void shouldVerifyStreamConfiguration() {
            // Get the ExportConfig from OpikConfiguration
            ExportConfig config = opikConfig.getExportJobs();
            assertThat(config.getStreamName()).isEqualTo("dataset-export-test");
            assertThat(config.getConsumerGroupName()).isNotNull();
            assertThat(config.getConsumerBatchSize()).isGreaterThan(0);
        }
    }

    // Helper methods

    /**
     * Asserts that the CSV file for a completed export job contains the expected headers and row count.
     *
     * @param filePath         The S3/MinIO file path of the exported CSV
     * @param expectedColumns  The expected column names (headers)
     * @param expectedRowCount The expected number of data rows (excluding header)
     */
    private void assertCsvFile(String filePath, Set<String> expectedColumns, int expectedRowCount) {
        try (InputStream csvStream = fileService.download(filePath);
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {

            // Read header line
            String headerLine = reader.readLine();
            assertThat(headerLine)
                    .as("CSV should have a header line")
                    .isNotNull();

            // Parse header columns
            String[] headers = headerLine.split(",");
            Set<String> actualHeaders = Set.of(headers);

            log.info("CSV headers for '{}': expected='{}', actual='{}'",
                    filePath, expectedColumns, actualHeaders);

            assertThat(actualHeaders)
                    .as("CSV headers should contain all expected columns")
                    .containsAll(expectedColumns);

            // Count data rows
            int rowCount = 0;
            while (reader.readLine() != null) {
                rowCount++;
            }

            assertThat(rowCount)
                    .as("CSV should have expected number of data rows")
                    .isEqualTo(expectedRowCount);

            log.info("CSV assertion passed for '{}': headerCount='{}', rowCount='{}'",
                    filePath, headers.length, rowCount);
        } catch (IOException e) {
            throw new AssertionError("Failed to read CSV file: " + filePath, e);
        }
    }

    /**
     * Asserts that the CSV file is empty (no headers, no data).
     *
     * @param filePath The S3/MinIO file path of the exported CSV
     */
    private void assertCsvFileIsEmpty(String filePath) {
        try (InputStream csvStream = fileService.download(filePath)) {
            byte[] content = csvStream.readAllBytes();
            assertThat(content.length)
                    .as("Empty dataset CSV should be empty or contain only whitespace")
                    .isLessThanOrEqualTo(1);
            log.info("Empty CSV assertion passed for '{}': size='{}' bytes", filePath, content.length);
        } catch (IOException e) {
            throw new AssertionError("Failed to read CSV file: " + filePath, e);
        }
    }

    private Dataset createDatasetWithItemsAndColumns(Set<String> columns, int rowCount) {
        // Create dataset
        Dataset dataset = buildDataset().toBuilder()
                .name("test-dataset-" + UUID.randomUUID())
                .build();

        datasetResourceClient.createDataset(dataset, API_KEY, WORKSPACE_NAME);

        // Create items with specified columns - values can be complex JSON objects
        List<DatasetItem> datasetItems = new ArrayList<>();

        for (int i = 0; i < rowCount; i++) {
            Map<String, JsonNode> data = columns.stream()
                    .collect(Collectors.toMap(
                            col -> col,
                            col -> podamFactory.manufacturePojo(JsonNode.class)));

            DatasetItem item = DatasetItem.builder()
                    .datasetId(dataset.id())
                    .source(DatasetItemSource.SDK)
                    .data(data)
                    .build();
            datasetItems.add(item);
        }

        datasetResourceClient.createDatasetItems(
                DatasetItemBatch.builder().datasetName(dataset.name()).items(datasetItems).build(),
                WORKSPACE_NAME,
                API_KEY);

        return dataset;
    }
}
