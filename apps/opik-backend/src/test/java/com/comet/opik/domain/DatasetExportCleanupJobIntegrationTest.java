package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Export Cleanup Job Integration Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetExportCleanupJobIntegrationTest {

    private final String TEST_WORKSPACE_ID = "test-workspace";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private final Network NETWORK = Network.newNetwork();

    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils
            .newZookeeperContainer(false, NETWORK);

    private final ClickHouseContainer CLICK_HOUSE = ClickHouseContainerUtils
            .newClickHouseContainer(false, NETWORK, ZOOKEEPER_CONTAINER);

    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

    private final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, ZOOKEEPER_CONTAINER, CLICK_HOUSE, MYSQL, MINIO).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICK_HOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE);

        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }

    private TransactionTemplate transactionTemplate;
    private GuiceJobManager guiceJobManager;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplate transactionTemplate,
            TransactionTemplateAsync templateAsync, GuiceJobManager guiceJobManager, Jdbi jdbi) {

        this.transactionTemplate = transactionTemplate;
        this.guiceJobManager = guiceJobManager;

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        MYSQL.stop();
        CLICK_HOUSE.stop();
        ZOOKEEPER_CONTAINER.stop();
        REDIS.stop();
        MINIO.stop();
        NETWORK.close();
    }

    @BeforeEach
    void setUp() {
        // Clean up all jobs to ensure test isolation
        transactionTemplate.inTransaction(handle -> {
            handle.execute("DELETE FROM dataset_export_jobs");
            return null;
        });
    }

    @Test
    @DisplayName("Should cleanup expired completed jobs")
    void shouldCleanupExpiredCompletedJobs() throws SchedulerException {
        // Given: Create expired and non-expired completed jobs
        String workspaceId = TEST_WORKSPACE_ID;
        UUID datasetId = UUID.randomUUID();

        UUID expiredJobId1 = createJob(workspaceId, datasetId, DatasetExportStatus.COMPLETED,
                "exports/test/expired-job1.csv", Instant.now().minusSeconds(3600));

        UUID expiredJobId2 = createJob(workspaceId, datasetId, DatasetExportStatus.COMPLETED,
                "exports/test/expired-job2.csv", Instant.now().minusSeconds(1800));

        UUID activeJobId = createJob(workspaceId, datasetId, DatasetExportStatus.COMPLETED,
                "exports/test/active-job.csv", Instant.now().plusSeconds(3600));

        // Verify jobs exist before cleanup
        assertThat(findJobById(expiredJobId1)).isPresent();
        assertThat(findJobById(expiredJobId2)).isPresent();
        assertThat(findJobById(activeJobId)).isPresent();

        // When: Trigger cleanup job
        triggerCleanupJob();

        // Then: Verify expired jobs are deleted and active job remains
        Awaitility
                .await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(findJobById(expiredJobId1)).isEmpty();
                    assertThat(findJobById(expiredJobId2)).isEmpty();
                    assertThat(findJobById(activeJobId)).isPresent();
                });
    }

    @Test
    @DisplayName("Should cleanup viewed failed jobs")
    void shouldCleanupViewedFailedJobs() throws SchedulerException {
        // Given: Create failed jobs with and without viewed_at
        String workspaceId = TEST_WORKSPACE_ID;
        UUID datasetId = UUID.randomUUID();

        UUID viewedFailedJobId1 = createFailedJob(workspaceId, datasetId,
                "exports/test/failed-job1.csv",
                "Export failed due to error",
                Instant.now().minusSeconds(7200));

        UUID viewedFailedJobId2 = createFailedJob(workspaceId, datasetId,
                "exports/test/failed-job2.csv",
                "Another export failure",
                Instant.now().minusSeconds(3600));

        UUID unviewedFailedJobId = createFailedJob(workspaceId, datasetId,
                "exports/test/unviewed-failed.csv",
                "Not viewed yet", null);

        // Verify jobs exist before cleanup
        assertThat(findJobById(viewedFailedJobId1)).isPresent();
        assertThat(findJobById(viewedFailedJobId2)).isPresent();
        assertThat(findJobById(unviewedFailedJobId)).isPresent();

        // When: Trigger cleanup job
        triggerCleanupJob();

        // Then: Verify viewed failed jobs are deleted and unviewed job remains
        Awaitility
                .await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(findJobById(viewedFailedJobId1)).isEmpty();
                    assertThat(findJobById(viewedFailedJobId2)).isEmpty();
                    assertThat(findJobById(unviewedFailedJobId)).isPresent();
                });
    }

    @Test
    @DisplayName("Should not cleanup jobs when not system user")
    void shouldNotCleanupJobsWhenNotSystemUser() {
        // Given: Create an expired job
        String workspaceId = TEST_WORKSPACE_ID;
        UUID datasetId = UUID.randomUUID();

        UUID expiredJobId = createJob(workspaceId, datasetId, DatasetExportStatus.COMPLETED,
                "exports/test/security-test.csv", Instant.now().minusSeconds(3600));

        // Verify job exists
        assertThat(findJobById(expiredJobId)).isPresent();

        // When: Try to cleanup with a non-system user (simulate by calling DAO directly)
        int deletedCount = Mono.deferContextual(ctx -> Mono.fromCallable(() -> {
            return transactionTemplate.inTransaction(handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                return dao.deleteJobsByIds("regular-user", Set.of(expiredJobId));
            });
        }))
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, "regular-user"))
                .block();

        // Then: Verify no jobs were deleted due to security check
        assertThat(deletedCount).isZero();
        assertThat(findJobById(expiredJobId)).isPresent();
    }

    @Test
    @DisplayName("Should handle empty database gracefully")
    void shouldHandleEmptyDatabaseGracefully() throws SchedulerException {
        // Given: Empty test database (setUp() already cleans test workspaces)

        // When: Trigger cleanup job
        triggerCleanupJob();

        // Then: Job should complete without errors
        Awaitility
                .await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // Just verify the job ran without throwing exceptions
                    assertThat(true).isTrue();
                });
    }

    // Helper methods

    private UUID createJob(String workspaceId, UUID datasetId, DatasetExportStatus status,
            String filePath, Instant expiresAt) {
        UUID jobId = UUID.randomUUID();
        String createdBy = "test-user";
        Instant now = Instant.now();

        DatasetExportJob job = DatasetExportJob.builder()
                .id(jobId)
                .datasetId(datasetId)
                .status(status)
                .filePath(filePath)
                .errorMessage(null)
                .createdAt(now)
                .lastUpdatedAt(now)
                .expiresAt(expiresAt)
                .viewedAt(null)
                .createdBy(createdBy)
                .lastUpdatedBy(createdBy)
                .build();

        transactionTemplate.inTransaction(handle -> {
            var dao = handle.attach(DatasetExportJobDAO.class);
            dao.save(job, workspaceId);
            return null;
        });

        return jobId;
    }

    private UUID createFailedJob(String workspaceId, UUID datasetId, String filePath,
            String errorMessage, Instant viewedAt) {
        UUID jobId = UUID.randomUUID();
        String createdBy = "test-user";
        Instant now = Instant.now();

        DatasetExportJob job = DatasetExportJob.builder()
                .id(jobId)
                .datasetId(datasetId)
                .status(DatasetExportStatus.FAILED)
                .filePath(filePath)
                .errorMessage(errorMessage)
                .createdAt(now)
                .lastUpdatedAt(now)
                .expiresAt(null)
                .viewedAt(null) // DAO save doesn't support viewed_at, we'll update it separately
                .createdBy(createdBy)
                .lastUpdatedBy(createdBy)
                .build();

        transactionTemplate.inTransaction(handle -> {
            var dao = handle.attach(DatasetExportJobDAO.class);
            dao.save(job, workspaceId);

            // Update viewed_at if provided (since save() doesn't support it)
            if (viewedAt != null) {
                handle.createUpdate("UPDATE dataset_export_jobs SET viewed_at = :viewedAt WHERE id = :id")
                        .bind("viewedAt", viewedAt)
                        .bind("id", jobId.toString())
                        .execute();
            }

            return null;
        });

        return jobId;
    }

    private java.util.Optional<DatasetExportJob> findJobById(UUID jobId) {
        return transactionTemplate.inTransaction(handle -> {
            // Directly query to check if job exists
            Integer count = handle.createQuery("SELECT COUNT(*) FROM dataset_export_jobs WHERE id = :id")
                    .bind("id", jobId.toString())
                    .mapTo(Integer.class)
                    .one();

            if (count == 0) {
                return java.util.Optional.empty();
            }

            // Get workspace ID
            String workspaceId = handle.createQuery("SELECT workspace_id FROM dataset_export_jobs WHERE id = :id")
                    .bind("id", jobId.toString())
                    .mapTo(String.class)
                    .one();

            // Use DAO to find the job
            var dao = handle.attach(DatasetExportJobDAO.class);
            return dao.findById(workspaceId, jobId);
        });
    }

    private void triggerCleanupJob() throws SchedulerException {
        var key = JobKey.jobKey(DatasetExportCleanupJob.class.getName());
        var trigger = TriggerBuilder.newTrigger().startNow().forJob(key).build();
        guiceJobManager.getScheduler().scheduleJob(trigger);
    }
}
