package com.comet.opik.domain.ailabels;

import com.comet.opik.api.BatchDeleteByProject;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ai-labels storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AiLabelsStorageTest {

    private static final String USER = "user-" + UUID.randomUUID();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reused: the job and opt-in reads span the whole table, so rows from other runs would leak into them.
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(redis, mysql, clickHouse, zookeeper).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                .build());
    }

    private AiLabelStatusDAO statusDAO;
    private AiLabelsDAO labelsDAO;
    private TransactionTemplate template;
    private TransactionTemplateAsync templateAsync;
    private TraceResourceClient traceClient;
    private ProjectResourceClient projectClient;

    @BeforeAll
    void beforeAll(ClientSupport client, @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO,
            TransactionTemplate template, TransactionTemplateAsync templateAsync) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;
        this.template = template;
        this.templateAsync = templateAsync;
        this.traceClient = new TraceResourceClient(client, baseURI);
        this.projectClient = new ProjectResourceClient(client, baseURI, PodamFactoryUtils.newPodamFactory());
    }

    @Nested
    @DisplayName("ai_label_status")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Status {

        @Test
        @DisplayName("pending, then done, then failed reads back as failed, before and after OPTIMIZE FINAL")
        void latestRowWins() {
            var workspaceId = UUID.randomUUID().toString();
            var pending = status(workspaceId, UUID.randomUUID(), ID_GENERATOR.generateId(), "summary",
                    AiLabelStatus.Status.PENDING, "job-" + UUID.randomUUID());
            var done = pending.toBuilder().status(AiLabelStatus.Status.DONE).build();
            var failed = pending.toBuilder()
                    .status(AiLabelStatus.Status.FAILED)
                    .retryable(true)
                    .attempt(2)
                    .errorCode("job_lost")
                    .build();

            insert(pending);
            insert(done);
            insert(failed);

            assertThat(findLatestStatus(workspaceId, pending.traceId())).containsExactly(failed);

            optimizeFinal(AiLabelStatusDAO.TABLE);

            assertThat(countRows(AiLabelStatusDAO.TABLE, workspaceId)).isOne();
            assertThat(findLatestStatus(workspaceId, pending.traceId())).containsExactly(failed);
        }

        @Test
        @DisplayName("pendingTraceTasks finds the pending rows of one job, among other jobs and workspaces")
        void pendingTraceTasksFindsOneJob() {
            var workspaceId = UUID.randomUUID().toString();
            var otherWorkspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var jobId = "job-" + UUID.randomUUID();
            var otherJobId = "job-" + UUID.randomUUID();
            var resubmittedJobId = "job-" + UUID.randomUUID();
            var pendingTraceId = ID_GENERATOR.generateId();
            var doneTraceId = ID_GENERATOR.generateId();
            var resubmittedTraceId = ID_GENERATOR.generateId();

            var pendingSummary = status(workspaceId, projectId, pendingTraceId, "summary",
                    AiLabelStatus.Status.PENDING, jobId);
            var pendingTopic = pendingSummary.toBuilder().task("topic").build();
            var done = status(workspaceId, projectId, doneTraceId, "summary", AiLabelStatus.Status.PENDING, jobId);
            var resubmitted = status(workspaceId, projectId, resubmittedTraceId, "summary",
                    AiLabelStatus.Status.PENDING, jobId);

            statusDAO.insert(List.of(pendingSummary, pendingTopic, done, resubmitted,
                    status(workspaceId, projectId, ID_GENERATOR.generateId(), "summary",
                            AiLabelStatus.Status.PENDING, otherJobId),
                    status(otherWorkspaceId, projectId, ID_GENERATOR.generateId(), "summary",
                            AiLabelStatus.Status.PENDING, jobId)))
                    .block();
            insert(done.toBuilder().status(AiLabelStatus.Status.DONE).build());
            insert(resubmitted.toBuilder().jobId(resubmittedJobId).attempt(2).build());

            var actual = statusDAO.pendingTraceTasks(workspaceId, jobId).collectList().block();

            assertThat(actual).containsExactlyInAnyOrder(pendingSummary, pendingTopic);
        }

        @Test
        @DisplayName("id_at and the partition equal traces_local_v2's for the same ids")
        void idAtMatchesTracesLocalV2() {
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var monday = Instant.parse("2025-03-03T00:00:00Z");
            var traceIds = List.of(
                    ID_GENERATOR.generateId(),
                    ID_GENERATOR.generateId(monday),
                    ID_GENERATOR.generateId(monday.minusMillis(1)),
                    // A far-future id, as a client with a broken clock mints: its own honest week, never a wrapped one.
                    ID_GENERATOR.generateId(Instant.parse("2201-06-01T00:00:00Z")),
                    // Not a UUIDv7: UUIDv7ToDateTime reads it as the epoch.
                    UUID.randomUUID());

            statusDAO.insert(traceIds.stream()
                    .map(traceId -> status(workspaceId, projectId, traceId, "summary",
                            AiLabelStatus.Status.DONE, "job"))
                    .toList())
                    .block();
            labelsDAO.insert(traceIds.stream()
                    .map(traceId -> outputLabel(workspaceId, projectId, traceId, "summary", 1, "{}"))
                    .toList())
                    .block();
            traceIds.forEach(traceId -> insertTracesLocalV2Row(workspaceId, projectId, traceId));

            var expected = idAtAndPartition("traces_local_v2", "id", workspaceId);

            assertThat(expected).hasSize(traceIds.size());
            assertThat(idAtAndPartition(AiLabelStatusDAO.TABLE, "trace_id", workspaceId)).isEqualTo(expected);
            assertThat(idAtAndPartition(AiLabelsDAO.TABLE, "trace_id", workspaceId)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("ai_labels")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Labels {

        @Test
        @DisplayName("every answer type reads back as written")
        void insertAndRead() {
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var traceId = ID_GENERATOR.generateId();
            var base = AiLabel.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .task("quality")
                    .traceId(traceId)
                    .key("base")
                    .taskVersion(3)
                    .answerType(AiLabel.AnswerType.NOUL)
                    .probabilities(Map.of())
                    .legend(Map.of())
                    .output("")
                    .build();
            var labels = List.of(
                    base.toBuilder().key("is_resolved").answerType(AiLabel.AnswerType.NOUL).noul(0.75f).confidence(0.5f)
                            .build(),
                    base.toBuilder().key("category").answerType(AiLabel.AnswerType.CHOICE).choice("billing")
                            .confidence(0.9f)
                            .probabilities(Map.of("billing", 0.9f, "other", 0.1f)).build(),
                    base.toBuilder().key("helpfulness").answerType(AiLabel.AnswerType.SCORE).score(3.5f)
                            .probabilities(Map.of("1", 0.25f, "5", 0.75f))
                            .legend(Map.of("1", "poor", "5", "great")).build(),
                    outputLabel(workspaceId, projectId, traceId, "summary", 1, "{\"summary\":\"a short summary\"}"));

            labelsDAO.insert(labels).block();

            assertThat(findLatestLabels(workspaceId, traceId)).containsExactlyInAnyOrderElementsOf(labels);
        }
    }

    @Nested
    @DisplayName("ai_label_jobs")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Jobs {

        @BeforeEach
        void clear() {
            template.inTransaction(WRITE, handle -> handle.execute("DELETE FROM ai_label_jobs"));
        }

        @Test
        @DisplayName("inFlightEngineCalls sums only submitted jobs")
        void inFlightEngineCallsSumsSubmittedJobs() {
            insertJob(job(AiLabelEndpoint.DECIDE, 10));
            insertJob(job(AiLabelEndpoint.GENERATE, 5));
            insertJob(job(AiLabelEndpoint.DECIDE, 100).toBuilder()
                    .status(AiLabelJob.Status.LOADED)
                    .terminalAt(now())
                    .build());

            assertThat(inFlightEngineCalls()).isEqualTo(15);
        }

        @Test
        @DisplayName("due returns submitted jobs not polled within the interval, least recently polled first")
        void due() {
            var polled = job(AiLabelEndpoint.DECIDE, 1);
            var neverPolled = job(AiLabelEndpoint.GENERATE, 1);
            var loaded = job(AiLabelEndpoint.DECIDE, 1);
            insertJob(polled);
            insertJob(neverPolled);
            insertJob(loaded);
            withJobs(dao -> dao.markPolled(polled.jobId()));
            withJobs(dao -> dao.markLoaded(loaded.jobId(), now()));

            List<AiLabelJob> dueWithinInterval = withJobs(dao -> dao.due(Duration.ofMinutes(5), 10));
            assertThat(dueWithinInterval).containsExactly(neverPolled);

            List<AiLabelJob> dueNow = withJobs(dao -> dao.due(Duration.ZERO, 10));
            assertThat(dueNow).extracting(AiLabelJob::jobId).containsExactly(neverPolled.jobId(), polled.jobId());
            assertThat(dueNow.getLast().lastPolledAt()).isNotNull();

            List<AiLabelJob> dueLimited = withJobs(dao -> dao.due(Duration.ZERO, 1));
            assertThat(dueLimited).containsExactly(neverPolled);
        }

        @Test
        @DisplayName("markLoaded frees the job's budget, and deleteLoadedBefore removes only loaded jobs past the cutoff")
        void markLoadedAndDeleteLoadedBefore() {
            var oldLoaded = job(AiLabelEndpoint.DECIDE, 3);
            var recentLoaded = job(AiLabelEndpoint.DECIDE, 4);
            var submitted = job(AiLabelEndpoint.GENERATE, 5);
            insertJob(oldLoaded);
            insertJob(recentLoaded);
            insertJob(submitted);
            var cutoff = now().minus(7, ChronoUnit.DAYS);

            withJobs(dao -> dao.markLoaded(oldLoaded.jobId(), cutoff.minus(1, ChronoUnit.HOURS)));
            withJobs(dao -> dao.markLoaded(recentLoaded.jobId(), now()));

            assertThat(inFlightEngineCalls()).isEqualTo(5);
            int deleted = withJobs(dao -> dao.deleteLoadedBefore(cutoff));
            assertThat(deleted).isOne();
            assertThat(jobIds()).containsExactlyInAnyOrder(recentLoaded.jobId(), submitted.jobId());
        }

        private AiLabelJob job(AiLabelEndpoint endpoint, int engineCalls) {
            return AiLabelJob.builder()
                    .jobId("job-" + UUID.randomUUID())
                    .workspaceId(UUID.randomUUID().toString())
                    .endpoint(endpoint)
                    .cache(endpoint == AiLabelEndpoint.GENERATE)
                    .itemCount(engineCalls)
                    .engineCalls(engineCalls)
                    .status(AiLabelJob.Status.SUBMITTED)
                    .submittedAt(now())
                    .build();
        }

        private void insertJob(AiLabelJob job) {
            template.inTransaction(WRITE, handle -> {
                handle.attach(AiLabelJobDAO.class).insert(job);
                return null;
            });
        }

        private long inFlightEngineCalls() {
            return withJobs(AiLabelJobDAO::inFlightEngineCalls);
        }

        private List<String> jobIds() {
            return template.inTransaction(READ_ONLY, handle -> handle
                    .createQuery("SELECT job_id FROM ai_label_jobs")
                    .mapTo(String.class)
                    .list());
        }

        private <T> T withJobs(java.util.function.Function<AiLabelJobDAO, T> action) {
            return template.inTransaction(WRITE, handle -> action.apply(handle.attach(AiLabelJobDAO.class)));
        }

        private Instant now() {
            return Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    @Nested
    @DisplayName("ai_label_workspaces")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Workspaces {

        @Test
        @DisplayName("upsert enables and disables a workspace, and enabledWorkspaceIds lists only enabled ones")
        void upsertAndEnabledWorkspaceIds() {
            var enabled = UUID.randomUUID().toString();
            var disabledLater = UUID.randomUUID().toString();
            var neverEnabled = UUID.randomUUID().toString();

            withWorkspaces(dao -> {
                dao.upsert(enabled, true, "operator");
                dao.upsert(disabledLater, true, "operator");
                dao.upsert(neverEnabled, false, "operator");
                dao.upsert(disabledLater, false, "other-operator");
                return null;
            });

            var actual = withWorkspaces(AiLabelWorkspaceDAO::enabledWorkspaceIds);
            assertThat(actual).contains(enabled).doesNotContain(disabledLater, neverEnabled);

            withWorkspaces(dao -> {
                dao.upsert(disabledLater, true, "operator");
                return null;
            });

            assertThat(withWorkspaces(AiLabelWorkspaceDAO::enabledWorkspaceIds))
                    .contains(enabled, disabledLater)
                    .doesNotContain(neverEnabled);
        }

        private <T> T withWorkspaces(java.util.function.Function<AiLabelWorkspaceDAO, T> action) {
            return template.inTransaction(WRITE, handle -> action.apply(handle.attach(AiLabelWorkspaceDAO.class)));
        }
    }

    @Nested
    @DisplayName("trace delete cascade")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Cascade {

        @Test
        @DisplayName("deleting traces deletes their labels and status rows, and leaves other traces' rows")
        void deletingTracesDeletesTheirLabels() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = "workspace-" + UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
            var projectName = "project-" + UUID.randomUUID();
            var projectId = projectClient.createProject(projectName, apiKey, workspaceName);
            var deletedTraceId = createTrace(projectName, apiKey, workspaceName);
            var keptTraceId = createTrace(projectName, apiKey, workspaceName);
            var otherWorkspaceId = UUID.randomUUID().toString();

            var traceIds = List.of(deletedTraceId, keptTraceId);
            statusDAO.insert(traceIds.stream()
                    .map(traceId -> status(workspaceId, projectId, traceId, "summary", AiLabelStatus.Status.DONE,
                            "job"))
                    .collect(Collectors.toList())).block();
            labelsDAO.insert(traceIds.stream()
                    .map(traceId -> outputLabel(workspaceId, projectId, traceId, "summary", 1, "{}"))
                    .toList()).block();
            // The same trace id under another workspace: the cascade is scoped to the deleting workspace.
            statusDAO.insert(List.of(status(otherWorkspaceId, projectId, deletedTraceId, "summary",
                    AiLabelStatus.Status.DONE, "job"))).block();
            labelsDAO.insert(List.of(outputLabel(otherWorkspaceId, projectId, deletedTraceId, "summary", 1, "{}")))
                    .block();

            traceClient.deleteTraces(BatchDeleteByProject.builder()
                    .ids(Set.of(deletedTraceId))
                    .projectId(projectId)
                    .build(), workspaceName, apiKey);

            Awaitility.await()
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> {
                        assertThat(statusDAO.findLatestByTraceIds(workspaceId, Set.copyOf(traceIds))
                                .map(AiLabelStatus::traceId).collectList().block())
                                .containsExactly(keptTraceId);
                        assertThat(labelsDAO.findLatestByTraceIds(workspaceId, Set.copyOf(traceIds))
                                .map(AiLabel::traceId).collectList().block())
                                .containsExactly(keptTraceId);
                    });
            assertThat(countRows(AiLabelStatusDAO.TABLE, otherWorkspaceId)).isOne();
            assertThat(countRows(AiLabelsDAO.TABLE, otherWorkspaceId)).isOne();
            // Scoped to the trace's partition, so the mutation does not visit every part of the table.
            assertThat(partitionScopedCascadeDeletes(workspaceId)).isEqualTo(2);
        }

        private long partitionScopedCascadeDeletes(String workspaceId) {
            return templateAsync.nonTransaction(connection -> Mono
                    .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.from(connection.createStatement("""
                            SELECT count() AS cnt FROM system.query_log
                            WHERE type = 'QueryFinish'
                            AND log_comment LIKE concat('ai-labels:delete_by_trace_ids_%:', :workspace_id, ':%')
                            AND query LIKE '%IN PARTITION%'
                            """)
                            .bind("workspace_id", workspaceId)
                            .execute()))
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
                    .block();
        }

        private UUID createTrace(String projectName, String apiKey, String workspaceName) {
            return traceClient.createTrace(Trace.builder()
                    .id(ID_GENERATOR.generateId())
                    .name("trace")
                    .projectName(projectName)
                    .startTime(Instant.now())
                    .build(), apiKey, workspaceName);
        }
    }

    private static AiLabelStatus status(String workspaceId, UUID projectId, UUID traceId, String task,
            AiLabelStatus.Status status, String jobId) {
        return AiLabelStatus.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(task)
                .traceId(traceId)
                .traceCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .taskVersion(1)
                .rendererVersion(1)
                .status(status)
                .retryable(false)
                .attempt(1)
                .errorCode("")
                .jobId(jobId)
                .compactionLevel("none")
                .build();
    }

    private static AiLabel outputLabel(String workspaceId, UUID projectId, UUID traceId, String task, int version,
            String output) {
        return AiLabel.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(task)
                .traceId(traceId)
                .key(AiLabel.OUTPUT_KEY)
                .taskVersion(version)
                .answerType(AiLabel.AnswerType.OUTPUT)
                .probabilities(Map.of())
                .legend(Map.of())
                .output(output)
                .build();
    }

    private void insert(AiLabelStatus row) {
        statusDAO.insert(List.of(row)).block();
    }

    private List<AiLabelStatus> findLatestStatus(String workspaceId, UUID traceId) {
        return statusDAO.findLatestByTraceIds(workspaceId, Set.of(traceId)).collectList().block();
    }

    private List<AiLabel> findLatestLabels(String workspaceId, UUID traceId) {
        return labelsDAO.findLatestByTraceIds(workspaceId, Set.of(traceId)).collectList().block();
    }

    private void optimizeFinal(String table) {
        templateAsync.nonTransaction(connection -> Mono
                .from(connection.createStatement("OPTIMIZE TABLE %s FINAL".formatted(table)).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    private long countRows(String table, String workspaceId) {
        return templateAsync.nonTransaction(connection -> Mono
                .from(connection.createStatement("SELECT count() AS cnt FROM %s WHERE workspace_id = :workspace_id"
                        .formatted(table))
                        .bind("workspace_id", workspaceId)
                        .execute())
                .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
                .block();
    }

    private void insertTracesLocalV2Row(String workspaceId, UUID projectId, UUID id) {
        templateAsync.nonTransaction(connection -> Mono
                .from(connection.createStatement("""
                        INSERT INTO traces_local_v2 (id, workspace_id, project_id)
                        VALUES (:id, :workspace_id, :project_id)
                        """)
                        .bind("id", id.toString())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    private Map<String, String> idAtAndPartition(String table, String idColumn, String workspaceId) {
        return templateAsync.nonTransaction(connection -> Flux
                .from(connection.createStatement("""
                        SELECT %s AS id, concat(toString(id_at), ' ', _partition_id) AS id_at_partition
                        FROM %s
                        WHERE workspace_id = :workspace_id
                        """.formatted(idColumn, table))
                        .bind("workspace_id", workspaceId)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(row.get("id", String.class),
                        row.get("id_at_partition", String.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                .block();
    }
}
