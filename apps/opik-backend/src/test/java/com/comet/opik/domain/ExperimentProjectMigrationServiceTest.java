package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Experiment;
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
import com.comet.opik.infrastructure.auth.RequestContext;
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
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.assertExperimentEqual;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentProjectMigrationServiceTest {

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
    private ExperimentProjectMigrationService migrationService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, ExperimentProjectMigrationService migrationService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        workspaceResourceClient = new WorkspaceResourceClient(clientSupport, baseUrl, factory);
        this.migrationService = migrationService;
    }

    @Test
    void migrateEligibleExperimentsAcrossWorkspaces() {
        // Workspace A: one eligible experiment (smallest first per FIND_ELIGIBLE ordering).
        var apiKeyA = randomName("api-key");
        var workspaceNameA = randomName("workspace");
        var workspaceIdA = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyA, workspaceNameA, workspaceIdA, randomName("user"));
        var projectNameA = randomName("project");
        var seededA = seedCertainExperiment(apiKeyA, workspaceNameA, projectNameA);
        var experimentIdA = seededA.getLeft();
        var projectIdA = seededA.getRight();
        var beforeA = experimentResourceClient.getExperiment(experimentIdA, apiKeyA, workspaceNameA);

        // Workspace B: two eligible experiments in the same project (multi-experiment per workspace).
        var apiKeyB = randomName("api-key");
        var workspaceNameB = randomName("workspace");
        var workspaceIdB = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyB, workspaceNameB, workspaceIdB, randomName("user"));
        var projectNameB = randomName("project");
        var projectIdB = createProject(apiKeyB, workspaceNameB, projectNameB);
        var datasetNameB = randomName("dataset");
        createDatasetWithProject(apiKeyB, workspaceNameB, datasetNameB, projectIdB);
        var experimentIdsB = new ArrayList<UUID>();
        for (int i = 0; i < 2; i++) {
            var traceId = createTrace(apiKeyB, workspaceNameB, projectNameB);
            var experimentId = createOrphanExperiment(apiKeyB, workspaceNameB, datasetNameB);
            linkExperimentToTraces(apiKeyB, workspaceNameB, experimentId, traceId);
            experimentIdsB.add(experimentId);
        }
        var beforesB = new ArrayList<Experiment>();
        for (var id : experimentIdsB) {
            beforesB.add(experimentResourceClient.getExperiment(id, apiKeyB, workspaceNameB));
        }

        // Prime the workspace_version cache to V1 before triggering the cycle. The post-cycle V2
        // read must then depend on evictWorkspaceVersionCache actually clearing the cached V1,
        // exercising the evictCache path end-to-end.
        assertWorkspaceVersion(apiKeyA, workspaceNameA, OpikVersion.VERSION_1);
        assertWorkspaceVersion(apiKeyB, workspaceNameB, OpikVersion.VERSION_1);

        // A single cycle picks up both workspaces (workspacesPerRun=20 default) and
        // FIND_ELIGIBLE_EXPERIMENT_WORKSPACES orders smallest-first; both should reach V2.
        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKeyA, workspaceNameA, OpikVersion.VERSION_2);
        assertWorkspaceVersion(apiKeyB, workspaceNameB, OpikVersion.VERSION_2);

        assertExperimentMigrated(apiKeyA, workspaceNameA, experimentIdA, beforeA, projectIdA, projectNameA);
        for (int i = 0; i < experimentIdsB.size(); i++) {
            assertExperimentMigrated(apiKeyB, workspaceNameB, experimentIdsB.get(i),
                    beforesB.get(i), projectIdB, projectNameB);
        }
    }

    /**
     * Idempotency on re-run: once a workspace is migrated, a second cycle must not touch its
     * experiments again. The first cycle promotes the workspace to V2 and stamps the experiment
     * with the system user; the second cycle's eligibility query (orphan check) finds no
     * candidates, and even if {@code BATCH_SET_PROJECT_ID} were reached it has a
     * {@code WHERE project_id = ''} guard that prevents double writes. Asserted via
     * {@code lastUpdatedAt} stability between cycles.
     */
    @Test
    void secondCycleIsNoopAfterSuccessfulMigration() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));
        var projectName = randomName("project");
        var seeded = seedCertainExperiment(apiKey, workspaceName, projectName);
        var experimentId = seeded.getLeft();
        var projectId = seeded.getRight();
        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertExperimentMigrated(apiKey, workspaceName, experimentId, beforeMigration, projectId, projectName);
        var afterFirstCycle = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        // Second cycle: the workspace is no longer eligible (no orphans) and the experiment row
        // must not be re-written.
        migrationService.runMigrationCycle().block();

        var afterSecondCycle = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstCycle.lastUpdatedAt());
        assertExperimentEqual(afterSecondCycle, afterFirstCycle);
        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
    }

    /**
     * One workspace exercising all migration buckets: certain (single project), multi-project
     * resolved by dominant assignment, certain-deleted, no-inference, demo. No bucket traps the
     * workspace — every non-demo orphan is migrated, the workspace flips to V2, and the demo
     * experiment is left untouched (filtered out at the {@code WHERE e.name NOT IN
     * :demo_experiment_names} guard before it can reach the classifier).
     */
    @Test
    void mixedWorkspaceMigratesAllBucketsIncludingMultiProjectAsDominant() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Certain: one trace in a single alive project => migrates to inferred project.
        var certainProjectName = randomName("project");
        var certainProjectId = createProject(apiKey, workspaceName, certainProjectName);
        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, certainProjectId);
        var certainTraceId = createTrace(apiKey, workspaceName, certainProjectName);
        var certainExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, certainExperimentId, certainTraceId);

        // Multi-project (dominant): two traces in the winner, one in the runner-up => migrates to the
        // winner. Previously this bucket was the "ambiguous" trap source.
        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var runnerUpProjectName = randomName("project");
        createProject(apiKey, workspaceName, runnerUpProjectName);
        var dominantTrace1Id = createTrace(apiKey, workspaceName, dominantProjectName);
        var dominantTrace2Id = createTrace(apiKey, workspaceName, dominantProjectName);
        var runnerUpTraceId = createTrace(apiKey, workspaceName, runnerUpProjectName);
        var dominantExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, dominantExperimentId,
                dominantTrace1Id, dominantTrace2Id, runnerUpTraceId);

        // Certain-deleted: one trace in a single project that is then deleted in MySQL =>
        // migrates to Default Project.
        var deletedProjectName = randomName("project");
        var deletedProjectId = createProject(apiKey, workspaceName, deletedProjectName);
        var deletedTraceId = createTrace(apiKey, workspaceName, deletedProjectName);
        var deletedExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, deletedExperimentId, deletedTraceId);
        projectResourceClient.deleteProject(deletedProjectId, apiKey, workspaceName);

        // No-inference: orphan experiment with no traces => migrates to Default Project.
        var noInferenceExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);

        // Demo: eligible-shaped (single trace in the live certain project) but filtered by demo-name.
        var demoTraceId = createTrace(apiKey, workspaceName, certainProjectName);
        var demoExperimentId = experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .name(DemoData.EXPERIMENTS.getFirst())
                        .datasetName(datasetName)
                        .build(),
                apiKey, workspaceName);
        linkExperimentToTraces(apiKey, workspaceName, demoExperimentId, demoTraceId);

        var certainBefore = experimentResourceClient.getExperiment(certainExperimentId, apiKey, workspaceName);
        var dominantBefore = experimentResourceClient.getExperiment(dominantExperimentId, apiKey, workspaceName);
        var deletedBefore = experimentResourceClient.getExperiment(deletedExperimentId, apiKey, workspaceName);
        var noInferenceBefore = experimentResourceClient.getExperiment(
                noInferenceExperimentId, apiKey, workspaceName);
        var demoBefore = experimentResourceClient.getExperiment(demoExperimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        // Every non-demo orphan resolves to a project this cycle; workspace flips to V2.
        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);

        assertExperimentMigrated(apiKey, workspaceName, certainExperimentId, certainBefore,
                certainProjectId, certainProjectName);
        assertExperimentMigrated(apiKey, workspaceName, dominantExperimentId, dominantBefore,
                dominantProjectId, dominantProjectName);
        assertExperimentMigratedToDefault(apiKey, workspaceName, deletedExperimentId, deletedBefore);
        assertExperimentMigratedToDefault(apiKey, workspaceName, noInferenceExperimentId, noInferenceBefore);
        assertExperimentUnchanged(apiKey, workspaceName, demoExperimentId, demoBefore);
    }

    @Test
    void migrateDeletedProjectExperimentToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, projectId);
        var traceId = createTrace(apiKey, workspaceName, projectName);
        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId, traceId);

        projectResourceClient.deleteProject(projectId, apiKey, workspaceName);

        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);

        assertExperimentMigratedToDefault(apiKey, workspaceName, experimentId, beforeMigration);
    }

    @Test
    void migrateNoInferenceExperimentToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, projectId);
        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);

        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);

        assertExperimentMigratedToDefault(apiKey, workspaceName, experimentId, beforeMigration);
    }

    /**
     * Dominant-project assignment by trace count: the project with more referencing traces wins.
     * Verifies the primary key of the SQL ranker ({@code count DESC}).
     */
    @Test
    void migrateMultiProjectExperimentToDominantProjectByCount() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var runnerUpProjectName = randomName("project");
        createProject(apiKey, workspaceName, runnerUpProjectName);

        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, dominantProjectId);

        var dominantTraceA = createTrace(apiKey, workspaceName, dominantProjectName);
        var dominantTraceB = createTrace(apiKey, workspaceName, dominantProjectName);
        var runnerUpTrace = createTrace(apiKey, workspaceName, runnerUpProjectName);

        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId,
                dominantTraceA, dominantTraceB, runnerUpTrace);

        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertExperimentMigrated(apiKey, workspaceName, experimentId, beforeMigration,
                dominantProjectId, dominantProjectName);
    }

    /**
     * Dominant-project recency tiebreaker: equal trace counts across two projects, the
     * most-recently-updated trace's project wins ({@code last_activity DESC}).
     */
    @Test
    void migrateMultiProjectExperimentToMostRecentProjectWhenCountTies() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var olderProjectName = randomName("project");
        createProject(apiKey, workspaceName, olderProjectName);
        var recentProjectName = randomName("project");
        var recentProjectId = createProject(apiKey, workspaceName, recentProjectName);

        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, recentProjectId);

        var olderTrace = createTrace(apiKey, workspaceName, olderProjectName);
        // Cushion against batched writes sharing an insert tick — keeps the recency contrast unambiguous.
        TestUtils.waitForMillis(20);
        var recentTrace = createTrace(apiKey, workspaceName, recentProjectName);

        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId, olderTrace, recentTrace);

        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertExperimentMigrated(apiKey, workspaceName, experimentId, beforeMigration,
                recentProjectId, recentProjectName);
    }

    /**
     * Dominant project is deleted before the cycle runs: validation drops it and the experiment
     * falls back to Default Project — it is not reassigned to the surviving lower-count project,
     * and it does not count as a dominant assignment.
     */
    @Test
    void migrateMultiProjectExperimentToDefaultProjectWhenDominantProjectWasDeleted() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var minorityProjectName = randomName("project");
        createProject(apiKey, workspaceName, minorityProjectName);

        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, dominantProjectId);

        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId,
                createTrace(apiKey, workspaceName, dominantProjectName),
                createTrace(apiKey, workspaceName, dominantProjectName),
                createTrace(apiKey, workspaceName, minorityProjectName));

        // Delete the dominant project: the query still infers it, but validation drops it.
        projectResourceClient.deleteProject(dominantProjectId, apiKey, workspaceName);

        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
        assertExperimentMigratedToDefault(apiKey, workspaceName, experimentId, beforeMigration);
    }

    /**
     * Re-running the cycle on an already-migrated multi-project experiment is a no-op:
     * eligibility query reports no orphans, and the {@code project_id = ''} guard on
     * {@code BATCH_SET_PROJECT_ID} would block a write even if reached. {@code lastUpdatedAt}
     * stability confirms idempotency.
     */
    @Test
    void multiProjectExperimentSecondCycleIsNoop() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var dominantProjectName = randomName("project");
        var dominantProjectId = createProject(apiKey, workspaceName, dominantProjectName);
        var runnerUpProjectName = randomName("project");
        createProject(apiKey, workspaceName, runnerUpProjectName);

        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, dominantProjectId);

        linkExperimentToTraces(apiKey, workspaceName,
                createOrphanExperiment(apiKey, workspaceName, datasetName),
                createTrace(apiKey, workspaceName, dominantProjectName),
                createTrace(apiKey, workspaceName, dominantProjectName),
                createTrace(apiKey, workspaceName, runnerUpProjectName));

        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId,
                createTrace(apiKey, workspaceName, dominantProjectName),
                createTrace(apiKey, workspaceName, dominantProjectName),
                createTrace(apiKey, workspaceName, runnerUpProjectName));

        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        migrationService.runMigrationCycle().block();
        var afterFirstCycle = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        assertExperimentMigrated(apiKey, workspaceName, experimentId, beforeMigration,
                dominantProjectId, dominantProjectName);

        migrationService.runMigrationCycle().block();
        var afterSecondCycle = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstCycle.lastUpdatedAt());
        assertExperimentEqual(afterSecondCycle, afterFirstCycle);
        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2);
    }

    @Test
    void skipExcludedWorkspaces() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, EXCLUDED_WORKSPACE_ID_1, randomName("user"));

        var projectName = randomName("project");
        var seeded = seedCertainExperiment(apiKey, workspaceName, projectName);
        var experimentId = seeded.getLeft();
        var beforeMigration = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1);
        assertExperimentUnchanged(apiKey, workspaceName, experimentId, beforeMigration);
    }

    private Pair<UUID, UUID> seedCertainExperiment(String apiKey, String workspaceName, String projectName) {
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, projectId);
        var traceId = createTrace(apiKey, workspaceName, projectName);
        var experimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, experimentId, traceId);
        return Pair.of(experimentId, projectId);
    }

    private String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    private UUID createProject(String apiKey, String workspaceName, String projectName) {
        return projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(),
                apiKey, workspaceName);
    }

    private void createDatasetWithProject(String apiKey, String workspaceName, String datasetName, UUID projectId) {
        datasetResourceClient.createDataset(
                factory.manufacturePojo(Dataset.class).toBuilder()
                        .name(datasetName)
                        .projectId(projectId)
                        .projectName(null)
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

    private UUID createOrphanExperiment(String apiKey, String workspaceName, String datasetName) {
        return experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .datasetName(datasetName)
                        .build(),
                apiKey, workspaceName);
    }

    private void linkExperimentToTraces(String apiKey, String workspaceName, UUID experimentId, UUID... traceIds) {
        var items = Arrays.stream(traceIds)
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

    private void assertExperimentMigrated(
            String apiKey, String workspaceName, UUID experimentId,
            Experiment beforeMigration, UUID expectedProjectId, String expectedProjectName) {
        var actual = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        var expected = beforeMigration.toBuilder()
                .projectId(expectedProjectId)
                .projectName(expectedProjectName)
                .lastUpdatedBy(RequestContext.SYSTEM_USER)
                .build();
        assertExperimentEqual(actual, expected);
        assertThat(actual.lastUpdatedAt()).isAfter(beforeMigration.lastUpdatedAt());
    }

    private void assertExperimentMigratedToDefault(
            String apiKey, String workspaceName, UUID experimentId, Experiment beforeMigration) {
        var actual = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        assertThat(actual.projectId()).isNotNull();
        var expected = beforeMigration.toBuilder()
                .projectId(actual.projectId())
                .projectName(ProjectService.DEFAULT_PROJECT)
                .lastUpdatedBy(RequestContext.SYSTEM_USER)
                .build();
        assertExperimentEqual(actual, expected);
        assertThat(actual.lastUpdatedAt()).isAfter(beforeMigration.lastUpdatedAt());
    }

    private void assertExperimentUnchanged(
            String apiKey, String workspaceName, UUID experimentId, Experiment beforeMigration) {
        var actual = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        assertExperimentEqual(actual, beforeMigration);
        assertThat(actual.lastUpdatedAt()).isEqualTo(beforeMigration.lastUpdatedAt());
    }
}
