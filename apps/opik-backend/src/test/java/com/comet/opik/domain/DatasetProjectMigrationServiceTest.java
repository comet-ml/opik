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
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-level coverage for all classification/policy cases in isolation by driving
 * {@code migrationService.runMigrationCycle().block()} directly. Faster and less flaky than the
 * scheduler-driven {@code DatasetProjectMigrationJobTest}, which only covers the happy path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetProjectMigrationServiceTest {

    private static final String EXCLUDED_WORKSPACE_ID_1 = UUID.randomUUID().toString();
    private static final String EXCLUDED_WORKSPACE_ID_2 = UUID.randomUUID().toString();

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
                                new CustomConfig("migration.excludedWorkspaceIds",
                                        "%s,%s".formatted(EXCLUDED_WORKSPACE_ID_1, EXCLUDED_WORKSPACE_ID_2)),
                                // Cache enabled with the production TTL so only the migration's
                                // evictCache can flip a cached V1 to V2 between assertions.
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
    private DatasetProjectMigrationService migrationService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, DatasetProjectMigrationService migrationService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        workspaceResourceClient = new WorkspaceResourceClient(clientSupport, baseUrl, factory);
        this.migrationService = migrationService;
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

        // Prime the workspace_version cache to V1 before triggering the cycle. The post-cycle V2
        // read must then depend on evictWorkspaceVersionCache actually clearing the cached V1.
        assertWorkspaceVersion(apiKeyA, workspaceNameA, OpikVersion.VERSION_1);
        assertWorkspaceVersion(apiKeyB, workspaceNameB, OpikVersion.VERSION_1);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKeyA, workspaceNameA, OpikVersion.VERSION_2);
        assertWorkspaceVersion(apiKeyB, workspaceNameB, OpikVersion.VERSION_2);

        assertDatasetMigrated(apiKeyA, workspaceNameA, datasetIdA, projectIdA);
        for (var id : datasetIdsB) {
            assertDatasetMigrated(apiKeyB, workspaceNameB, id, projectIdB);
        }
    }

    /**
     * Idempotency on re-run: once a workspace is migrated, a second cycle must not touch its
     * datasets again. Asserted via {@code lastUpdatedAt} stability between cycles.
     */
    @Test
    void secondCycleIsNoopAfterSuccessfulMigration() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var seeded = seedEligibleDataset(apiKey, workspaceName, randomName("project"));
        var datasetId = seeded.getLeft();
        var projectId = seeded.getRight();

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertDatasetMigrated(apiKey, workspaceName, datasetId, projectId);
        var afterFirstCycle = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);

        // Second cycle: no orphan candidates remain, BATCH_SET_PROJECT_ID also has a
        // `project_id IS NULL` guard. Row must be byte-for-byte stable.
        migrationService.runMigrationCycle().block();

        var afterSecondCycle = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(afterSecondCycle.projectId()).isEqualTo(projectId);
        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstCycle.lastUpdatedAt());
    }

    /**
     * One workspace exercising all 4 buckets: certain, ambiguous, certain-deleted, demo.
     * After the D1 policy alignment certain-deleted reroutes to Default Project (auto-created),
     * so only ambiguous + demo stay V1 and the workspace remains trapped on {@code all_ambiguous}.
     */
    @Test
    void mixedWorkspaceMigratesAcrossBucketsAndTrapsOnAmbiguous(WorkspacesService workspacesService) {
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

        migrationService.runMigrationCycle().block();

        // Ambiguous keeps the workspace pinned to V1; the cycle migrates the other three buckets
        // and traps the workspace with all_ambiguous.
        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);

        assertDatasetMigrated(apiKey, workspaceName, eligibleDatasetId, eligibleProjectId);
        assertDatasetUnchanged(apiKey, workspaceName, ambiguousDatasetId);
        var migratedDeleted = datasetResourceClient.getDatasetById(deletedDatasetId, apiKey, workspaceName);
        assertThat(migratedDeleted.projectId()).isNotNull().isNotEqualTo(eligibleProjectId);
        assertDatasetUnchanged(apiKey, workspaceName, demoDatasetId);

        assertWorkspaceTrapped(workspacesService, workspaceId, "all_ambiguous");
    }

    /**
     * Certain-deleted reroutes to Default Project (aligned with D1's policy via
     * {@code projectService.getOrCreate}). Workspace migrates to V2 instead of being trapped.
     */
    @Test
    void migrateDatasetWhenInferredProjectWasDeletedToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName);

        projectResourceClient.deleteProject(projectId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertDatasetMigrated(apiKey, workspaceName, datasetId, defaultProjectId);
    }

    /** No-inference orphan + Default Project pre-seeded → orphan migrates to Default Project. */
    @Test
    void migrateNoInferenceDatasetToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertDatasetMigrated(apiKey, workspaceName, datasetId, defaultProjectId);
    }

    /**
     * Reviewer scenario (PR #6799, Default Project exists): dataset whose experiments all have
     * {@code project_id=''} (D1-pending, or D1 left them ambiguous). The CH
     * {@code HAVING experiment_project_id != ''} filter drops those rows from
     * {@code COMPUTE_DATASET_PROJECT_MAPPING}'s output, so the dataset is absent from
     * {@code inferenceByDataset} and falls into the no-inference bucket → migrated to the
     * pre-seeded Default Project. Proves the empty-project-id rows do not slip through as
     * ambiguous or certain. Workspace version is intentionally not asserted: the V1 experiment
     * is still present and its V1→V2 promotion is D1's responsibility, not this job's.
     */
    @Test
    void migrateDatasetWhenAllExperimentsHaveEmptyProjectIdToExistingDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        // V1 experiment linked to the dataset by name (createPartialExperiment leaves projectId
        // and projectName null → project_id='' in ClickHouse, matching D1-pending state).
        experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment().id(null).datasetName(datasetName).build(),
                apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertDatasetMigrated(apiKey, workspaceName, datasetId, defaultProjectId);
    }

    /**
     * Reviewer scenario (PR #6799, no Default Project): same empty-{@code project_id} setup but
     * the workspace has no Default Project pre-seeded. The service auto-provisions it via
     * {@code projectService.getOrCreate} (per the D1 policy alignment) and migrates the dataset
     * there. Proves that the no-inference fallback works end-to-end even on a clean workspace.
     */
    @Test
    void migrateDatasetWhenAllExperimentsHaveEmptyProjectIdAutoCreatingDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment().id(null).datasetName(datasetName).build(),
                apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isNotNull();
        var defaultProject = projectResourceClient.getProject(actual.projectId(), apiKey, workspaceName);
        assertThat(defaultProject.name()).isEqualTo(ProjectService.DEFAULT_PROJECT);
    }

    /**
     * No-inference orphan in a workspace WITHOUT a Default Project: service auto-provisions
     * the Default Project via {@code projectService.getOrCreate} and migrates the dataset there.
     */
    @Test
    void migrateNoInferenceDatasetWhenDefaultProjectMissingByAutoCreating() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);

        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isNotNull();
        var defaultProject = projectResourceClient.getProject(actual.projectId(), apiKey, workspaceName);
        assertThat(defaultProject.name()).isEqualTo(ProjectService.DEFAULT_PROJECT);
    }

    /**
     * All orphans ambiguous (multi-project) → workspace trapped with {@code all_ambiguous}, the
     * only remaining trap reason after the D1 policy alignment. Default Project is intentionally
     * NOT pre-seeded — there are no certain-deleted or no-inference orphans, so
     * {@code getOrCreate} is never reached.
     */
    @Test
    void trapWorkspaceWhenAllAmbiguous(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName1 = randomName("project");
        var projectName2 = randomName("project");
        createProject(apiKey, workspaceName, projectName1);
        createProject(apiKey, workspaceName, projectName2);

        var datasetName = randomName("dataset");
        var datasetId = createOrphanDataset(apiKey, workspaceName, datasetName);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName1);
        seedTracedExperiment(apiKey, workspaceName, datasetName, projectName2);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);
        assertDatasetUnchanged(apiKey, workspaceName, datasetId);
        assertWorkspaceTrapped(workspacesService, workspaceId, "all_ambiguous");
    }

    @Test
    void skipPreMarkedTrappedWorkspaces(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        workspacesService.markDatasetProjectMigrationSkipped(workspaceId, "test-pre-marked-trap");

        var seeded = seedEligibleDataset(apiKey, workspaceName, randomName("project"));
        var datasetId = seeded.getLeft();

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);
        assertDatasetUnchanged(apiKey, workspaceName, datasetId);
    }

    @Test
    void skipExcludedWorkspaces() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, EXCLUDED_WORKSPACE_ID_1, randomName("user"));

        var seeded = seedEligibleDataset(apiKey, workspaceName, randomName("project"));
        var datasetId = seeded.getLeft();

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);
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

    private void assertDatasetMigrated(String apiKey, String workspaceName, UUID datasetId, UUID expectedProjectId) {
        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isEqualTo(expectedProjectId);
    }

    private void assertDatasetUnchanged(String apiKey, String workspaceName, UUID datasetId) {
        var actual = datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName);
        assertThat(actual.projectId()).isNull();
    }

    private void assertWorkspaceTrapped(WorkspacesService workspacesService, String workspaceId, String reason) {
        assertThat(workspacesService.findDatasetProjectMigrationSkippedWorkspaceIds()).contains(workspaceId);
        assertThat(workspacesService.findById(workspaceId))
                .hasValueSatisfying(w -> assertThat(w.datasetProjectMigrationSkipReason()).isEqualTo(reason));
    }
}
