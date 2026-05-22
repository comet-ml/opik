package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.Project;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY;
import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AlertProjectMigrationServiceTest {

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
                                        "%s,%s".formatted(EXCLUDED_WORKSPACE_ID_1, EXCLUDED_WORKSPACE_ID_2))))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private AlertResourceClient alertResourceClient;
    private ProjectResourceClient projectResourceClient;
    private AlertProjectMigrationService migrationService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, AlertProjectMigrationService migrationService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        alertResourceClient = new AlertResourceClient(clientSupport);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.migrationService = migrationService;
    }

    // --- Tests ---

    @Test
    void workspaceWideAlertAssignedToDefaultProject() {
        var ws = newWorkspace();
        var defaultProjectId = createProject(ws, ProjectService.DEFAULT_PROJECT);

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                workspaceWideTrigger(AlertEventType.PROMPT_CREATED),
                workspaceWideTrigger(AlertEventType.PROMPT_COMMITTED)));

        migrationService.runMigrationCycle().block();

        var alert = getAlert(ws, alertId);
        assertThat(alert.projectId()).isEqualTo(defaultProjectId);
        assertThat(alert.triggers()).hasSize(2);
        assertNoScopeProjectConfig(alert);
    }

    @Test
    void singleProjectAlertMigratedInPlace() {
        var ws = newWorkspace();
        var p1 = createProject(ws, randomName("project"));

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, p1)));

        migrationService.runMigrationCycle().block();

        var alert = getAlert(ws, alertId);
        assertThat(alert.projectId()).isEqualTo(p1);
        assertThat(alert.triggers()).hasSize(1);
        assertNoScopeProjectConfig(alert);
    }

    @Test
    void singleProjectWithWorkspaceWideTriggerSplitsDefaultAlert() {
        var ws = newWorkspace();
        var p1 = createProject(ws, randomName("project"));
        var defaultProjectId = createProject(ws, ProjectService.DEFAULT_PROJECT);

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, p1),
                workspaceWideTrigger(AlertEventType.PROMPT_COMMITTED)));

        migrationService.runMigrationCycle().block();

        // Original alert should be scoped to p1 with only the scoped trigger
        var originalAlert = getAlert(ws, alertId);
        assertThat(originalAlert.projectId()).isEqualTo(p1);
        assertThat(originalAlert.triggers()).hasSize(1);
        assertThat(originalAlert.triggers().getFirst().eventType()).isEqualTo(AlertEventType.PROMPT_CREATED);
        assertNoScopeProjectConfig(originalAlert);

        // A new alert should have been created for the Default Project with the workspace-wide trigger
        var defaultAlerts = getAlertsForProject(ws, defaultProjectId);
        assertThat(defaultAlerts).hasSize(1);
        var defaultAlert = defaultAlerts.getFirst();
        assertThat(defaultAlert.projectId()).isEqualTo(defaultProjectId);
        assertThat(defaultAlert.triggers()).hasSize(1);
        assertThat(defaultAlert.triggers().getFirst().eventType()).isEqualTo(AlertEventType.PROMPT_COMMITTED);
        assertNoScopeProjectConfig(defaultAlert);
    }

    @Test
    void multiProjectAlertSplitByProject() {
        var ws = newWorkspace();
        var p1 = createProject(ws, randomName("project"));
        var p2 = createProject(ws, randomName("project"));
        var firstId = lexFirst(p1, p2);
        var secondId = firstId.equals(p1) ? p2 : p1;

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, p1),
                scopedTrigger(AlertEventType.PROMPT_COMMITTED, p2)));

        migrationService.runMigrationCycle().block();

        // Original alert keeps first (lex) project's trigger
        var originalAlert = getAlert(ws, alertId);
        assertThat(originalAlert.projectId()).isEqualTo(firstId);
        assertThat(originalAlert.triggers()).hasSize(1);
        assertNoScopeProjectConfig(originalAlert);

        // A new alert was created for the second project
        var secondAlerts = getAlertsForProject(ws, secondId);
        assertThat(secondAlerts).hasSize(1);
        var secondAlert = secondAlerts.getFirst();
        assertThat(secondAlert.projectId()).isEqualTo(secondId);
        assertThat(secondAlert.triggers()).hasSize(1);
        assertNoScopeProjectConfig(secondAlert);
    }

    @Test
    void multiProjectWithWorkspaceWideTriggerCreatesThreeAlerts() {
        var ws = newWorkspace();
        var p1 = createProject(ws, randomName("project"));
        var p2 = createProject(ws, randomName("project"));
        var defaultProjectId = createProject(ws, ProjectService.DEFAULT_PROJECT);
        var firstId = lexFirst(p1, p2);
        var secondId = firstId.equals(p1) ? p2 : p1;

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, p1),
                scopedTrigger(AlertEventType.PROMPT_COMMITTED, p2),
                workspaceWideTrigger(AlertEventType.PROMPT_DELETED)));

        migrationService.runMigrationCycle().block();

        // Original alert: assigned to lex-first project, 1 trigger, no scope
        var originalAlert = getAlert(ws, alertId);
        assertThat(originalAlert.projectId()).isEqualTo(firstId);
        assertThat(originalAlert.triggers()).hasSize(1);
        assertNoScopeProjectConfig(originalAlert);

        // Second project: 1 trigger
        var secondAlerts = getAlertsForProject(ws, secondId);
        assertThat(secondAlerts).hasSize(1);
        assertThat(secondAlerts.getFirst().triggers()).hasSize(1);
        assertNoScopeProjectConfig(secondAlerts.getFirst());

        // Default Project: 1 trigger (workspace-wide)
        var defaultAlerts = getAlertsForProject(ws, defaultProjectId);
        assertThat(defaultAlerts).hasSize(1);
        assertThat(defaultAlerts.getFirst().projectId()).isEqualTo(defaultProjectId);
        assertThat(defaultAlerts.getFirst().triggers()).hasSize(1);
        assertThat(defaultAlerts.getFirst().triggers().getFirst().eventType())
                .isEqualTo(AlertEventType.PROMPT_DELETED);
        assertNoScopeProjectConfig(defaultAlerts.getFirst());
    }

    @Test
    void allProjectsDeletedAlertAssignedToDefault() {
        var ws = newWorkspace();
        var defaultProjectId = createProject(ws, ProjectService.DEFAULT_PROJECT);
        var nonExistentProjectId = UUID.randomUUID();

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, nonExistentProjectId)));

        migrationService.runMigrationCycle().block();

        var alert = getAlert(ws, alertId);
        assertThat(alert.projectId()).isEqualTo(defaultProjectId);
        assertNoScopeProjectConfig(alert);
    }

    @Test
    void missingDefaultProjectIsAutoCreatedDuringMigration() {
        var ws = newWorkspace();
        // Default Project intentionally NOT pre-created — the migration must create it on demand.

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                workspaceWideTrigger(AlertEventType.PROMPT_CREATED)));

        migrationService.runMigrationCycle().block();

        var alert = getAlert(ws, alertId);
        assertThat(alert.projectId()).isNotNull();

        var defaultProject = projectResourceClient.getProject(alert.projectId(), ws.apiKey(), ws.workspaceName());
        assertThat(defaultProject.name()).isEqualTo(ProjectService.DEFAULT_PROJECT);
    }

    @Test
    void envExcludedWorkspacesAreNotMigrated() {
        var ws = new WorkspaceCtx(randomName("api-key"), randomName("workspace"), EXCLUDED_WORKSPACE_ID_1);
        mockTargetWorkspace(wireMock.server(), ws.apiKey(), ws.workspaceName(), ws.workspaceId(), randomName("user"));

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                workspaceWideTrigger(AlertEventType.PROMPT_CREATED)));

        migrationService.runMigrationCycle().block();

        var alert = getAlert(ws, alertId);
        assertThat(alert.projectId()).isNull();
    }

    @Test
    void secondCycleIsNoopAfterMigration() {
        var ws = newWorkspace();
        var p1 = createProject(ws, randomName("project"));

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, p1)));

        migrationService.runMigrationCycle().block();

        var afterFirstCycle = getAlert(ws, alertId);
        assertThat(afterFirstCycle.projectId()).isEqualTo(p1);

        migrationService.runMigrationCycle().block();

        var afterSecondCycle = getAlert(ws, alertId);
        assertThat(afterSecondCycle.lastUpdatedAt()).isEqualTo(afterFirstCycle.lastUpdatedAt());
    }

    @Test
    void postMigrationAlertIsEditableViaApi() {
        var ws = newWorkspace();
        var p1 = createProject(ws, randomName("project"));

        var alertId = createOrphanAlert(ws, randomName("alert"), List.of(
                scopedTrigger(AlertEventType.PROMPT_CREATED, p1)));

        migrationService.runMigrationCycle().block();

        var migrated = getAlert(ws, alertId);
        assertThat(migrated.projectId()).isEqualTo(p1);
        assertNoScopeProjectConfig(migrated);

        // Update the alert: set project_id explicitly (scope:project already gone after migration)
        var updatedAlert = migrated.toBuilder()
                .projectId(p1)
                .triggers(migrated.triggers().stream()
                        .map(t -> t.toBuilder()
                                .createdBy(null)
                                .createdAt(null)
                                .build())
                        .toList())
                .build();

        alertResourceClient.updateAlert(alertId, updatedAlert, ws.apiKey(), ws.workspaceName(),
                HttpStatus.SC_NO_CONTENT);
    }

    // --- Helper types and methods ---

    private record WorkspaceCtx(String apiKey, String workspaceName, String workspaceId) {
    }

    private WorkspaceCtx newWorkspace() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));
        return new WorkspaceCtx(apiKey, workspaceName, workspaceId);
    }

    private UUID createProject(WorkspaceCtx ws, String name) {
        return projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder().name(name).build(),
                ws.apiKey(), ws.workspaceName());
    }

    private UUID createOrphanAlert(WorkspaceCtx ws, String name, List<AlertTrigger> triggers) {
        var webhook = factory.manufacturePojo(Webhook.class).toBuilder()
                .createdBy(null)
                .createdAt(null)
                .secretToken(UUID.randomUUID().toString())
                .build();

        var alert = Alert.builder()
                .name(name)
                .alertType(AlertType.GENERAL)
                .enabled(true)
                .webhook(webhook)
                .triggers(triggers)
                .projectId(null)
                .build();

        return alertResourceClient.createAlert(alert, ws.apiKey(), ws.workspaceName(), HttpStatus.SC_CREATED);
    }

    private static AlertTrigger scopedTrigger(AlertEventType eventType, UUID... projectIds) {
        var scopeConfig = AlertTriggerConfig.builder()
                .type(AlertTriggerConfigType.SCOPE_PROJECT)
                .configValue(Map.of(PROJECT_IDS_CONFIG_KEY,
                        JsonUtils.writeValueAsString(Set.of(projectIds))))
                .build();

        return AlertTrigger.builder()
                .eventType(eventType)
                .triggerConfigs(List.of(scopeConfig))
                .build();
    }

    private static AlertTrigger workspaceWideTrigger(AlertEventType eventType) {
        return AlertTrigger.builder()
                .eventType(eventType)
                .triggerConfigs(List.of())
                .build();
    }

    private Alert getAlert(WorkspaceCtx ws, UUID alertId) {
        return alertResourceClient.getAlertById(alertId, ws.apiKey(), ws.workspaceName(), HttpStatus.SC_OK);
    }

    private List<Alert> getAlertsForProject(WorkspaceCtx ws, UUID projectId) {
        return alertResourceClient
                .findAlertsByProject(projectId, ws.apiKey(), ws.workspaceName(), 1, 100, HttpStatus.SC_OK)
                .content();
    }

    private static void assertNoScopeProjectConfig(Alert alert) {
        if (alert.triggers() == null) {
            return;
        }
        for (var trigger : alert.triggers()) {
            if (trigger.triggerConfigs() == null) {
                continue;
            }
            assertThat(trigger.triggerConfigs())
                    .noneMatch(c -> c.type() == AlertTriggerConfigType.SCOPE_PROJECT);
        }
    }

    private static UUID lexFirst(UUID... ids) {
        return Arrays.stream(ids)
                .min(Comparator.comparing(UUID::toString))
                .orElseThrow();
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }
}
