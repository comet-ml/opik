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
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the enabled trigger branch of enable() — the no-op default client (used by the other
 * test classes) never fires, so here we override the {@link AgentInsightsReportClient} binding with a
 * recording stub whose {@code isEnabled()} returns true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AgentInsightsJobTriggerTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private record Trigger(UUID projectId, String projectName, String workspaceName,
            Instant periodStart, Instant periodEnd) {
    }

    // Recording, always-enabled client bound in place of the no-op default.
    private static final List<Trigger> TRIGGERS = new CopyOnWriteArrayList<>();
    private static final AgentInsightsReportClient RECORDING_CLIENT = new AgentInsightsReportClient() {
        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void triggerAgentInsights(String reportId, UUID projectId, String projectName,
                String workspaceName, Instant periodStart, Instant periodEnd) {
            TRIGGERS.add(new Trigger(projectId, projectName, workspaceName, periodStart, periodEnd));
        }
    };

    // Full stack: creating a project via the API exercises ClickHouse, so analytics containers are required.
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
                .modules(List.of(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(AgentInsightsReportClient.class).toInstance(RECORDING_CLIENT);
                    }
                }))
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
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Enable triggers exactly one immediate run and records last_triggered_at")
    void enable__triggersImmediateRunOnce() {
        var projectId = projectResourceClient.createProject(
                "project-" + UUID.randomUUID(), API_KEY, WORKSPACE_NAME);

        jobsClient.enable(projectId, API_KEY, WORKSPACE_NAME).close();

        // The enabled client fired exactly once, for this project, with a non-empty time window.
        var forProject = TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList();
        assertThat(forProject).hasSize(1);
        var trigger = forProject.get(0);
        assertThat(trigger.workspaceName()).isEqualTo(WORKSPACE_NAME);
        assertThat(trigger.periodStart()).isNotNull();
        assertThat(trigger.periodEnd()).isNotNull();
        assertThat(trigger.periodStart()).isBefore(trigger.periodEnd());

        // markTriggered persisted the run time.
        try (var get = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(get.readEntity(AgentInsightsJob.class).lastTriggeredAt()).isNotNull();
        }
    }
}
