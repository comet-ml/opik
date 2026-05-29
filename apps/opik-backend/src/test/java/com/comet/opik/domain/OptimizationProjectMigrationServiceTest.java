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
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
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
 * Service-level coverage for the D3 optimization-project migration's classification cases. Drives
 * {@code migrationService.runMigrationCycle().block()} directly so each bucket can be exercised in
 * isolation, mirroring {@link DatasetProjectMigrationServiceTest}. The scheduler-driven
 * {@link OptimizationProjectMigrationJobTest} only covers the Path A happy path.
 *
 * <p>{@code allowBeforeDependencies=true} keeps the per-workspace D1/D2 readiness probe from
 * gating the assertions — the seeded data already reflects the post-D1/D2 production layout, and
 * leaving the override on isolates these tests from any latent V1 artefact in shared containers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationProjectMigrationServiceTest {

    private static final String EXCLUDED_WORKSPACE_ID = UUID.randomUUID().toString();

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
                                new CustomConfig("optimizationProjectMigration.allowBeforeDependencies", "true"),
                                new CustomConfig("migration.excludedWorkspaceIds", EXCLUDED_WORKSPACE_ID),
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
    private OptimizationResourceClient optimizationResourceClient;
    private TraceResourceClient traceResourceClient;
    private WorkspaceResourceClient workspaceResourceClient;
    private OptimizationProjectMigrationService migrationService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, OptimizationProjectMigrationService migrationService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        optimizationResourceClient = new OptimizationResourceClient(clientSupport, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        workspaceResourceClient = new WorkspaceResourceClient(clientSupport, baseUrl, factory);
        this.migrationService = migrationService;
    }

    /**
     * Path A happy path across multiple workspaces. Workspace A has one eligible orphan
     * optimization; Workspace B has two. Both end V2 with optimizations pointing at the
     * experiment's project.
     */
    @Test
    void migrateEligibleOptimizationsAcrossWorkspaces() {
        var apiKeyA = randomName("api-key");
        var workspaceNameA = randomName("workspace");
        var workspaceIdA = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyA, workspaceNameA, workspaceIdA, randomName("user"));
        var projectNameA = randomName("project");
        var seededA = seedEligibleOptimization(apiKeyA, workspaceNameA, projectNameA);
        var optimizationIdA = seededA.getLeft();
        var projectIdA = seededA.getRight();

        var apiKeyB = randomName("api-key");
        var workspaceNameB = randomName("workspace");
        var workspaceIdB = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyB, workspaceNameB, workspaceIdB, randomName("user"));
        var projectNameB = randomName("project");
        var projectIdB = createProject(apiKeyB, workspaceNameB, projectNameB);
        var optimizationIdsB = new ArrayList<UUID>();
        for (int i = 0; i < 2; i++) {
            var datasetName = randomName("dataset");
            createDatasetLinkedToProject(apiKeyB, workspaceNameB, datasetName, projectNameB);
            var optimizationId = createOrphanOptimization(apiKeyB, workspaceNameB, datasetName);
            seedExperimentForOptimization(apiKeyB, workspaceNameB, datasetName, projectNameB, optimizationId);
            optimizationIdsB.add(optimizationId);
        }

        // Workspace-version flip is not asserted here: V2 promotion only fires once every entity
        // type (datasets, experiments, prompts, optimizations) is V2, which is a multi-job
        // concern this test does not own. The optimization migration's job is to assign
        // project_ids to optimizations — that's what gets asserted directly.
        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKeyA, workspaceNameA, optimizationIdA, projectIdA);
        for (var id : optimizationIdsB) {
            assertOptimizationMigrated(apiKeyB, workspaceNameB, id, projectIdB);
        }
    }

    /**
     * Idempotency on re-run: BATCH_SET_PROJECT_ID guards with {@code WHERE project_id = ''}, so a
     * second cycle must not touch already-migrated optimizations. Asserted via {@code lastUpdatedAt}
     * stability between cycles.
     */
    @Test
    void secondCycleIsNoopAfterSuccessfulMigration() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var seeded = seedEligibleOptimization(apiKey, workspaceName, randomName("project"));
        var optimizationId = seeded.getLeft();
        var projectId = seeded.getRight();

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, projectId);
        var afterFirstCycle = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);

        migrationService.runMigrationCycle().block();

        var afterSecondCycle = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);
        assertThat(afterSecondCycle.projectId()).isEqualTo(projectId);
        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstCycle.lastUpdatedAt());
    }

    /**
     * Dominant-project assignment by count — the core of Option A. Three experiments in the
     * dominant project, one in the minority project. The dominant project wins. A second cycle
     * is a no-op (deterministic re-runs).
     */
    @Test
    void migrateMultiProjectOptimizationToDominantProjectByCount() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var minorityProjectName = randomName("project");
        createProject(apiKey, workspaceName, minorityProjectName);

        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, dominantProjectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);

        // 3 experiments in the dominant project, 1 in the minority project — all referencing the
        // same orphan optimization.
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, minorityProjectName, optimizationId);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, dominantProjectId);

        var afterFirstCycle = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);
        migrationService.runMigrationCycle().block();
        var afterSecondCycle = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);
        assertThat(afterSecondCycle.projectId()).isEqualTo(dominantProjectId);
        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstCycle.lastUpdatedAt());
    }

    /**
     * Dominant-project tiebreaker by recency (Decision §1 in the Notion doc): equal counts across
     * two projects, the project with the more recently updated experiment wins. A small pause
     * between seed calls keeps the {@code last_updated_at} values distinguishable at CH's
     * nanosecond precision.
     */
    @Test
    void migrateMultiProjectOptimizationToMostRecentProjectWhenCountTies() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var olderProjectName = randomName("project");
        createProject(apiKey, workspaceName, olderProjectName);
        var newerProjectName = randomName("project");
        var newerProjectId = createProject(apiKey, workspaceName, newerProjectName);

        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, newerProjectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);

        seedExperimentForOptimization(apiKey, workspaceName, datasetName, olderProjectName, optimizationId);
        TestUtils.waitForMillis(20);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, newerProjectName, optimizationId);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, newerProjectId);
    }

    /**
     * Multi-project orphan where one referencing experiment is itself still V1 ({@code project_id=''}):
     * the {@code HAVING experiment_project_id != ''} filter drops the empty-project row, so the
     * dominant tally is taken over only the V2 experiments. Workspace version is intentionally
     * not asserted — the residual V1 experiment is D1's responsibility.
     */
    @Test
    void multiProjectOptimizationIgnoresExperimentsWithEmptyProjectId() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var minorityProjectName = randomName("project");
        createProject(apiKey, workspaceName, minorityProjectName);

        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, dominantProjectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);

        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, minorityProjectName, optimizationId);
        // V1 experiment linked to the optimization (no projectName/projectId). The CH filter
        // {@code HAVING experiment_project_id != ''} drops this row, so the dominant tally is
        // taken across only the three V2 experiments above.
        experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .datasetName(datasetName)
                        .optimizationId(optimizationId)
                        .build(),
                apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, dominantProjectId);
    }

    /**
     * Multi-project optimization whose dominant project (the higher-count one the query chooses)
     * is deleted before the cycle runs. Validation drops the now-missing dominant project, so the
     * optimization falls back to the Default Project — it is not reassigned to the surviving
     * lower-count project.
     */
    @Test
    void migrateMultiProjectOptimizationToDefaultProjectWhenDominantProjectWasDeleted() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var minorityProjectName = randomName("project");
        createProject(apiKey, workspaceName, minorityProjectName);

        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, dominantProjectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);

        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, dominantProjectName, optimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, minorityProjectName, optimizationId);

        projectResourceClient.deleteProject(dominantProjectId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, defaultProjectId);
    }

    /**
     * Certain-deleted (Path A returns one project that has since been deleted) reroutes to Default
     * Project via {@code projectService.getOrCreate}.
     */
    @Test
    void migrateOptimizationWhenInferredProjectWasDeletedToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, projectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, projectName, optimizationId);

        projectResourceClient.deleteProject(projectId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, defaultProjectId);
    }

    /**
     * Path B fallback — no experiments reference the orphan optimization (Path A returns nothing),
     * but its dataset has a {@code project_id} (set by D2). The optimization migrates there.
     */
    @Test
    void migrateOptimizationViaPathBWhenNoExperimentsReferenceIt() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, projectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);
        // No experiment referencing the optimization → Path A returns nothing → Path B uses
        // datasets.project_id.

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, projectId);
    }

    /** No-inference orphan (Path A=∅, Path B=null) with Default Project pre-seeded → migrates there. */
    @Test
    void migrateNoInferenceOptimizationToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        var datasetName = randomName("dataset");
        // Dataset has no project link (Path B will return null) and no experiments reference the
        // optimization (Path A returns nothing).
        createOrphanDataset(apiKey, workspaceName, datasetName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, optimizationId, defaultProjectId);
    }

    /**
     * No-inference orphan in a workspace WITHOUT a Default Project: service auto-provisions the
     * Default Project via {@code projectService.getOrCreate} and migrates the optimization there.
     */
    @Test
    void migrateNoInferenceOptimizationWhenDefaultProjectMissingByAutoCreating() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var datasetName = randomName("dataset");
        createOrphanDataset(apiKey, workspaceName, datasetName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);

        migrationService.runMigrationCycle().block();

        var actual = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);
        assertThat(actual.projectId()).isNotNull();
        var defaultProject = projectResourceClient.getProject(actual.projectId(), apiKey, workspaceName);
        assertThat(defaultProject.name()).isEqualTo(ProjectService.DEFAULT_PROJECT);
    }

    /**
     * One workspace exercising several buckets at once: certain via experiments (single project),
     * multi-project resolved by dominant assignment, certain-deleted rerouted to Default Project,
     * Path B via dataset, no-inference rerouted to Default Project. Every orphan is migrated; the
     * workspace flips to V2.
     */
    @Test
    void mixedWorkspaceMigratesAllBucketsIncludingMultiProjectAsDominant() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);

        // Certain: one V2 experiment in a single alive project.
        var certainProjectName = randomName("project");
        var certainProjectId = createProject(apiKey, workspaceName, certainProjectName);
        var certainDatasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, certainDatasetName, certainProjectName);
        var certainOptimizationId = createOrphanOptimization(apiKey, workspaceName, certainDatasetName);
        seedExperimentForOptimization(apiKey, workspaceName, certainDatasetName, certainProjectName,
                certainOptimizationId);

        // Multi-project: two experiments in project1, one in project2 — project1 dominates by count.
        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var minorityProjectName = randomName("project");
        createProject(apiKey, workspaceName, minorityProjectName);
        var multiDatasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, multiDatasetName, dominantProjectName);
        var multiOptimizationId = createOrphanOptimization(apiKey, workspaceName, multiDatasetName);
        seedExperimentForOptimization(apiKey, workspaceName, multiDatasetName, dominantProjectName,
                multiOptimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, multiDatasetName, dominantProjectName,
                multiOptimizationId);
        seedExperimentForOptimization(apiKey, workspaceName, multiDatasetName, minorityProjectName,
                multiOptimizationId);

        // Certain-deleted: V2 experiment in a project that gets deleted after seeding.
        var deletedProjectName = randomName("project");
        var deletedProjectId = createProject(apiKey, workspaceName, deletedProjectName);
        var deletedDatasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, deletedDatasetName, deletedProjectName);
        var deletedOptimizationId = createOrphanOptimization(apiKey, workspaceName, deletedDatasetName);
        seedExperimentForOptimization(apiKey, workspaceName, deletedDatasetName, deletedProjectName,
                deletedOptimizationId);
        projectResourceClient.deleteProject(deletedProjectId, apiKey, workspaceName);

        // Path B: no experiments reference the optimization; dataset has a project link.
        var pathBProjectName = randomName("project");
        var pathBProjectId = createProject(apiKey, workspaceName, pathBProjectName);
        var pathBDatasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, pathBDatasetName, pathBProjectName);
        var pathBOptimizationId = createOrphanOptimization(apiKey, workspaceName, pathBDatasetName);

        // No-inference: orphan dataset + no experiments → Default Project.
        var noInfDatasetName = randomName("dataset");
        createOrphanDataset(apiKey, workspaceName, noInfDatasetName);
        var noInfOptimizationId = createOrphanOptimization(apiKey, workspaceName, noInfDatasetName);

        migrationService.runMigrationCycle().block();

        assertOptimizationMigrated(apiKey, workspaceName, certainOptimizationId, certainProjectId);
        assertOptimizationMigrated(apiKey, workspaceName, multiOptimizationId, dominantProjectId);
        assertOptimizationMigrated(apiKey, workspaceName, deletedOptimizationId, defaultProjectId);
        assertOptimizationMigrated(apiKey, workspaceName, pathBOptimizationId, pathBProjectId);
        assertOptimizationMigrated(apiKey, workspaceName, noInfOptimizationId, defaultProjectId);
    }

    /** Env-excluded workspace: not touched even if it has eligible orphans. */
    @Test
    void skipExcludedWorkspaces() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, EXCLUDED_WORKSPACE_ID, randomName("user"));

        var seeded = seedEligibleOptimization(apiKey, workspaceName, randomName("project"));
        var optimizationId = seeded.getLeft();

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);
        assertOptimizationUnchanged(apiKey, workspaceName, optimizationId);
    }

    /** @return (optimizationId, projectId) for one eligible orphan + V2 experiment + trace. */
    private Pair<UUID, UUID> seedEligibleOptimization(String apiKey, String workspaceName, String projectName) {
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        createDatasetLinkedToProject(apiKey, workspaceName, datasetName, projectName);
        var optimizationId = createOrphanOptimization(apiKey, workspaceName, datasetName);
        seedExperimentForOptimization(apiKey, workspaceName, datasetName, projectName, optimizationId);
        return Pair.of(optimizationId, projectId);
    }

    private void seedExperimentForOptimization(String apiKey, String workspaceName, String datasetName,
            String projectName, UUID optimizationId) {
        var experimentId = experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .datasetName(datasetName)
                        .projectName(projectName)
                        .optimizationId(optimizationId)
                        .build(),
                apiKey, workspaceName);
        var traceId = traceResourceClient.createTrace(
                factory.manufacturePojo(Trace.class).toBuilder()
                        .id(null)
                        .projectName(projectName)
                        .projectId(null)
                        .startTime(Instant.now())
                        .feedbackScores(null)
                        .usage(null)
                        .build(),
                apiKey, workspaceName);
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

    private UUID createDatasetLinkedToProject(String apiKey, String workspaceName, String datasetName,
            String projectName) {
        return datasetResourceClient.createDataset(
                factory.manufacturePojo(Dataset.class).toBuilder()
                        .name(datasetName)
                        .projectId(null)
                        .projectName(projectName)
                        .build(),
                apiKey, workspaceName);
    }

    private UUID createOrphanOptimization(String apiKey, String workspaceName, String datasetName) {
        return optimizationResourceClient.create(
                optimizationResourceClient.createPartialOptimization()
                        .id(null)
                        .datasetName(datasetName)
                        .projectName(null)
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

    private void assertOptimizationMigrated(String apiKey, String workspaceName, UUID optimizationId,
            UUID expectedProjectId) {
        var actual = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);
        assertThat(actual.projectId()).isEqualTo(expectedProjectId);
    }

    private void assertOptimizationUnchanged(String apiKey, String workspaceName, UUID optimizationId) {
        var actual = optimizationResourceClient.get(optimizationId, apiKey, workspaceName, 200);
        assertThat(actual.projectId()).isNull();
    }
}
