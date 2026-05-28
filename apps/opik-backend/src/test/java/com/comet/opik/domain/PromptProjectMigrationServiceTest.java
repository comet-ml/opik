package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Experiment.PromptVersionLink;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
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
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptTestAssertions;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class PromptProjectMigrationServiceTest {

    private static final String EXCLUDED_WORKSPACE_ID = UUID.randomUUID().toString();
    /**
     * Pinned to the {@code @Min(100)} floor so {@link #oversizedWorkspaceDrainsAcrossCycles}
     * can trigger spillover with a tractable seed (BATCH_SIZE + 1 prompts) — well above any
     * other test's prompt count so the rest of the class is unaffected.
     */
    private static final int BATCH_SIZE = 100;

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

        // Job stays disabled here on purpose. The service-level tests drive the cycle directly
        // via PromptProjectMigrationService.runMigrationCycle().block() — deterministic and fast,
        // no Quartz interleaving with the test's seed/assert phases.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("migration.excludedWorkspaceIds", EXCLUDED_WORKSPACE_ID),
                                new CustomConfig("promptProjectMigration.promptBatchSize",
                                        String.valueOf(BATCH_SIZE))))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private PromptResourceClient promptResourceClient;
    private PromptProjectMigrationService migrationService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, PromptProjectMigrationService migrationService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        promptResourceClient = new PromptResourceClient(clientSupport, baseUrl, factory);
        this.migrationService = migrationService;
    }

    @Test
    void migrateCertainPromptViaLegacyAndModernPaths() {
        // Three prompts: one referenced only via legacy prompt_id, one only via prompt_versions map,
        // one via both. All resolve to the same project and migrate to it.
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = createDatasetWithProject(apiKey, workspaceName, projectId);

        var legacyLink = createOrphanPromptVersion(apiKey, workspaceName);
        var modernLink = createOrphanPromptVersion(apiKey, workspaceName);
        var bothLink = createOrphanPromptVersion(apiKey, workspaceName);

        // Experiment 1: legacy prompt_id only.
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                legacyLink, null);
        // Experiment 2: prompt_versions map only.
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                null, List.of(modernLink));
        // Experiment 3: both fields set, redundantly referencing the same prompt.
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                bothLink, List.of(bothLink));

        var legacyBefore = promptResourceClient.getPrompt(legacyLink.promptId(), apiKey, workspaceName);
        var modernBefore = promptResourceClient.getPrompt(modernLink.promptId(), apiKey, workspaceName);
        var bothBefore = promptResourceClient.getPrompt(bothLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedTo(apiKey, workspaceName, legacyLink.promptId(), legacyBefore, projectId);
        assertPromptMigratedTo(apiKey, workspaceName, modernLink.promptId(), modernBefore, projectId);
        assertPromptMigratedTo(apiKey, workspaceName, bothLink.promptId(), bothBefore, projectId);
    }

    /**
     * Single workspace covering all four classifier buckets in one cycle:
     * <ul>
     *     <li><b>certain</b> — one referencing experiment in a single alive project => assigned.</li>
     *     <li><b>certain-deleted</b> — one referencing experiment in a project later deleted in
     *     MySQL => routed to Default Project.</li>
     *     <li><b>no-inference</b> — orphan prompt with no referencing experiment => routed to
     *     Default Project.</li>
     *     <li><b>ambiguous</b> — referencing experiments in two distinct live projects => skipped.</li>
     * </ul>
     * Also covers the demo-name filter: a demo-named prompt is workspace-orphan but excluded from
     * the eligibility query, so it's invisible to the cycle even when other prompts in the
     * workspace migrate.
     *
     * <p>Outcome: workspace gets trapped with {@code all_ambiguous} because the ambiguous prompt
     * is left behind; the other three buckets land on their assigned projects and the demo prompt
     * stays orphan.
     */
    @Test
    void mixedWorkspaceMigratesAcrossBucketsAndTrapsOnAmbiguous(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var aliveProjectName = randomName("project");
        var aliveProjectId = createProject(apiKey, workspaceName, aliveProjectName);
        var aliveDataset = createDatasetWithProject(apiKey, workspaceName, aliveProjectId);

        var certainLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, aliveDataset, aliveProjectId, aliveProjectName,
                certainLink, null);

        var deletedProjectName = randomName("project");
        var deletedProjectId = createProject(apiKey, workspaceName, deletedProjectName);
        var deletedDataset = createDatasetWithProject(apiKey, workspaceName, deletedProjectId);
        var deletedLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, deletedDataset, deletedProjectId, deletedProjectName,
                deletedLink, null);
        projectResourceClient.deleteProject(deletedProjectId, apiKey, workspaceName);

        var noInferenceLink = createOrphanPromptVersion(apiKey, workspaceName);

        var secondAliveProjectName = randomName("project");
        var secondAliveProjectId = createProject(apiKey, workspaceName, secondAliveProjectName);
        var secondAliveDataset = createDatasetWithProject(apiKey, workspaceName, secondAliveProjectId);
        var ambiguousLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, aliveDataset, aliveProjectId, aliveProjectName,
                ambiguousLink, null);
        createExperimentInProject(apiKey, workspaceName, secondAliveDataset, secondAliveProjectId,
                secondAliveProjectName, ambiguousLink, null);

        // Demo: eligible-shaped but excluded by the demo-name filter on the eligibility query.
        var demoName = DemoData.PROMPTS.getFirst();
        var demoPrompt = Prompt.builder().name(demoName).projectId(null).projectName(null).build();
        var demoVersion = promptResourceClient.createPromptVersion(demoPrompt, apiKey, workspaceName);
        var demoLink = buildVersionLink(demoVersion, demoName);
        createExperimentInProject(apiKey, workspaceName, aliveDataset, aliveProjectId, aliveProjectName,
                demoLink, null);

        var certainBefore = promptResourceClient.getPrompt(certainLink.promptId(), apiKey, workspaceName);
        var deletedBefore = promptResourceClient.getPrompt(deletedLink.promptId(), apiKey, workspaceName);
        var noInferenceBefore = promptResourceClient.getPrompt(noInferenceLink.promptId(), apiKey, workspaceName);
        var ambiguousBefore = promptResourceClient.getPrompt(ambiguousLink.promptId(), apiKey, workspaceName);
        var demoBefore = promptResourceClient.getPrompt(demoLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedTo(apiKey, workspaceName, certainLink.promptId(), certainBefore, aliveProjectId);
        assertPromptMigratedToDefault(apiKey, workspaceName, deletedLink.promptId(), deletedBefore);
        assertPromptMigratedToDefault(apiKey, workspaceName, noInferenceLink.promptId(), noInferenceBefore);
        assertPromptUnchanged(apiKey, workspaceName, ambiguousLink.promptId(), ambiguousBefore);
        assertPromptUnchanged(apiKey, workspaceName, demoLink.promptId(), demoBefore);

        assertWorkspaceTrapped(workspacesService, workspaceId, "all_ambiguous");
    }

    @Test
    void migrateDeletedProjectPromptToDefaultProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = createDatasetWithProject(apiKey, workspaceName, projectId);
        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                promptLink, null);

        projectResourceClient.deleteProject(projectId, apiKey, workspaceName);

        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedToDefault(apiKey, workspaceName, promptLink.promptId(), before);
    }

    @Test
    void migrateNoInferencePromptToDefaultProject() {
        // Orphan prompt with zero referencing experiments => no-inference bucket => Default Project.
        // Also verifies the cycle provisions Default Project in-line when it's missing
        // (projectService.getOrCreate path).
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedToDefault(apiKey, workspaceName, promptLink.promptId(), before);
    }

    @Test
    void migrateNoInferencePromptWhenAllReferencingExperimentsAreOrphan() {
        // Prompt referenced only by experiments that are still orphan (project_id = ''). Those
        // experiments are correctly invisible to the classification (anyIf / countDistinctIf on
        // project_id != '') and the prompt falls into no-inference => Default Project.
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var datasetName = randomName("dataset");
        datasetResourceClient.createDataset(
                factory.manufacturePojo(Dataset.class).toBuilder()
                        .name(datasetName)
                        .projectId(null)
                        .projectName(null)
                        .build(),
                apiKey, workspaceName);
        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        var orphanExperiment = experimentResourceClient.createPartialExperiment()
                .id(null)
                .datasetName(datasetName)
                .promptVersion(promptLink)
                .promptVersions(List.of(promptLink))
                .build();
        experimentResourceClient.create(orphanExperiment, apiKey, workspaceName);

        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedToDefault(apiKey, workspaceName, promptLink.promptId(), before);
    }

    /**
     * Workspace whose only orphan is ambiguous: the early {@code assignments.isEmpty()}
     * short-circuit in {@code applyClassifications} traps the workspace before any writes happen.
     * Distinct from the post-write trap exercised by
     * {@link #mixedWorkspaceMigratesAcrossBucketsAndTrapsOnAmbiguous}, which goes through the
     * full batch-update chain first.
     */
    @Test
    void trapWorkspaceWithOnlyAmbiguousPrompts(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName1 = randomName("project");
        var projectId1 = createProject(apiKey, workspaceName, projectName1);
        var dataset1 = createDatasetWithProject(apiKey, workspaceName, projectId1);
        var projectName2 = randomName("project");
        var projectId2 = createProject(apiKey, workspaceName, projectName2);
        var dataset2 = createDatasetWithProject(apiKey, workspaceName, projectId2);

        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, dataset1, projectId1, projectName1,
                promptLink, null);
        createExperimentInProject(apiKey, workspaceName, dataset2, projectId2, projectName2,
                promptLink, null);

        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptUnchanged(apiKey, workspaceName, promptLink.promptId(), before);
        assertWorkspaceTrapped(workspacesService, workspaceId, "all_ambiguous");
    }

    @Test
    void skipPreMarkedTrappedWorkspaces(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        workspacesService.markPromptProjectMigrationSkipped(workspaceId, "test-pre-marked-trap");

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = createDatasetWithProject(apiKey, workspaceName, projectId);
        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                promptLink, null);

        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptUnchanged(apiKey, workspaceName, promptLink.promptId(), before);
    }

    @Test
    void skipExcludedWorkspaces() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, EXCLUDED_WORKSPACE_ID, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = createDatasetWithProject(apiKey, workspaceName, projectId);
        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                promptLink, null);

        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptUnchanged(apiKey, workspaceName, promptLink.promptId(), before);
    }

    /**
     * Multi-workspace cycle: one cycle picks up every eligible workspace in scope (up to
     * {@code workspacesPerRun}), ordered smallest-first. Both workspaces should reach a migrated
     * state in a single {@code runMigrationCycle().block()}. Mirrors D1's
     * {@code migrateEligibleExperimentsAcrossWorkspaces}.
     */
    @Test
    void migrateEligiblePromptsAcrossWorkspaces() {
        // Workspace A: one orphan prompt (smaller — processed first by FIND_ELIGIBLE_PROMPT_WORKSPACES).
        var apiKeyA = randomName("api-key");
        var workspaceNameA = randomName("workspace");
        var workspaceIdA = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyA, workspaceNameA, workspaceIdA, randomName("user"));
        var projectNameA = randomName("project");
        var projectIdA = createProject(apiKeyA, workspaceNameA, projectNameA);
        var datasetNameA = createDatasetWithProject(apiKeyA, workspaceNameA, projectIdA);
        var promptLinkA = createOrphanPromptVersion(apiKeyA, workspaceNameA);
        createExperimentInProject(apiKeyA, workspaceNameA, datasetNameA, projectIdA, projectNameA,
                promptLinkA, null);

        // Workspace B: two orphan prompts in the same alive project.
        var apiKeyB = randomName("api-key");
        var workspaceNameB = randomName("workspace");
        var workspaceIdB = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKeyB, workspaceNameB, workspaceIdB, randomName("user"));
        var projectNameB = randomName("project");
        var projectIdB = createProject(apiKeyB, workspaceNameB, projectNameB);
        var datasetNameB = createDatasetWithProject(apiKeyB, workspaceNameB, projectIdB);
        var promptLinkB1 = createOrphanPromptVersion(apiKeyB, workspaceNameB);
        var promptLinkB2 = createOrphanPromptVersion(apiKeyB, workspaceNameB);
        createExperimentInProject(apiKeyB, workspaceNameB, datasetNameB, projectIdB, projectNameB,
                promptLinkB1, null);
        createExperimentInProject(apiKeyB, workspaceNameB, datasetNameB, projectIdB, projectNameB,
                promptLinkB2, null);

        var beforeA = promptResourceClient.getPrompt(promptLinkA.promptId(), apiKeyA, workspaceNameA);
        var beforeB1 = promptResourceClient.getPrompt(promptLinkB1.promptId(), apiKeyB, workspaceNameB);
        var beforeB2 = promptResourceClient.getPrompt(promptLinkB2.promptId(), apiKeyB, workspaceNameB);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedTo(apiKeyA, workspaceNameA, promptLinkA.promptId(), beforeA, projectIdA);
        assertPromptMigratedTo(apiKeyB, workspaceNameB, promptLinkB1.promptId(), beforeB1, projectIdB);
        assertPromptMigratedTo(apiKeyB, workspaceNameB, promptLinkB2.promptId(), beforeB2, projectIdB);
    }

    /**
     * Pagination: a workspace with more orphans than {@code promptBatchSize} drains across
     * successive cycles. Cycle 1 processes exactly {@code BATCH_SIZE} prompts (the
     * {@code findOrphanPromptIds.LIMIT :limit} cap); cycle 2 picks up the remaining one. The
     * eligibility scan keeps finding the workspace until it's empty.
     */
    @Test
    void oversizedWorkspaceDrainsAcrossCycles() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // No-inference path: BATCH_SIZE + 1 orphan prompts with zero referencing experiments.
        // Cycle drives them all to Default Project, but only BATCH_SIZE per pass.
        var promptIds = new ArrayList<UUID>();
        for (int i = 0; i < BATCH_SIZE + 1; i++) {
            promptIds.add(createOrphanPromptVersion(apiKey, workspaceName).promptId());
        }

        migrationService.runMigrationCycle().block();

        var migratedAfterCycle1 = countMigrated(apiKey, workspaceName, promptIds);
        assertThat(migratedAfterCycle1)
                .as("cycle 1 should migrate exactly BATCH_SIZE prompts, leaving 1 orphan")
                .isEqualTo(BATCH_SIZE);

        migrationService.runMigrationCycle().block();

        var migratedAfterCycle2 = countMigrated(apiKey, workspaceName, promptIds);
        assertThat(migratedAfterCycle2)
                .as("cycle 2 should drain the last orphan")
                .isEqualTo(BATCH_SIZE + 1);
    }

    /**
     * Partial-batch all-ambiguous: a workspace with more orphans than {@code promptBatchSize}
     * whose current batch happens to be entirely ambiguous must NOT be trapped on this cycle —
     * the workspace may still have non-ambiguous orphans on the next page. Without the
     * {@code isTailBatch} guard this is a silent data-loss path: workspace gets permanently
     * excluded after cycle 1 and the remaining orphans are stranded.
     *
     * <p>Known residual: when every orphan in the workspace is genuinely ambiguous,
     * {@code findOrphanPromptIds} keeps returning a full {@code BATCH_SIZE}-sized page each
     * cycle so {@code isTailBatch} stays false forever and the workspace cycles indefinitely
     * without trapping. The indefinite cycle is annoying (visible in metrics / logs) but is
     * strictly better than the silent-data-loss alternative. Closing this cleanly requires
     * {@code ORDER BY id ASC} + cursor-style spillover in {@code findOrphanPromptIds} and is
     * a follow-up to this PR.
     */
    @Test
    void partialBatchAllAmbiguousDoesNotTrap(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Two distinct alive projects shared across every ambiguous prompt so each prompt's
        // classification has projectCount == 2 → AMBIGUOUS bucket.
        var projectName1 = randomName("project");
        var projectId1 = createProject(apiKey, workspaceName, projectName1);
        var dataset1 = createDatasetWithProject(apiKey, workspaceName, projectId1);
        var projectName2 = randomName("project");
        var projectId2 = createProject(apiKey, workspaceName, projectName2);
        var dataset2 = createDatasetWithProject(apiKey, workspaceName, projectId2);

        // BATCH_SIZE + 2 ambiguous prompts: cycle 1 fetches BATCH_SIZE (non-tail, all-ambiguous),
        // so the guard defers the trap. None get migrated either, so the workspace remains
        // eligible — which is the correctness contract we're protecting.
        var promptIds = new ArrayList<UUID>();
        for (int i = 0; i < BATCH_SIZE + 2; i++) {
            var link = createOrphanPromptVersion(apiKey, workspaceName);
            createExperimentInProject(apiKey, workspaceName, dataset1, projectId1, projectName1, link, null);
            createExperimentInProject(apiKey, workspaceName, dataset2, projectId2, projectName2, link, null);
            promptIds.add(link.promptId());
        }

        migrationService.runMigrationCycle().block();

        assertThat(countMigrated(apiKey, workspaceName, promptIds))
                .as("cycle 1 must not migrate any ambiguous prompt")
                .isZero();
        assertWorkspaceNotTrapped(workspacesService, workspaceId);
    }

    /**
     * Partial-batch mixed buckets: a workspace with more orphans than {@code promptBatchSize}
     * whose current batch contains both certain and ambiguous prompts must migrate the certain
     * prompts and NOT trap on the post-write decision — the workspace still has more orphans
     * (including the ambiguous remainder) for the next cycle. Without the {@code isTailBatch}
     * guard the post-write trap fires and strands the rest.
     */
    @Test
    void partialBatchMixedAmbiguousAndCertainDoesNotTrap(WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // One alive project for the certain bucket, two alive projects for the ambiguous bucket.
        var certainProjectName = randomName("project");
        var certainProjectId = createProject(apiKey, workspaceName, certainProjectName);
        var certainDataset = createDatasetWithProject(apiKey, workspaceName, certainProjectId);
        var ambiguousProjectName1 = randomName("project");
        var ambiguousProjectId1 = createProject(apiKey, workspaceName, ambiguousProjectName1);
        var ambiguousDataset1 = createDatasetWithProject(apiKey, workspaceName, ambiguousProjectId1);
        var ambiguousProjectName2 = randomName("project");
        var ambiguousProjectId2 = createProject(apiKey, workspaceName, ambiguousProjectName2);
        var ambiguousDataset2 = createDatasetWithProject(apiKey, workspaceName, ambiguousProjectId2);

        // 51 certain + 51 ambiguous = BATCH_SIZE + 2 total. Pigeonhole: any 100-of-102 slice must
        // include at least 49 of each bucket — cycle 1's batch is guaranteed to have both certain
        // and ambiguous prompts regardless of MySQL fetch order.
        int perBucket = (BATCH_SIZE + 2) / 2;
        var certainPromptIds = new ArrayList<UUID>();
        for (int i = 0; i < perBucket; i++) {
            var link = createOrphanPromptVersion(apiKey, workspaceName);
            createExperimentInProject(apiKey, workspaceName, certainDataset, certainProjectId, certainProjectName,
                    link, null);
            certainPromptIds.add(link.promptId());
        }
        var ambiguousPromptIds = new ArrayList<UUID>();
        for (int i = 0; i < perBucket; i++) {
            var link = createOrphanPromptVersion(apiKey, workspaceName);
            createExperimentInProject(apiKey, workspaceName, ambiguousDataset1, ambiguousProjectId1,
                    ambiguousProjectName1, link, null);
            createExperimentInProject(apiKey, workspaceName, ambiguousDataset2, ambiguousProjectId2,
                    ambiguousProjectName2, link, null);
            ambiguousPromptIds.add(link.promptId());
        }

        migrationService.runMigrationCycle().block();

        assertThat(countMigrated(apiKey, workspaceName, certainPromptIds))
                .as("cycle 1 must migrate at least some certain prompts in the batch")
                .isPositive();
        assertThat(countMigrated(apiKey, workspaceName, ambiguousPromptIds))
                .as("ambiguous prompts are never written by the cycle")
                .isZero();
        assertWorkspaceNotTrapped(workspacesService, workspaceId);
    }

    /**
     * Edge case for {@code mergeAssignments}: when the workspace's Default Project happens to
     * coincide with a {@code certainAssignments} key (an experiment legitimately references the
     * Default Project), the merge function unions the two prompt sets rather than letting either
     * side shadow the other. Both the certain and the no-inference prompt should land on the
     * Default Project.
     */
    @Test
    void defaultProjectKeyCollisionUnionsCertainAndNoInferencePromptSets() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        // Pre-create Default Project so the certain-experiment references it as its alive project.
        var defaultProjectId = createProject(apiKey, workspaceName, ProjectService.DEFAULT_PROJECT);
        var datasetName = createDatasetWithProject(apiKey, workspaceName, defaultProjectId);

        // Certain: prompt referenced by an experiment pointing at Default Project → its key in
        // certainAssignments collides with the Default Project key produced by getDefaultAssignments.
        var certainLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, datasetName, defaultProjectId,
                ProjectService.DEFAULT_PROJECT, certainLink, null);

        // No-inference: prompt with no referencing experiment → routed to Default by
        // getDefaultAssignments, hitting the same key.
        var noInferenceLink = createOrphanPromptVersion(apiKey, workspaceName);

        var certainBefore = promptResourceClient.getPrompt(certainLink.promptId(), apiKey, workspaceName);
        var noInferenceBefore = promptResourceClient.getPrompt(noInferenceLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        // Both prompts must land on the Default Project — neither shadows the other.
        assertPromptMigratedTo(apiKey, workspaceName, certainLink.promptId(), certainBefore, defaultProjectId);
        assertPromptMigratedTo(apiKey, workspaceName, noInferenceLink.promptId(), noInferenceBefore,
                defaultProjectId);
    }

    @Test
    void secondCycleIsNoopAfterSuccessfulMigration() {
        // Idempotency: after the first cycle assigns a prompt, a second cycle must not re-write
        // it (eligibility excludes it, batch UPDATE's project_id IS NULL guard would prevent any
        // double write even if it ran). Asserted via full-row equality between cycles.
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        var projectId = createProject(apiKey, workspaceName, projectName);
        var datasetName = createDatasetWithProject(apiKey, workspaceName, projectId);
        var promptLink = createOrphanPromptVersion(apiKey, workspaceName);
        createExperimentInProject(apiKey, workspaceName, datasetName, projectId, projectName,
                promptLink, null);

        var before = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptMigratedTo(apiKey, workspaceName, promptLink.promptId(), before, projectId);
        var afterFirst = promptResourceClient.getPrompt(promptLink.promptId(), apiKey, workspaceName);

        migrationService.runMigrationCycle().block();

        assertPromptUnchanged(apiKey, workspaceName, promptLink.promptId(), afterFirst);
    }

    private UUID createProject(String apiKey, String workspaceName, String projectName) {
        return projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(),
                apiKey, workspaceName);
    }

    private String createDatasetWithProject(String apiKey, String workspaceName, UUID projectId) {
        var datasetName = randomName("dataset");
        datasetResourceClient.createDataset(
                factory.manufacturePojo(Dataset.class).toBuilder()
                        .name(datasetName)
                        .projectId(projectId)
                        .projectName(null)
                        .build(),
                apiKey, workspaceName);
        return datasetName;
    }

    private PromptVersionLink createOrphanPromptVersion(String apiKey, String workspaceName) {
        var prompt = PromptResourceClient.buildPrompt(factory);
        var version = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
        return buildVersionLink(version, prompt.name());
    }

    private static PromptVersionLink buildVersionLink(PromptVersion promptVersion, String promptName) {
        return PromptVersionLink.builder()
                .id(promptVersion.id())
                .commit(promptVersion.commit())
                .promptId(promptVersion.promptId())
                .promptName(promptName)
                .build();
    }

    private void createExperimentInProject(
            String apiKey,
            String workspaceName,
            String datasetName,
            UUID projectId,
            String projectName,
            PromptVersionLink promptVersion,
            List<PromptVersionLink> promptVersions) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .id(null)
                .datasetName(datasetName)
                .projectId(projectId)
                .projectName(projectName)
                .promptVersion(promptVersion)
                .promptVersions(promptVersions)
                .build();
        experimentResourceClient.create(experiment, apiKey, workspaceName);
    }

    private void assertPromptMigratedToDefault(
            String apiKey,
            String workspaceName,
            UUID promptId,
            Prompt beforeMigration) {
        var actual = promptResourceClient.getPrompt(promptId, apiKey, workspaceName);
        assertThat(actual.projectId()).isNotNull();
        var project = projectResourceClient.getProject(actual.projectId(), apiKey, workspaceName);
        assertThat(project.name()).isEqualTo(ProjectService.DEFAULT_PROJECT);
        assertPromptMigratedTo(apiKey, workspaceName, promptId, beforeMigration, actual.projectId());
    }

    /**
     * Common post-cycle check for any prompt the migration successfully assigned: full equality
     * against {@code beforeMigration} via {@link PromptTestAssertions#assertPromptEqual} (with
     * the expected projectId / lastUpdatedBy patched onto the snapshot — {@code lastUpdatedAt}
     * is also copied from the actual row since it's auto-updated by the schema), then the
     * isAfter check pins the timestamp progression that's specific to the "row was written" case.
     */
    private void assertPromptMigratedTo(
            String apiKey,
            String workspaceName,
            UUID promptId,
            Prompt beforeMigration,
            UUID expectedProjectId) {
        var actual = promptResourceClient.getPrompt(promptId, apiKey, workspaceName);
        var expected = beforeMigration.toBuilder()
                .projectId(expectedProjectId)
                .lastUpdatedBy(RequestContext.SYSTEM_USER)
                .lastUpdatedAt(actual.lastUpdatedAt())
                .build();
        PromptTestAssertions.assertPromptEqual(actual, expected);
        assertThat(actual.lastUpdatedAt()).isAfter(beforeMigration.lastUpdatedAt());
    }

    private void assertPromptUnchanged(
            String apiKey,
            String workspaceName,
            UUID promptId,
            Prompt beforeMigration) {
        var actual = promptResourceClient.getPrompt(promptId, apiKey, workspaceName);
        PromptTestAssertions.assertPromptEqual(actual, beforeMigration);
    }

    private void assertWorkspaceTrapped(WorkspacesService workspacesService, String workspaceId, String reason) {
        assertThat(workspacesService.findPromptProjectMigrationSkippedWorkspaceIds()).contains(workspaceId);
        assertThat(workspacesService.findById(workspaceId))
                .hasValueSatisfying(w -> assertThat(w.promptProjectMigrationSkipReason()).isEqualTo(reason));
    }

    private void assertWorkspaceNotTrapped(WorkspacesService workspacesService, String workspaceId) {
        assertThat(workspacesService.findPromptProjectMigrationSkippedWorkspaceIds()).doesNotContain(workspaceId);
    }

    private long countMigrated(String apiKey, String workspaceName, List<UUID> promptIds) {
        return promptIds.stream()
                .map(id -> promptResourceClient.getPrompt(id, apiKey, workspaceName).projectId())
                .filter(java.util.Objects::nonNull)
                .count();
    }

    private String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }
}
