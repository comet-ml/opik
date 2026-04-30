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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.assertExperimentEqual;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentProjectMigrationJobTest {

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
                                new CustomConfig("experimentProjectMigration.enabled", "true"),
                                // Buffer so the first cycle fires after the initial seed+prime.
                                new CustomConfig("experimentProjectMigration.startupDelay", "3s"),
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

        // Prime the workspace_version cache to V1 before the first migration cycle fires.
        // This forces the post-migration V2 read to depend on evictWorkspaceVersionCache
        // actually clearing the cached V1, exercising the evictCache path end-to-end.
        assertWorkspaceVersion(apiKeyA, workspaceNameA, OpikVersion.VERSION_1);
        assertWorkspaceVersion(apiKeyB, workspaceNameB, OpikVersion.VERSION_1);

        // A single cycle picks up both workspaces (workspacesPerRun=20 default) and
        // FIND_ELIGIBLE_EXPERIMENT_WORKSPACES orders smallest-first; both should reach V2.
        assertWorkspaceVersion2(apiKeyA, workspaceNameA);
        assertWorkspaceVersion2(apiKeyB, workspaceNameB);

        assertExperimentMigrated(apiKeyA, workspaceNameA, experimentIdA, beforeA, projectIdA, projectNameA);
        for (int i = 0; i < experimentIdsB.size(); i++) {
            assertExperimentMigrated(apiKeyB, workspaceNameB, experimentIdsB.get(i),
                    beforesB.get(i), projectIdB, projectNameB);
        }
    }

    /**
     * Single workspace covering all non-eligible filter points in one cycle:
     * <ul>
     *   <li>"ambiguous" experiment is filtered at {@code COMPUTE_MAPPING}
     *       ({@code HAVING count(DISTINCT t.project_id) = 1}).</li>
     *   <li>"deleted-project" experiment reaches {@code COMPUTE_MAPPING} but is filtered at
     *       {@code projectService.findByIds} ({@code skippedDeleted > 0} branch in
     *       {@code migrateValidatedMappings}).</li>
     *   <li>"demo" experiment is eligible-shaped (one trace in a single live project) but
     *       filtered by {@code WHERE e.name NOT IN :demo_experiment_names} in both
     *       {@code FIND_ELIGIBLE} and {@code COMPUTE_MAPPING}. Demo data is invisible to the
     *       workspace V1 entity check, so this gap can only be detected at the experiment level.</li>
     * </ul>
     * Workspace stays V1 because the ambiguous and deleted-project orphans keep it V1; the
     * eligible one migrates to its inferred project; the demo experiment's empty project_id is
     * preserved.
     */
    @Test
    void mixedWorkspaceMigratesEligibleAndKeepsNonEligibleAsV1() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Eligible: one trace in a single alive project => migrates.
        var eligibleProjectName = randomName("project");
        var eligibleProjectId = createProject(apiKey, workspaceName, eligibleProjectName);
        var datasetName = randomName("dataset");
        createDatasetWithProject(apiKey, workspaceName, datasetName, eligibleProjectId);
        var eligibleTraceId = createTrace(apiKey, workspaceName, eligibleProjectName);
        var eligibleExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, eligibleExperimentId, eligibleTraceId);

        // Ambiguous: two traces in two different projects => filtered at COMPUTE_MAPPING.
        var ambiguousProjectName1 = randomName("project");
        var ambiguousProjectName2 = randomName("project");
        createProject(apiKey, workspaceName, ambiguousProjectName1);
        createProject(apiKey, workspaceName, ambiguousProjectName2);
        var ambiguousTrace1Id = createTrace(apiKey, workspaceName, ambiguousProjectName1);
        var ambiguousTrace2Id = createTrace(apiKey, workspaceName, ambiguousProjectName2);
        var ambiguousExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, ambiguousExperimentId,
                ambiguousTrace1Id, ambiguousTrace2Id);

        // Deleted-project: one trace in a single project that gets deleted in MySQL after seeding.
        // Reaches COMPUTE_MAPPING (single project), gets filtered at projectService.findByIds.
        var deletedProjectName = randomName("project");
        var deletedProjectId = createProject(apiKey, workspaceName, deletedProjectName);
        var deletedTraceId = createTrace(apiKey, workspaceName, deletedProjectName);
        var deletedExperimentId = createOrphanExperiment(apiKey, workspaceName, datasetName);
        linkExperimentToTraces(apiKey, workspaceName, deletedExperimentId, deletedTraceId);
        projectResourceClient.deleteProject(deletedProjectId, apiKey, workspaceName);

        // Demo: eligible-shaped (single trace in the live eligible project) but filtered by
        // demo-name exclusion in both FIND_ELIGIBLE and COMPUTE_MAPPING.
        var demoTraceId = createTrace(apiKey, workspaceName, eligibleProjectName);
        var demoExperimentId = experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment()
                        .id(null)
                        .name(DemoData.EXPERIMENTS.getFirst())
                        .datasetName(datasetName)
                        .build(),
                apiKey, workspaceName);
        linkExperimentToTraces(apiKey, workspaceName, demoExperimentId, demoTraceId);

        var eligibleBefore = experimentResourceClient.getExperiment(eligibleExperimentId, apiKey, workspaceName);
        var ambiguousBefore = experimentResourceClient.getExperiment(ambiguousExperimentId, apiKey, workspaceName);
        var deletedBefore = experimentResourceClient.getExperiment(deletedExperimentId, apiKey, workspaceName);
        var demoBefore = experimentResourceClient.getExperiment(demoExperimentId, apiKey, workspaceName);

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertExperimentMigrated(apiKey, workspaceName, eligibleExperimentId, eligibleBefore,
                eligibleProjectId, eligibleProjectName);
        assertExperimentUnchanged(apiKey, workspaceName, ambiguousExperimentId, ambiguousBefore);
        assertExperimentUnchanged(apiKey, workspaceName, deletedExperimentId, deletedBefore);
        assertExperimentUnchanged(apiKey, workspaceName, demoExperimentId, demoBefore);
    }

    @Test
    void skipExperimentWhenInferredProjectWasDeleted() {
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

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertExperimentUnchanged(apiKey, workspaceName, experimentId, beforeMigration);
    }

    @Test
    void skipExperimentWithNoTraceLinks() {
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

        assertWorkspaceVersion1(apiKey, workspaceName);

        assertExperimentUnchanged(apiKey, workspaceName, experimentId, beforeMigration);
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

        assertWorkspaceVersion1(apiKey, workspaceName);

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

    private void assertWorkspaceVersion2(String apiKey, String workspaceName) {
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS).untilAsserted(
                        () -> assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_2));
    }

    private void assertWorkspaceVersion1(String apiKey, String workspaceName) {
        Awaitility.await().atMost(15, TimeUnit.SECONDS).during(12, TimeUnit.SECONDS).untilAsserted(
                () -> assertWorkspaceVersion(apiKey, workspaceName, OpikVersion.VERSION_1));
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

    private void assertExperimentUnchanged(
            String apiKey, String workspaceName, UUID experimentId, Experiment beforeMigration) {
        var actual = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        assertExperimentEqual(actual, beforeMigration);
        assertThat(actual.lastUpdatedAt()).isEqualTo(beforeMigration.lastUpdatedAt());
    }
}
