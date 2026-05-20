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
import com.comet.opik.domain.workspaces.WorkspacesService;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetProjectMigrationJobTest {

    private static final String EXCLUDED_WORKSPACE_ID_1 = UUID.randomUUID().toString();
    private static final String EXCLUDED_WORKSPACE_ID_2 = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    // Reuse disabled: eligibility query is `WHERE project_id IS NULL`, which makes the
    // test sensitive to stale orphan datasets accumulated in a reused MySQL container —
    // they push the test's own workspace past the workspacesPerRun cap.
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer(
            ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

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
                                new CustomConfig("datasetProjectMigration.enabled", "true"),
                                // Buffer so the first cycle fires after the initial seed+prime.
                                new CustomConfig("datasetProjectMigration.startupDelay", "3s"),
                                new CustomConfig("migration.excludedWorkspaceIds",
                                        "%s,%s".formatted(EXCLUDED_WORKSPACE_ID_1, EXCLUDED_WORKSPACE_ID_2)),
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
    void migrateEligibleDatasetsAcrossWorkspaces() {
        // Workspace A: one eligible V1 dataset (smallest first per FIND_ELIGIBLE ordering).
        var apiKeyA = randomName("api-key");
        var workspaceNameA = randomName("workspace");
        var workspaceIdA = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyA, workspaceNameA, workspaceIdA, randomName("user"));
        var projectNameA = randomName("project");
        var seededA = seedEligibleDataset(apiKeyA, workspaceNameA, projectNameA);
        var datasetIdA = seededA.getLeft();
        var projectIdA = seededA.getRight();

        // Workspace B: two eligible V1 datasets each linked to a V2 experiment in the same project.
        var apiKeyB = randomName("api-key");
        var workspaceNameB = randomName("workspace");
        var workspaceIdB = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyB, workspaceNameB, workspaceIdB, randomName("user"));
        var projectNameB = randomName("project");
        var projectIdB = createProject(apiKeyB, workspaceNameB, projectNameB);
        var datasetIdsB = new ArrayList<UUID>();
        for (int i = 0; i < 2; i++) {
            var datasetName = randomName("dataset");
            var datasetId = createOrphanDataset(apiKeyB, workspaceNameB, datasetName);
            seedTracedExperiment(apiKeyB, workspaceNameB, datasetName, projectNameB);
            datasetIdsB.add(datasetId);
        }

        // Prime the workspace_version cache to V1 AFTER seeding (the workspace must have V1
        // entities for this read to return V1). Forces the post-migration V2 read to depend on
        // evictWorkspaceVersionCache actually clearing the cached V1.
        assertWorkspaceVersion(apiKeyA, workspaceNameA, OpikVersion.VERSION_1);
        assertWorkspaceVersion(apiKeyB, workspaceNameB, OpikVersion.VERSION_1);

        // A single cycle picks up both workspaces (workspacesPerRun=20 default) and
        // FIND_ELIGIBLE_DATASET_WORKSPACES orders smallest-first; both should reach V2.
        assertWorkspaceVersion2(apiKeyA, workspaceNameA);
        assertWorkspaceVersion2(apiKeyB, workspaceNameB);

        assertDatasetMigrated(apiKeyA, workspaceNameA, datasetIdA, projectIdA);
        for (var id : datasetIdsB) {
            assertDatasetMigrated(apiKeyB, workspaceNameB, id, projectIdB);
        }
    }

    /** One workspace exercising all 4 buckets: certain, ambiguous, certain-deleted, demo. */
    @Test
    void mixedWorkspaceMigratesEligibleAndKeepsNonEligibleAsV1() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Certain: one V2 experiment with a trace in a single alive project => migrates.
        var eligibleProjectName = randomName("project");
        var eligibleProjectId = createProject(apiKey, workspaceName, eligibleProjectName);
        var eligibleDatasetName = randomName("dataset");
        var eligibleDatasetId = createOrphanDataset(apiKey, workspaceName, eligibleDatasetName);
        seedTracedExperiment(apiKey, workspaceName, eligibleDatasetName, eligibleProjectName);

        // Ambiguous: two V2 experiments whose traces span two different projects.
        var ambiguousProjectName1 = randomName("project");
        var ambiguousProjectName2 = randomName("project");
        createProject(apiKey, workspaceName, ambiguousProjectName1);
        createProject(apiKey, workspaceName, ambiguousProjectName2);
        var ambiguousDatasetName = randomName("dataset");
        var ambiguousDatasetId = createOrphanDataset(apiKey, workspaceName, ambiguousDatasetName);
        seedTracedExperiment(apiKey, workspaceName, ambiguousDatasetName, ambiguousProjectName1);
        seedTracedExperiment(apiKey, workspaceName, ambiguousDatasetName, ambiguousProjectName2);

        // Certain-deleted: V2 experiment with a trace in a single project that gets deleted after seeding.
        var deletedProjectName = randomName("project");
        var deletedProjectId = createProject(apiKey, workspaceName, deletedProjectName);
        var deletedDatasetName = randomName("dataset");
        var deletedDatasetId = createOrphanDataset(apiKey, workspaceName, deletedDatasetName);
        seedTracedExperiment(apiKey, workspaceName, deletedDatasetName, deletedProjectName);
        projectResourceClient.deleteProject(deletedProjectId, apiKey, workspaceName);

        // Demo: dataset with a demo name + a demo-named experiment+trace referencing it.
        // Filtered out at the MySQL orphan lookup; never reaches CH inference.
        var demoDatasetName = DemoData.DATASETS.getFirst();
        var demoDatasetId = createOrphanDataset(apiKey, workspaceName, demoDatasetName);
        var demoExperimentId = experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .name(DemoData.EXPERIMENTS.getFirst())
                        .datasetName(demoDatasetName)
                        .projectName(eligibleProjectName)
                        .build(),
                apiKey, workspaceName);
        var demoTraceId = createTrace(apiKey, workspaceName, eligibleProjectName);
        linkExperimentToTraces(apiKey, workspaceName, demoExperimentId, demoTraceId);

        assertWorkspaceVersion1(apiKey, workspaceName);

        // Certain dataset migrates; ambiguous + certain-deleted + demo datasets stay V1.
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(
                () -> assertDatasetMigrated(apiKey, workspaceName, eligibleDatasetId, eligibleProjectId));
        assertDatasetUnchanged(apiKey, workspaceName, ambiguousDatasetId);
        assertDatasetUnchanged(apiKey, workspaceName, deletedDatasetId);
        assertDatasetUnchanged(apiKey, workspaceName, demoDatasetId);
    }

    @Test
    void skipDatasetWhenInferredProjectWasDeleted(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Workspace also has a Default Project so any phantom no-inference path doesn't preempt
        // the deleted_project trap. This test is specifically about the certain-deleted bucket.
        createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName);

        // Delete the project so the only orphan dataset's inferred project is gone.
        // This routes the dataset to certain-deleted → no migration possible → workspace trapped.
        projectResourceClient.deleteProject(projectId, apiKey, workspaceName);

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertDatasetUnchanged(apiKey, workspaceName, datasetId);

        // Trapped workspaces persist via the workspaces.dataset_migration_skipped_at column; the next
        // cycle reads that list to assemble its exclusion set, so the workspace must appear here.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(workspacesService.findDatasetProjectMigrationSkippedWorkspaceIds())
                        .contains(workspaceId));
    }

    /** No-inference orphan + Default Project → orphan migrates to Default Project. */
    @Test
    void migrateNoInferenceDatasetToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Default Project exists → no-inference orphans fall back here.
        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        // V1 dataset with no experiments at all.
        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);

        assertWorkspaceVersion2(apiKey, workspaceName);

        assertDatasetMigrated(apiKey, workspaceName, datasetId, defaultProjectId);
    }

    /** No-inference orphan + no Default Project → workspace trapped (default_project_missing). */
    @Test
    void trapWorkspaceWhenNoDefaultProject(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // V1 dataset with no experiments and no Default Project to fall back to.
        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertDatasetUnchanged(apiKey, workspaceName, datasetId);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(workspacesService.findDatasetProjectMigrationSkippedWorkspaceIds())
                        .contains(workspaceId));
    }

    /**
     * Mixed certain + no-inference with no Default Project: the certain dataset must migrate
     * even though the workspace will be trapped for the un-migratable no-inference orphan.
     * Regression test for a pre-trap bug where validated certain mappings were stranded V1.
     */
    @Test
    void migrateCertainEvenWhenDefaultProjectMissingForNoInference(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Certain bucket: dataset with a traced V2 experiment in a live project → migrates.
        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var certainDatasetName = randomName("dataset");
        var certainDatasetId = createOrphanDataset(apiKey, workspaceName, certainDatasetName);
        seedTracedExperiment(apiKey, workspaceName, certainDatasetName, projectName);

        // No-inference bucket: dataset with no experiments and no Default Project to fall back to.
        var noInferenceDatasetName = randomName("dataset");
        var noInferenceDatasetId = createOrphanDataset(apiKey, workspaceName, noInferenceDatasetName);

        // Certain dataset still gets migrated even though the workspace is about to be trapped.
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(
                () -> assertDatasetMigrated(apiKey, workspaceName, certainDatasetId, projectId));

        // No-inference dataset stays V1 (no Default Project as destination).
        assertDatasetUnchanged(apiKey, workspaceName, noInferenceDatasetId);

        // Workspace is trapped with default_project_missing so future cycles skip it.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(workspacesService.findDatasetProjectMigrationSkippedWorkspaceIds())
                        .contains(workspaceId));
    }

    /** All orphans ambiguous (multi-project) → workspace trapped (all_ambiguous). */
    @Test
    void trapWorkspaceWhenAllAmbiguous(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Default Project exists, so the trap reason is forced to be all_ambiguous, not
        // default_project_missing — this isolates the all_ambiguous code path.
        createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var projectName1 = randomName("project");
        var projectName2 = randomName("project");
        createProject(apiKey, workspaceName, projectName1);
        createProject(apiKey, workspaceName, projectName2);

        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName1);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName2);

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertDatasetUnchanged(apiKey, workspaceName, datasetId);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(workspacesService.findDatasetProjectMigrationSkippedWorkspaceIds())
                        .contains(workspaceId));
    }

    /** Re-running cycles after a successful migration must not touch the dataset again. */
    @Test
    void migrationIsIdempotent() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var seeded = seedEligibleDataset(apiKey, workspaceName, randomName("project"));
        var datasetId = seeded.getLeft();
        var projectId = seeded.getRight();

        assertWorkspaceVersion2(apiKey, workspaceName);
        assertDatasetMigrated(apiKey, workspaceName, datasetId, projectId);

        var afterFirstMigration = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);

        // Hold V2 for >2 migration cycles (interval=5s in config-test.yml) to prove the second
        // cycle doesn't touch the already-migrated dataset.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).during(15, TimeUnit.SECONDS).untilAsserted(
                () -> assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2));

        var afterSecondCycle = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(afterSecondCycle.projectId()).isEqualTo(projectId);
        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstMigration.lastUpdatedAt());
    }

    @Test
    void skipPreMarkedTrappedWorkspaces(WorkspacesService workspacesService) {
        // Pre-mark a workspace as skipped via the workspaces table BEFORE seeding any datasets,
        // because the migration scheduler is running concurrently and a cycle that fires between
        // seeding and marking would migrate the dataset out from under us. The mark first
        // guarantees that every cycle observed after data creation sees the workspace as trapped.
        // The cycle's exclusion set is the union of migration.excludedWorkspaceIds config and
        // findDatasetProjectMigrationSkippedWorkspaceIds(), so this workspace must be omitted —
        // proven by the eligible dataset never getting migrated.
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        workspacesService.markDatasetProjectMigrationSkipped(workspaceId, "test-pre-marked-trap");

        var seeded = seedEligibleDataset(apiKey, workspaceName, randomName("project"));
        var datasetId = seeded.getLeft();

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertDatasetUnchanged(apiKey, workspaceName, datasetId);
    }

    @Test
    void skipExcludedWorkspaces() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, EXCLUDED_WORKSPACE_ID_1, randomName("user"));

        var seeded = seedEligibleDataset(apiKey, workspaceName, randomName("project"));
        var datasetId = seeded.getLeft();

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertDatasetUnchanged(apiKey, workspaceName, datasetId);
    }

    /** @return (datasetId, projectId) for one eligible V1 dataset + V2 experiment + trace. */
    private Pair<UUID, UUID> seedEligibleDataset(String apiKey, String workspaceName, String projectName) {
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName);
        return Pair.of(datasetId, projectId);
    }

    // CH inference joins experiments → experiment_items → traces; an experiment without a
    // trace link yields no row, so the orphan falls into no-inference instead of certain.
    private void seedTracedExperiment(String apiKey, String workspaceName, String datasetName, String projectName) {
        var experimentId = createV2Experiment(apiKey, workspaceName, datasetName, projectName);
        var traceId = createTrace(apiKey, workspaceName, projectName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId, traceId);
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

    private void assertWorkspaceVersion2(String apiKey, String workspaceName) {
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS).untilAsserted(
                        () -> assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2));
    }

    private void assertWorkspaceVersion1(String apiKey, String workspaceName) {
        Awaitility.await().atMost(15, TimeUnit.SECONDS).during(12, TimeUnit.SECONDS).untilAsserted(
                () -> assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1));
    }

    private void assertDatasetMigrated(String apiKey, String workspaceName, UUID datasetId, UUID expectedProjectId) {
        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isEqualTo(expectedProjectId);
    }

    private void assertDatasetUnchanged(String apiKey, String workspaceName, UUID datasetId) {
        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isNull();
    }
}
