package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentInsightsJob;
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
import com.comet.opik.api.resources.utils.resources.AgentInsightsJobResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AgentInsightsJobsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    // Second workspace — used to assert workspace isolation.
    private static final String API_KEY_2 = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_2 = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_2 = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER_2 = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    // Full stack: creating projects via the API exercises ClickHouse, so analytics containers are required.
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
                .minioUrl(minioUrl)
                .isMinIO(true)
                .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private AgentInsightsJobResourceClient jobsClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
        this.jobsClient = new AgentInsightsJobResourceClient(client, baseURI);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_2, WORKSPACE_NAME_2, WORKSPACE_ID_2, USER_2);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createProject() {
        return projectResourceClient.createProject("project-" + UUID.randomUUID(), API_KEY, WORKSPACE_NAME);
    }

    @Test
    @DisplayName("Create makes the job (201) and is idempotent (200, same row, on repeat)")
    void create__firstThenRepeat__createsThen200() {
        var projectId = createProject();

        UUID jobId;
        try (var first = jobsClient.create(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(first.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            var job = first.readEntity(AgentInsightsJob.class);
            jobId = job.id();
            assertThat(job.id()).isNotNull();
            assertThat(job.projectId()).isEqualTo(projectId);
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.ENABLED);
            // Audit columns are populated from the auth context / DB defaults.
            assertThat(job.createdBy()).isEqualTo(USER);
            assertThat(job.lastUpdatedBy()).isEqualTo(USER);
            assertThat(job.createdAt()).isNotNull();
            assertThat(job.lastUpdatedAt()).isNotNull();
            // No-op trigger client is bound by default, so no immediate run was recorded.
            assertThat(job.lastTriggeredAt()).isNull();
        }

        // Idempotent: enabling again does not create a duplicate (same id) and returns 200.
        try (var second = jobsClient.create(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(second.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var job = second.readEntity(AgentInsightsJob.class);
            assertThat(job.id()).isEqualTo(jobId);
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.ENABLED);
        }
    }

    @Test
    @DisplayName("Create for a non-existent project returns 404")
    void create__projectMissing__returns404() {
        try (var response = jobsClient.create(UUID.randomUUID(), API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Create without project_id fails validation (422)")
    void create__missingProjectId__returnsUnprocessableEntity() {
        try (var response = jobsClient.createRaw(Map.of(), API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("Get returns the job after create, 404 when none exists")
    void get__afterCreateAndWhenAbsent() {
        var projectId = createProject();

        try (var absent = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(absent.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }

        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        try (var present = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(present.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var job = present.readEntity(AgentInsightsJob.class);
            assertThat(job.projectId()).isEqualTo(projectId);
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.ENABLED);
        }
    }

    @Test
    @DisplayName("PATCH status=disabled flips status without deleting; 404 when absent")
    void update__disablesWithoutDeleting_andNotFoundWhenAbsent() {
        var projectId = createProject();

        // 404 before any job exists
        try (var missing = jobsClient.update(projectId, AgentInsightsJob.Status.DISABLED, API_KEY, WORKSPACE_NAME)) {
            assertThat(missing.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }

        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        try (var disabled = jobsClient.update(projectId, AgentInsightsJob.Status.DISABLED, API_KEY, WORKSPACE_NAME)) {
            assertThat(disabled.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(disabled.readEntity(AgentInsightsJob.class).status())
                    .isEqualTo(AgentInsightsJob.Status.DISABLED);
        }

        // Row is kept; status flipped to disabled (never deleted).
        try (var afterDisable = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(afterDisable.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(afterDisable.readEntity(AgentInsightsJob.class).status())
                    .isEqualTo(AgentInsightsJob.Status.DISABLED);
        }
    }

    @Test
    @DisplayName("PATCH can re-enable a disabled job")
    void update__canReEnable() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();
        jobsClient.update(projectId, AgentInsightsJob.Status.DISABLED, API_KEY, WORKSPACE_NAME).close();

        try (var reEnabled = jobsClient.update(projectId, AgentInsightsJob.Status.ENABLED, API_KEY, WORKSPACE_NAME)) {
            assertThat(reEnabled.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(reEnabled.readEntity(AgentInsightsJob.class).status())
                    .isEqualTo(AgentInsightsJob.Status.ENABLED);
        }
    }

    @Test
    @DisplayName("Trigger returns 202 for an existing job, 404 when absent")
    void trigger__acceptedForExistingJob_andNotFoundWhenAbsent() {
        var projectId = createProject();

        // 404 before the job exists
        try (var missing = jobsClient.trigger(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(missing.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }

        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        // No-op trigger client is bound by default, so the run is accepted but records nothing.
        try (var triggered = jobsClient.trigger(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(triggered.getStatus()).isEqualTo(HttpStatus.SC_ACCEPTED);
        }
    }

    @Test
    @DisplayName("Workspace isolation: a job created in one workspace is invisible to another")
    void workspaceIsolation__jobNotVisibleAcrossWorkspaces() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        try (var otherWorkspace = jobsClient.get(projectId, API_KEY_2, WORKSPACE_NAME_2)) {
            assertThat(otherWorkspace.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }
}
