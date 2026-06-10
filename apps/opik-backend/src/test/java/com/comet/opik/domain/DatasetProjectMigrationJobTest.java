package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Happy-path E2E for the scheduled migration job: a single eligible workspace is seeded, the
 * scheduler fires {@code runMigrationCycle()}, and the workspace promotes from V1 to V2 with
 * its dataset pointing at the inferred project. All classification/policy cases are covered by
 * {@link DatasetProjectMigrationServiceTest}, which calls the service directly for speed and
 * isolation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetProjectMigrationJobTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                // Entity-based determination required so the migration's V1 -> V2 transition
                                // is observable.
                                new CustomConfig("serviceToggles.forceWorkspaceVersion", "disabled"),
                                new CustomConfig("datasetProjectMigration.enabled", "true"),
                                // Cache enabled with the production TTL so only the migration's
                                // evictCache can flip a cached V1 to V2 within the test windows.
                                new CustomConfig("cacheManager.enabled", "true"),
                                new CustomConfig("cacheManager.caches.workspace_version", "PT5M")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private WorkspaceResourceClient workspaceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        workspaceResourceClient = new WorkspaceResourceClient(clientSupport, baseUrl, factory);
    }

    @Test
    void scheduledJobMigratesEligibleWorkspaceToV2() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var seeded = seedEligibleDataset(apiKey, workspaceName, projectName);
        var datasetId = seeded.getLeft();
        var projectId = seeded.getRight();

        // Prime the workspace_version cache to V1 before the first migration cycle fires.
        // This forces the post-migration V2 read to depend on evictWorkspaceVersionCache
        // actually clearing the cached V1, exercising the evictCache path end-to-end.
        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);

        // The scheduler fires the job, the job invokes the cycle, the cycle promotes the
        // workspace. We don't assert which bucket — just that the wiring works end-to-end.
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(
                () -> assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2));
        assertDatasetMigrated(apiKey, workspaceName, datasetId, projectId);
    }

    private Pair<UUID, UUID> seedEligibleDataset(String apiKey, String workspaceName, String projectName) {
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        var experimentId = createV2Experiment(apiKey, workspaceName, datasetName, projectName);
        var traceId = createTrace(apiKey, workspaceName, projectName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId, traceId);
        return Pair.of(datasetId, projectId);
    }

    private String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    private UUID createProject(String apiKey, String workspaceName, String projectName) {
        return projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(),
                apiKey, workspaceName);
    }

    private UUID createOrphanDataset(String apiKey, String workspaceName, String datasetName) {
        return datasetResourceClient.createDataset(
                factory.manufacturePojo(Dataset.class).toBuilder()
                        .name(datasetName)
                        .projectId(null)
                        .projectName(null)
                        .build(),
                apiKey, workspaceName);
    }

    private UUID createV2Experiment(String apiKey, String workspaceName, String datasetName, String projectName) {
        return experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .datasetName(datasetName)
                        .projectName(projectName)
                        .build(),
                apiKey, workspaceName);
    }

    private UUID createTrace(String apiKey, String workspaceName, String projectName) {
        return traceResourceClient.createTrace(
                factory.manufacturePojo(Trace.class).toBuilder()
                        .id(null)
                        .projectName(projectName)
                        .projectId(null)
                        .startTime(Instant.now())
                        .feedbackScores(null)
                        .usage(null)
                        .build(),
                apiKey, workspaceName);
    }

    private void linkExperimentToTraces(String apiKey, String workspaceName, UUID experimentId, UUID... traceIds) {
        Set<ExperimentItem> items = Arrays.stream(traceIds)
                .map(traceId -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experimentId)
                        .traceId(traceId)
                        .feedbackScores(null)
                        .build())
                .collect(Collectors.toUnmodifiableSet());
        experimentResourceClient.createExperimentItem(items, apiKey, workspaceName);
    }

    private void assertWorkspaceVersion(String apiKey, String workspaceName, OpikVersion expected) {
        assertThat(workspaceResourceClient.getWorkspaceVersion(apiKey, workspaceName).opikVersion())
                .isEqualTo(expected);
    }

    private void assertDatasetMigrated(String apiKey, String workspaceName, UUID datasetId, UUID expectedProjectId) {
        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isEqualTo(expectedProjectId);
    }
}
