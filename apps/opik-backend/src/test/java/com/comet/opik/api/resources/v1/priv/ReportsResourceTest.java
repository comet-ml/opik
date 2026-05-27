package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ReportPreference;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.ReportsResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
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

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ReportsResourceTest {

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
    private ReportsResourceClient reportsResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
        this.reportsResourceClient = new ReportsResourceClient(client, baseURI);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Preferences")
    class Preferences {

        @Test
        @DisplayName("Returns empty body when no preference has been set")
        void getPreference__noPreference__returnsEmpty() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            try (var response = reportsResourceClient.getPreference(projectId, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }
        }

        @Test
        @DisplayName("Creates and returns preference on first update")
        void updatePreference__firstUpdate__createsPreference() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            try (var response = reportsResourceClient.updatePreference(projectId,
                    Map.of("enabled", true, "schedule_time", "07:00:00"), API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                var preference = response.readEntity(ReportPreference.class);
                assertThat(preference.enabled()).isTrue();
                assertThat(preference.scheduleTime()).isEqualTo("07:00:00");
            }
        }

        @Test
        @DisplayName("Preserves schedule_time when not provided in update")
        void updatePreference__partialUpdate__preservesScheduleTime() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            reportsResourceClient.updatePreference(projectId,
                    Map.of("enabled", true, "schedule_time", "14:30:00"), API_KEY, TEST_WORKSPACE_NAME).close();

            try (var response = reportsResourceClient.updatePreference(projectId,
                    Map.of("enabled", false), API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                var preference = response.readEntity(ReportPreference.class);
                assertThat(preference.enabled()).isFalse();
                assertThat(preference.scheduleTime()).isEqualTo("14:30:00");
            }
        }

        @Test
        @DisplayName("Preserves custom_prompt when not provided in update")
        void updatePreference__partialUpdate__preservesCustomPrompt() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            reportsResourceClient.updatePreference(projectId,
                    Map.of("enabled", true, "schedule_time", "07:00:00", "custom_prompt", "Focus on errors"),
                    API_KEY, TEST_WORKSPACE_NAME).close();

            try (var response = reportsResourceClient.updatePreference(projectId,
                    Map.of("enabled", true, "schedule_time", "07:00:00"), API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                var preference = response.readEntity(ReportPreference.class);
                assertThat(preference.customPrompt()).isEqualTo("Focus on errors");
            }
        }
    }

    @Nested
    @DisplayName("Reports")
    class Reports {

        @Test
        @DisplayName("Returns empty page for a project with no reports")
        void getReports__noReports__returnsEmptyPage() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            var page = reportsResourceClient.getReports(projectId, API_KEY, TEST_WORKSPACE_NAME);
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        @DisplayName("Generate returns 503 when report generation is not configured")
        void generateReport__notConfigured__returns503() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);

            try (var response = reportsResourceClient.generateReport(projectId, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_SERVICE_UNAVAILABLE);
            }
        }

        @Test
        @DisplayName("Complete callback returns 404 for non-existent report")
        void completeReport__nonExistentReport__returns404() {
            var projectId = projectResourceClient.createProject(
                    "project-" + UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME);
            var fakeReportId = UUID.randomUUID();

            try (var response = reportsResourceClient.completeReport(projectId, fakeReportId,
                    Map.of("content", "test", "status", "completed", "session_id", "sess-1"),
                    API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }
}
