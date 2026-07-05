package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.ReportFailure;
import com.comet.opik.api.ReportFailureType;
import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.resources.AgentInsightsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.ReportFailureResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.v1.jobs.AgentInsightsReportJob;
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    private record Trigger(UUID projectId, String workspaceId, Instant periodStart, Instant periodEnd,
            String triggerSource) {
    }

    // Recording client bound in place of the platform default, to capture triggers fired via the queue.
    private static final List<Trigger> TRIGGERS = new CopyOnWriteArrayList<>();
    private static final AgentInsightsReportClient RECORDING_CLIENT = (reportId, projectId, workspaceId,
            periodStart, periodEnd, triggerSource) -> TRIGGERS.add(
                    new Trigger(projectId, workspaceId, periodStart, periodEnd, triggerSource));

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
                // Enable the Agent Insights feature so the publisher publishes and the subscriber consumes.
                .customConfigs(List.of(
                        new TestDropwizardAppExtensionUtils.CustomConfig("serviceToggles.agentInsightsEnabled",
                                "true")))
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
    private TraceResourceClient traceResourceClient;
    private AgentInsightsJobResourceClient jobsClient;
    private AgentInsightsResourceClient insightsClient;
    private ReportFailureResourceClient reportFailuresClient;
    private AgentInsightsReportJob reportJob;

    @BeforeAll
    void beforeAll(ClientSupport client, Injector injector) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.jobsClient = new AgentInsightsJobResourceClient(client, baseURI);
        this.insightsClient = new AgentInsightsResourceClient(client);
        this.reportFailuresClient = new ReportFailureResourceClient(client);
        this.reportJob = injector.getInstance(AgentInsightsReportJob.class);

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
    @DisplayName("Create makes the job (201); creating again returns 409")
    void create__firstThenConflict() {
        var projectId = createProject();

        try (var first = jobsClient.create(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(first.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            var job = first.readEntity(AgentInsightsJob.class);
            assertThat(job.id()).isNotNull();
            assertThat(job.projectId()).isEqualTo(projectId);
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.ENABLED);
            // Audit columns are populated from the auth context / DB defaults.
            assertThat(job.createdBy()).isEqualTo(USER);
            assertThat(job.lastUpdatedBy()).isEqualTo(USER);
            assertThat(job.createdAt()).isNotNull();
            assertThat(job.lastUpdatedAt()).isNotNull();
        }

        // Create is not idempotent: a second create for the same project conflicts.
        try (var second = jobsClient.create(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(second.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
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

    @Test
    @DisplayName("Trigger fires exactly one run via the queue; create alone does not")
    void trigger__firesRunOnce() {
        var projectId = createProject();

        // Create does NOT trigger a run.
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();
        assertThat(TRIGGERS.stream().anyMatch(t -> t.projectId().equals(projectId))).isFalse();

        // The trigger endpoint accepts the request (202) and fires the run via the bounded queue.
        jobsClient.trigger(projectId, API_KEY, WORKSPACE_NAME).close();

        await().atMost(10, SECONDS).untilAsserted(() -> assertThat(
                TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList()).hasSize(1));
        var trigger = TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).findFirst().orElseThrow();
        assertThat(trigger.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(trigger.periodStart()).isBefore(trigger.periodEnd());
        assertThat(trigger.triggerSource()).isEqualTo("manual");
    }

    // Failures are recorded through the report-failures endpoint (type=agent_insights, project_id=project),
    // exactly as Ollie does; the job then surfaces the latest one via its query.
    private ReportFailure agentInsightsFailure(UUID projectId, String reason, String detail) {
        return ReportFailure.builder()
                .type(ReportFailureType.AGENT_INSIGHTS)
                .projectId(projectId)
                .reason(reason)
                .detail(detail)
                .build();
    }

    @Test
    @DisplayName("Run failure is surfaced on the job and cleared by the next successful report")
    void runFailure__recordedThenClearedOnSuccess() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        reportFailuresClient.create(
                agentInsightsFailure(projectId, "out_of_credits", "anthropic 402: insufficient credits"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);

        try (var afterFailure = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(afterFailure.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var job = afterFailure.readEntity(AgentInsightsJob.class);
            assertThat(job.lastFailureReason()).isEqualTo("out_of_credits");
            assertThat(job.lastFailureDetail()).isEqualTo("anthropic 402: insufficient credits");
            assertThat(job.lastFailedAt()).isNotNull();
        }

        // An all-clear report is a successful run; it advances last_scan_at and supersedes the failure.
        insightsClient.reportIssues(
                AgentInsightsReport.builder().projectId(projectId).reportDay(LocalDate.now()).issues(List.of())
                        .build(),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_NO_CONTENT);

        try (var afterSuccess = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(afterSuccess.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var job = afterSuccess.readEntity(AgentInsightsJob.class);
            assertThat(job.lastScanAt()).isNotNull();
            assertThat(job.lastFailureReason()).isNull();
            assertThat(job.lastFailureDetail()).isNull();
            assertThat(job.lastFailedAt()).isNull();
        }
    }

    @Test
    @DisplayName("Failures accumulate as history; the job surfaces the most recent one")
    void runFailure__multipleFailures__latestSurfaced() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        reportFailuresClient.create(agentInsightsFailure(projectId, "rate_limited", "429 first"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);
        reportFailuresClient.create(agentInsightsFailure(projectId, "out_of_credits", "402 latest"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);

        // Both rows are appended to report_failures (history); the job surfaces the latest.
        try (var resp = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            var job = resp.readEntity(AgentInsightsJob.class);
            assertThat(job.lastFailureReason()).isEqualTo("out_of_credits");
            assertThat(job.lastFailureDetail()).isEqualTo("402 latest");
        }
        assertThat(reportFailuresClient.find("agent_insights", projectId, API_KEY, WORKSPACE_NAME).total())
                .isEqualTo(2);
    }

    private static Stream<Arguments> invalidReportFailures() {
        return Stream.of(
                Arguments.of("missing type",
                        ReportFailure.builder().projectId(UUID.randomUUID()).reason("x").build()),
                Arguments.of("missing project id",
                        ReportFailure.builder().type(ReportFailureType.AGENT_INSIGHTS).reason("x").build()),
                Arguments.of("blank reason",
                        ReportFailure.builder().type(ReportFailureType.AGENT_INSIGHTS).projectId(UUID.randomUUID())
                                .reason("")
                                .build()));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidReportFailures")
    @DisplayName("Report failure with an invalid body fails validation (422)")
    void reportFailure__invalidBody__returns422(String name, ReportFailure body) {
        reportFailuresClient.create(body, API_KEY, WORKSPACE_NAME, 422);
    }

    @Test
    @DisplayName("Report failure with an unsupported type is rejected (400) before hitting the DB enum")
    void reportFailure__unsupportedType__returns400() {
        // Raw JSON: the typed DTO can't express an invalid enum, so post an unknown `type` directly.
        var body = """
                {"type": "not_a_real_type", "project_id": "%s", "reason": "x"}""".formatted(UUID.randomUUID());
        reportFailuresClient.createRaw(body, API_KEY, WORKSPACE_NAME, HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("Report failures are listed for the project, most recent first")
    void reportFailure__createAndRead() {
        var projectId = createProject();

        reportFailuresClient.create(agentInsightsFailure(projectId, "rate_limited", "first"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);
        reportFailuresClient.create(agentInsightsFailure(projectId, "out_of_credits", "latest"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);

        var page = reportFailuresClient.find("agent_insights", projectId, API_KEY, WORKSPACE_NAME);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);
        var latest = page.content().getFirst();
        assertThat(latest.type()).isEqualTo(ReportFailureType.AGENT_INSIGHTS);
        assertThat(latest.projectId()).isEqualTo(projectId);
        assertThat(latest.reason()).isEqualTo("out_of_credits");
        assertThat(latest.detail()).isEqualTo("latest");
        assertThat(latest.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Daily sweep triggers enabled jobs that had traces in the window")
    void cronSweep__triggersJobsWithTraces() {
        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();

        // Seed a trace so the sweep's trace gate passes for this project.
        var trace = podamFactory.manufacturePojo(Trace.class).toBuilder().projectName(projectName).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

        // Drive the real sweep over a window bracketing the just-ingested trace. Exercises the real queries
        // end-to-end: findAllEnabled (MySQL, JOIN projects) -> getProjectsWithTracesInRange (ClickHouse, one
        // tuple-IN query) -> publish (Redis) -> subscriber -> recording client.
        Instant now = Instant.now();
        reportJob.runSweep(now.minusSeconds(3600), now.plusSeconds(3600)).block();

        await().atMost(10, SECONDS).untilAsserted(() -> assertThat(
                TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList()).hasSize(1));
        var trigger = TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).findFirst().orElseThrow();
        assertThat(trigger.triggerSource()).isEqualTo("scheduled");
    }
}
