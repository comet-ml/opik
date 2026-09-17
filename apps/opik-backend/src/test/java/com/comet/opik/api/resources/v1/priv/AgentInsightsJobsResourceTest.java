package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentInsightsEnrollment;
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
import com.comet.opik.api.resources.v1.jobs.AgentInsightsAutoFirstRunJob;
import com.comet.opik.api.resources.v1.jobs.AgentInsightsReportJob;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.domain.AgentInsightsTriggerException;
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
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;
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
    // Projects the platform should reject for a spent free-run budget, as the real 402 + error_code would.
    private static final Set<UUID> FREE_POOL_EXHAUSTED_PROJECTS = ConcurrentHashMap.newKeySet();
    private static final AgentInsightsReportClient RECORDING_CLIENT = (reportId, projectId, workspaceId,
            periodStart, periodEnd, triggerSource) -> {
        if (FREE_POOL_EXHAUSTED_PROJECTS.contains(projectId)) {
            throw new AgentInsightsTriggerException(AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED,
                    "Free diagnostics budget exhausted");
        }
        TRIGGERS.add(new Trigger(projectId, workspaceId, periodStart, periodEnd, triggerSource));
    };

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
                        new TestDropwizardAppExtensionUtils.CustomConfig("serviceToggles.ollieEnabled",
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
    private AgentInsightsAutoFirstRunJob autoFirstRunJob;
    private AgentInsightsJobService jobService;

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
        this.autoFirstRunJob = injector.getInstance(AgentInsightsAutoFirstRunJob.class);
        this.jobService = injector.getInstance(AgentInsightsJobService.class);

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
    @DisplayName("Enrolment creates a job row for a project that has none, enrolled and disabled")
    void enrol__createsRowForProjectWithoutJob() {
        var projectId = createProject();

        try (var response = jobsClient.enrolInAutoFirstRun(true, List.of(projectId))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var result = response.readEntity(AgentInsightsEnrollment.Response.class);
            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.unknownProjectIds()).isEmpty();
            assertThat(result.alreadyRunProjectIds()).isEmpty();
        }

        try (var created = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            var job = created.readEntity(AgentInsightsJob.class);
            assertThat(job.autoFirstRunEnrolled()).isTrue();
            assertThat(job.autoFirstRunAt()).isNull();
            // Enrolment must not switch the daily schedule on.
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.DISABLED);
        }
    }

    @Test
    @DisplayName("Enrolment flags an existing job row without disturbing its status")
    void enrol__flagsExistingRow() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();
        jobsClient.update(projectId, AgentInsightsJob.Status.ENABLED, API_KEY, WORKSPACE_NAME).close();

        try (var response = jobsClient.enrolInAutoFirstRun(true, List.of(projectId))) {
            assertThat(response.readEntity(AgentInsightsEnrollment.Response.class).enrolled()).isEqualTo(1);
        }

        try (var updated = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            var job = updated.readEntity(AgentInsightsJob.class);
            assertThat(job.autoFirstRunEnrolled()).isTrue();
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.ENABLED);
        }
    }

    @Test
    @DisplayName("Enrolment is idempotent and reports ids that match no project")
    void enrol__isIdempotentAndReportsUnknownProjects() {
        var projectId = createProject();
        var unknownProjectId = UUID.randomUUID();

        jobsClient.enrolInAutoFirstRun(true, List.of(projectId)).close();

        try (var response = jobsClient.enrolInAutoFirstRun(true, List.of(projectId, unknownProjectId))) {
            var result = response.readEntity(AgentInsightsEnrollment.Response.class);
            assertThat(result.unknownProjectIds()).containsExactly(unknownProjectId);
            assertThat(result.alreadyRunProjectIds()).isEmpty();
        }

        try (var job = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(job.readEntity(AgentInsightsJob.class).autoFirstRunEnrolled()).isTrue();
        }
    }

    @Test
    @DisplayName("Enrolling with false clears enrolment")
    void enrol__falseClears() {
        var projectId = createProject();
        jobsClient.enrolInAutoFirstRun(true, List.of(projectId)).close();

        try (var response = jobsClient.enrolInAutoFirstRun(false, List.of(projectId))) {
            assertThat(response.readEntity(AgentInsightsEnrollment.Response.class).cleared()).isEqualTo(1);
        }

        try (var job = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(job.readEntity(AgentInsightsJob.class).autoFirstRunEnrolled()).isFalse();
        }
    }

    Stream<Arguments> invalidEnrolmentProjectIds() {
        return Stream.of(
                Arguments.of("empty list", (Function<UUID, List<UUID>>) projectId -> List.of()),
                Arguments.of("too many ids", (Function<UUID, List<UUID>>) projectId -> Stream.concat(
                        Stream.of(projectId),
                        Stream.generate(UUID::randomUUID).limit(AgentInsightsEnrollment.MAX_PROJECTS)).toList()),
                Arguments.of("null id", (Function<UUID, List<UUID>>) projectId -> Arrays.asList(projectId, null)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidEnrolmentProjectIds")
    @DisplayName("Enrolment with invalid project ids fails validation (422) and enrols nothing")
    void enrol__invalidProjectIds__isRejected(String name, Function<UUID, List<UUID>> projectIds) {
        var projectId = createProject();

        try (var response = jobsClient.enrolInAutoFirstRun(true, projectIds.apply(projectId))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        try (var job = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(job.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
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
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.DISABLED);
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
            assertThat(job.status()).isEqualTo(AgentInsightsJob.Status.DISABLED);
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
    @DisplayName("Auto-first-run sweep runs an enrolled project past the threshold, once")
    void autoFirstRunSweep__runsEnrolledProjectOverThreshold() {
        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        jobsClient.enrolInAutoFirstRun(true, List.of(projectId)).close();

        // The sweep only picks up projects past MIN_TRACES within its window.
        var traces = IntStream.range(0, AgentInsightsAutoFirstRunJob.MIN_TRACES)
                .mapToObj(__ -> podamFactory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .build())
                .toList();
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        // Exercises the real chain: findAwaitingFirstRun (MySQL) -> min-traces count (ClickHouse, tuple-IN)
        // -> publish (Redis) -> subscriber -> recording client.
        autoFirstRunJob.runSweep(Instant.now(), 10).block();

        await().atMost(10, SECONDS).untilAsserted(() -> assertThat(
                TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList()).hasSize(1));
        assertThat(TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).findFirst().orElseThrow()
                .triggerSource()).isEqualTo("auto_first_run");

        // Stamped at enqueue, which is what drops the project out of the candidate set.
        try (var afterRun = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(afterRun.readEntity(AgentInsightsJob.class).autoFirstRunAt()).isNotNull();
        }

        // A second sweep must not run it again.
        autoFirstRunJob.runSweep(Instant.now(), 10).block();
        assertThat(TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList()).hasSize(1);

        // And re-enrolling reports it rather than relabelling it.
        try (var response = jobsClient.enrolInAutoFirstRun(true, List.of(projectId))) {
            var result = response.readEntity(AgentInsightsEnrollment.Response.class);
            assertThat(result.alreadyRunProjectIds()).containsExactly(projectId);
            assertThat(result.enrolled()).isZero();
        }
    }

    @Test
    @DisplayName("A spent free-run budget cancels the rollout instead of failing the project that hit it")
    void autoFirstRunSweep__freePoolExhausted__cancelsRollout() {
        String projectName = "project-" + UUID.randomUUID();
        var runningProjectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var waitingProjectId = createProject();
        jobsClient.enrolInAutoFirstRun(true, List.of(runningProjectId, waitingProjectId)).close();
        FREE_POOL_EXHAUSTED_PROJECTS.add(runningProjectId);

        // Only the first project is past the threshold, so it is the one that probes the budget.
        var traces = IntStream.range(0, AgentInsightsAutoFirstRunJob.MIN_TRACES)
                .mapToObj(__ -> podamFactory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .build())
                .toList();
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        autoFirstRunJob.runSweep(Instant.now(), 10).block();

        // The project that hit the limit is unwound: no enqueue stamp left to read as a run in flight, and
        // no failure recorded against it — it did nothing wrong.
        await().atMost(10, SECONDS).untilAsserted(() -> {
            try (var response = jobsClient.get(runningProjectId, API_KEY, WORKSPACE_NAME)) {
                var job = response.readEntity(AgentInsightsJob.class);
                assertThat(job.autoFirstRunEnrolled()).isFalse();
                assertThat(job.autoFirstRunAt()).isNull();
                assertThat(job.lastFailureReason()).isNull();
            }
        });

        // And everyone else still owed a run is unenrolled, without having to probe the budget themselves.
        try (var response = jobsClient.get(waitingProjectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(response.readEntity(AgentInsightsJob.class).autoFirstRunEnrolled()).isFalse();
        }

        // A later sweep has nothing left to run.
        autoFirstRunJob.runSweep(Instant.now(), 10).block();
        assertThat(TRIGGERS.stream().filter(t -> t.projectId().equals(runningProjectId)).toList()).isEmpty();
    }

    @Test
    @DisplayName("When the free-run budget runs out mid-batch, every rejected project is unwound, not just the first")
    void autoFirstRunSweep__freePoolExhaustedForSeveralInOneSweep__unwindsEveryRejectedProject() {
        // The sweep enqueues several projects at once and stamps each before publishing, so when the budget is
        // already gone they are all rejected together. The first rejection cancels the rollout for everyone;
        // the ones after it must still clear their own stamp, or they read as already run forever.
        var rejectedProjectIds = IntStream.range(0, 3)
                .mapToObj(__ -> {
                    String projectName = "project-" + UUID.randomUUID();
                    var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
                    var traces = IntStream.range(0, AgentInsightsAutoFirstRunJob.MIN_TRACES)
                            .mapToObj(___ -> podamFactory.manufacturePojo(Trace.class).toBuilder()
                                    .projectName(projectName)
                                    .build())
                            .toList();
                    traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
                    FREE_POOL_EXHAUSTED_PROJECTS.add(projectId);
                    return projectId;
                })
                .toList();
        jobsClient.enrolInAutoFirstRun(true, rejectedProjectIds).close();

        autoFirstRunJob.runSweep(Instant.now(), 10).block();

        await().atMost(10, SECONDS).untilAsserted(() -> rejectedProjectIds.forEach(projectId -> {
            try (var response = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
                var job = response.readEntity(AgentInsightsJob.class);
                assertThat(job.autoFirstRunEnrolled()).as("enrolled for %s", projectId).isFalse();
                assertThat(job.autoFirstRunAt()).as("enqueue stamp for %s", projectId).isNull();
            }
        }));
    }

    @Test
    @DisplayName("Auto-first-run sweep ignores a project that is not enrolled")
    void autoFirstRunSweep__ignoresProjectNotEnrolled() {
        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var traces = IntStream.range(0, AgentInsightsAutoFirstRunJob.MIN_TRACES)
                .mapToObj(__ -> podamFactory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .build())
                .toList();
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        autoFirstRunJob.runSweep(Instant.now(), 10).block();

        assertThat(TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList()).isEmpty();
    }

    @Test
    @DisplayName("A candidate unenrolled after the sweep selected it is not run")
    void autoFirstRun__enrolmentClearedAfterSelection__doesNotRun() {
        // The rollout is cancelled mid-sweep when the free budget runs out, so candidates already selected
        // must be re-checked at the stamp rather than run on a cancelled rollout.
        var projectId = createProject();
        jobsClient.enrolInAutoFirstRun(true, List.of(projectId)).close();
        jobsClient.enrolInAutoFirstRun(false, List.of(projectId)).close();

        jobService.autoFirstRun(WORKSPACE_ID, projectId, Instant.now().minus(7, ChronoUnit.DAYS), Instant.now());

        assertThat(TRIGGERS.stream().filter(t -> t.projectId().equals(projectId)).toList()).isEmpty();
        try (var job = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(job.readEntity(AgentInsightsJob.class).autoFirstRunAt()).isNull();
        }
    }

    @Test
    @DisplayName("Running out of credits switches the daily schedule off")
    void runFailure__outOfCredits__disablesSchedule() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();
        jobsClient.update(projectId, AgentInsightsJob.Status.ENABLED, API_KEY, WORKSPACE_NAME).close();

        reportFailuresClient.create(agentInsightsFailure(projectId, "out_of_credits", "402: insufficient credits"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);

        try (var response = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(response.readEntity(AgentInsightsJob.class).status())
                    .isEqualTo(AgentInsightsJob.Status.DISABLED);
        }
    }

    @Test
    @DisplayName("A failure for any other reason leaves the schedule alone")
    void runFailure__otherReason__keepsSchedule() {
        var projectId = createProject();
        jobsClient.create(projectId, API_KEY, WORKSPACE_NAME).close();
        jobsClient.update(projectId, AgentInsightsJob.Status.ENABLED, API_KEY, WORKSPACE_NAME).close();

        reportFailuresClient.create(agentInsightsFailure(projectId, "did_not_start", "trigger never landed"),
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);

        try (var response = jobsClient.get(projectId, API_KEY, WORKSPACE_NAME)) {
            assertThat(response.readEntity(AgentInsightsJob.class).status())
                    .isEqualTo(AgentInsightsJob.Status.ENABLED);
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
        // Jobs are created disabled, and the sweep only reads enabled ones, so opt in explicitly.
        jobsClient.update(projectId, AgentInsightsJob.Status.ENABLED, API_KEY, WORKSPACE_NAME).close();

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
