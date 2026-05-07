package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.RecentActivity;
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
import com.comet.opik.api.resources.utils.resources.AgentConfigsResourceClient;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.RecentActivityResourceClient;
import com.comet.opik.domain.AgentBlueprint;
import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import com.comet.opik.domain.AgentConfigValue;
import com.comet.opik.domain.AgentConfigValue.ValueType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RecentActivityResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER, MINIO).join();

        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = newTestDropwizardAppExtension(TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .minioUrl(minioUrl)
                .isMinIO(true)
                .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private AgentConfigsResourceClient agentConfigsResourceClient;
    private RecentActivityResourceClient recentActivityResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, podamFactory);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.optimizationResourceClient = new OptimizationResourceClient(client, baseURI, podamFactory);
        this.agentConfigsResourceClient = new AgentConfigsResourceClient(client);
        this.recentActivityResourceClient = new RecentActivityResourceClient(client, baseURI);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Get recent activity")
    class GetRecentActivity {

        @Test
        @DisplayName("Returns empty content for a new project with no activity")
        void returnsEmptyForNewProject() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            var result = recentActivityResourceClient.getActivities(projectId, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(result).isEqualTo(RecentActivity.RecentActivityPage.builder()
                    .page(1).size(10).total(0).content(List.of()).build());
        }

        @Test
        @DisplayName("Returns all entity types sorted by date descending")
        void returnsAllEntityTypesSortedByDate() {
            var projectName = "project-" + UUID.randomUUID();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE_NAME);

            var datasetName = "dataset-" + UUID.randomUUID();
            datasetResourceClient.createDataset(Dataset.builder()
                    .name(datasetName).projectId(projectId).build(), API_KEY, TEST_WORKSPACE_NAME);

            var suiteName = "suite-" + UUID.randomUUID();
            datasetResourceClient.createDataset(Dataset.builder()
                    .name(suiteName).projectId(projectId).type(DatasetType.TEST_SUITE)
                    .build(), API_KEY, TEST_WORKSPACE_NAME);

            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName("exp-dataset-" + UUID.randomUUID())
                    .projectName(projectName).build();
            experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE_NAME);

            var optimization = optimizationResourceClient.createPartialOptimization()
                    .datasetName("opt-dataset-" + UUID.randomUUID())
                    .projectName(projectName).build();
            optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            var agentConfigValues = List.of(AgentConfigValue.builder()
                    .key("model").value("gpt-4").type(ValueType.STRING).build());
            agentConfigsResourceClient.createAgentConfig(AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("test")
                            .values(agentConfigValues).build())
                    .build(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_CREATED);

            var result = recentActivityResourceClient.getActivities(projectId, API_KEY, TEST_WORKSPACE_NAME);

            var content = result.content();
            assertThat(content).isNotEmpty();

            assertThat(content).anyMatch(item -> item.type() == RecentActivity.ActivityType.EXPERIMENT
                    && experiment.name().equals(item.name())
                    && item.resourceId() != null);
            assertThat(content).anyMatch(item -> item.type() == RecentActivity.ActivityType.OPTIMIZATION
                    && optimization.datasetName().equals(item.name()));
            assertThat(content).anyMatch(item -> item.type() == RecentActivity.ActivityType.DATASET_VERSION
                    && datasetName.equals(item.name()));
            assertThat(content).anyMatch(item -> item.type() == RecentActivity.ActivityType.TEST_SUITE_VERSION
                    && suiteName.equals(item.name()));
            assertThat(content).anyMatch(item -> item.type() == RecentActivity.ActivityType.AGENT_CONFIG_VERSION);

            content.forEach(item -> {
                assertThat(item.id()).isNotNull();
                assertThat(item.createdAt()).isNotNull();
                assertThat(item.createdBy()).isEqualTo(USER);
            });

            assertThat(content)
                    .extracting(RecentActivity.RecentActivityItem::createdAt)
                    .isSortedAccordingTo(Comparator.reverseOrder());
        }

        @Test
        @DisplayName("Does not return activity from other projects")
        void doesNotReturnActivityFromOtherProjects() {
            var projectName = "project-" + UUID.randomUUID();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE_NAME);

            var otherProjectName = "project-" + UUID.randomUUID();
            projectResourceClient.createProject(otherProjectName, API_KEY, TEST_WORKSPACE_NAME);

            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName("dataset-" + UUID.randomUUID())
                    .projectName(otherProjectName)
                    .build();
            experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE_NAME);

            var result = recentActivityResourceClient.getActivities(projectId, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(result.content()).noneMatch(
                    item -> experiment.name().equals(item.name()));
        }
    }

    @Nested
    @DisplayName("Required permissions")
    class RequiredPermissionsTest {

        @Test
        @DisplayName("Passes required permissions to auth endpoint")
        void passesRequiredPermissionsToAuthEndpoint() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

            wireMock.server().resetRequests();
            recentActivityResourceClient.callGetActivities(UUID.randomUUID(), apiKey, workspaceName).close();

            wireMock.server().verify(
                    postRequestedFor(urlPathEqualTo("/opik/auth"))
                            .withRequestBody(matchingJsonPath("$.requiredPermissions[0]",
                                    equalTo(WorkspaceUserPermission.PROJECT_DATA_VIEW.getValue()))));
        }

        @Test
        @DisplayName("Returns 403 when permission is denied")
        void returnsForbiddenWhenPermissionDenied() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();

            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    WorkspaceUserPermission.PROJECT_DATA_VIEW.getValue());

            try (var response = recentActivityResourceClient.callGetActivities(UUID.randomUUID(), apiKey,
                    workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        }
    }
}
