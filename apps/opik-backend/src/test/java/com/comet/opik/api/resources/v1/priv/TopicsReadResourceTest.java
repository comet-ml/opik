package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.topics.Topic;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicProjectState;
import com.comet.opik.domain.topics.TopicRun;
import com.comet.opik.domain.topics.TopicRunDAO;
import com.comet.opik.domain.topics.TopicVector;
import com.comet.opik.domain.topics.TopicVectorsDAO;
import com.comet.opik.domain.topics.TopicWorkspaceDAO;
import com.comet.opik.domain.topics.TraceTopic;
import com.comet.opik.domain.topics.TraceTopicsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code GET /v1/private/topics} against real MySQL, ClickHouse and Redis. Expected numbers come from the seed in Java,
 * by the response's {@code today}: a test that seeds on one UTC day and gets the response on the next is skipped.
 */
@DisplayName("topics read API")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsReadResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/topics";
    private static final String USER = "user-" + UUID.randomUUID();
    private static final String RATE_LIMIT_BUCKET = "getProjectTopics";
    private static final int RATE_LIMIT = 3;
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, clickHouse, mysql, zookeeper).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        // topics.enabled needs ai-labels, which reads only the cut-over traces table.
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .rateLimitEnabled(true)
                .limit(10_000L)
                .workspaceLimit(10_000L)
                .limitDurationInSeconds(60L)
                .customLimits(Map.of(RATE_LIMIT_BUCKET, new LimitConfig("Get-Project-Topics", "get_project_topics",
                        RATE_LIMIT, 60, "You have exceeded the rate limit for this operation.")))
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.server().baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.nightly.enabled", "false"),
                        new CustomConfig("topics.arrival.enabled", "false")))
                .build());
    }

    private String baseUrl;
    private ClientSupport client;
    private ProjectResourceClient projectClient;
    private TransactionTemplate template;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicVectorsDAO vectorsDAO;
    private OpikConfiguration opikConfig;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector, OpikConfiguration opikConfig) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.projectClient = new ProjectResourceClient(client, baseUrl, PodamFactoryUtils.newPodamFactory());
        this.template = injector.getInstance(TransactionTemplate.class);
        this.traceTopicsDAO = injector.getInstance(TraceTopicsDAO.class);
        this.vectorsDAO = injector.getInstance(TopicVectorsDAO.class);
        this.opikConfig = opikConfig;
    }

    @AfterEach
    void tearDown() {
        opikConfig.getTopics().setEnabled(true);
    }

    @Test
    @DisplayName("ready: per topic counts, shares, changes, ranks, series and New as seeded; a retired topic's rows "
            + "count as stale Other; rows of a failed run and rows outside the window do not count")
    void ready() {
        var caller = caller();
        var projectId = project(caller);
        optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var firstBuild = today.minusDays(20);
        var first = topic(caller.workspaceId(), projectId, firstBuild, null, null);
        var newborn = topic(caller.workspaceId(), projectId, today.minusDays(3), null, null);
        var inactive = topic(caller.workspaceId(), projectId, firstBuild, today.minusDays(2), null);
        var retired = topic(caller.workspaceId(), projectId, firstBuild, null, today.minusDays(1));
        var lastRun = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var saved = run(caller.workspaceId(), projectId, TopicRun.Status.SAVED);
        var failed = run(caller.workspaceId(), projectId, TopicRun.Status.FAILED);
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(first, newborn, inactive, retired));
            dao.insertState(TopicProjectState.builder()
                    .projectId(projectId)
                    .workspaceId(caller.workspaceId())
                    .rebuilds(1)
                    .firstBuiltAt(firstBuild.atTime(2, 0).toInstant(ZoneOffset.UTC))
                    .lastRunId(saved)
                    .lastSuccessAt(lastRun)
                    .windowUntil(today.atStartOfDay().toInstant(ZoneOffset.UTC))
                    .build());
            return null;
        });
        var rows = new ArrayList<TraceTopic>();
        rows.addAll(night(caller.workspaceId(), projectId, first.id(), today.minusDays(25), 1, saved));
        rows.addAll(night(caller.workspaceId(), projectId, first.id(), today.minusDays(1), 3, saved));
        var firstToday = night(caller.workspaceId(), projectId, first.id(), today, 2, saved);
        rows.addAll(firstToday);
        rows.addAll(night(caller.workspaceId(), projectId, newborn.id(), today, 4, saved));
        rows.add(arrival(caller.workspaceId(), projectId, newborn.id(), today));
        rows.addAll(night(caller.workspaceId(), projectId, inactive.id(), today.minusDays(1), 10, saved));
        rows.addAll(night(caller.workspaceId(), projectId, retired.id(), today.minusDays(1), 2, saved));
        rows.addAll(night(caller.workspaceId(), projectId, TraceTopic.OTHER, today, 1, saved));
        rows.addAll(night(caller.workspaceId(), projectId, first.id(), today.minusDays(30), 5, saved));
        traceTopicsDAO.insert(rows).block();
        // Written after the saved rows, so they are the latest: a failed run's relabel and a trace only it labelled.
        traceTopicsDAO.insert(List.of(
                firstToday.getFirst().toBuilder().topicId(newborn.id()).runId(failed.toString()).build(),
                night(caller.workspaceId(), projectId, newborn.id(), today, 1, failed).getFirst())).block();

        var body = get(caller, projectId);

        assumeTrue(today.equals(body.today()), "the UTC day changed between the seed and the request");
        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.windowFrom()).isEqualTo(today.minusDays(29));
        assertThat(body.windowUntil()).isEqualTo(today);
        assertThat(body.lastRunAt()).isEqualTo(lastRun);
        assertThat(body.totalTraces()).isEqualTo(24L);
        assertThat(body.labelledFrom()).isEqualTo(today.minusDays(25));
        assertThat(body.vectorsInWindow()).isNull();
        assertThat(body.topics()).extracting(TopicStats::id, TopicStats::rank, TopicStats::count,
                TopicStats::change, TopicStats::isNew).containsExactly(
                        tuple(first.id(), 1, 6L, -1L, false),
                        tuple(newborn.id(), 2, 5L, 5L, true),
                        tuple(inactive.id(), 3, 10L, -10L, false));
        var firstStats = body.topics().getFirst();
        assertThat(firstStats.name()).isEqualTo(first.name());
        assertThat(firstStats.share()).isCloseTo(6.0 / 24, within(1e-12));
        assertThat(firstStats.series()).hasSize(30)
                .contains(DayCount.builder().day(today.minusDays(25)).count(1).build(),
                        DayCount.builder().day(today.minusDays(1)).count(3).build(),
                        DayCount.builder().day(today).count(2).build());
        assertThat(body.topics().getLast().inactiveSince()).isEqualTo(today.minusDays(2));
        var other = body.other();
        assertThat(other.id()).isEqualTo(TraceTopic.OTHER);
        assertThat(other.count()).isEqualTo(3);
        assertThat(other.stale()).isEqualTo(2);
        assertThat(other.change()).isEqualTo(-1);
        assertThat(other.share()).isCloseTo(3.0 / 24, within(1e-12));
    }

    @Test
    @DisplayName("collecting: no live topic; the window's vectors and the first build's minimum, without the ready "
            + "fields")
    void collecting() {
        var caller = caller();
        var projectId = project(caller);
        optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        vectorsDAO.insert(List.of(
                vector(caller.workspaceId(), projectId, today),
                vector(caller.workspaceId(), projectId, today.minusDays(29)),
                vector(caller.workspaceId(), projectId, today.minusDays(30)))).block();

        try (var response = request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            var json = response.readEntity(JsonNode.class);
            assumeTrue(today.toString().equals(json.get("today").asText()),
                    "the UTC day changed between the seed and the request");
            assertThat(json.get("status").asText()).isEqualTo("collecting");
            assertThat(json.get("vectors_in_window").asLong()).isEqualTo(2);
            assertThat(json.get("min_vectors").asLong())
                    .isEqualTo(opikConfig.getTopics().getNightly().getMinVectors());
            assertThat(json.get("topics")).isEmpty();
            assertThat(json.has("total_traces")).isFalse();
            assertThat(json.has("other")).isFalse();
            assertThat(json.has("labelled_from")).isFalse();
            assertThat(json.has("last_run_at")).isFalse();
        }
    }

    @Test
    @DisplayName("disabled: a workspace not opted in, also with live topics")
    void disabled() {
        var caller = caller();
        var projectId = project(caller);
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(
                    topic(caller.workspaceId(), projectId, LocalDate.now(ZoneOffset.UTC), null, null)));
            handle.attach(AiLabelWorkspaceDAO.class).upsert(caller.workspaceId(), true, "test");
            return null;
        });

        var body = get(caller, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.DISABLED);
        assertThat(body.topics()).isEmpty();
        assertThat(body.other()).isNull();
        assertThat(body.vectorsInWindow()).isNull();
    }

    @Test
    @DisplayName("404 for a project of another workspace")
    void otherWorkspacesProject() {
        var caller = caller();
        var projectId = project(caller());

        try (var response = request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("400 without project_id")
    void noProjectId() {
        var caller = caller();

        try (var response = client.target(RESOURCE_PATH.formatted(baseUrl)).request()
                .header(HttpHeaders.AUTHORIZATION, caller.apiKey())
                .header(RequestContext.WORKSPACE_HEADER, caller.workspaceName())
                .get()) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("501 with no body when topics are off, before the project check")
    void topicsOff() {
        var caller = caller();
        opikConfig.getTopics().setEnabled(false);

        try (var response = request(caller, UUID.randomUUID()).get()) {
            assertThat(response.getStatus()).isEqualTo(501);
            assertThat(response.hasEntity()).isFalse();
        }
    }

    @Test
    @DisplayName("403 without the project data view permission")
    void forbidden() {
        var caller = new Caller(UUID.randomUUID().toString(), "ws-" + UUID.randomUUID(),
                UUID.randomUUID().toString());
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), caller.apiKey(), caller.workspaceName(),
                WorkspaceUserPermission.PROJECT_DATA_VIEW.getValue());

        try (var response = request(caller, UUID.randomUUID()).get()) {
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("429 after the workspace's limit of requests")
    void rateLimited() {
        var caller = caller();
        var projectId = project(caller);

        for (int i = 0; i < RATE_LIMIT; i++) {
            try (var response = request(caller, projectId).get()) {
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }
        try (var response = request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(429);
        }
    }

    private record Caller(String apiKey, String workspaceName, String workspaceId) {
    }

    private Caller caller() {
        var caller = new Caller(UUID.randomUUID().toString(), "ws-" + UUID.randomUUID(),
                UUID.randomUUID().toString());
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), caller.apiKey(), caller.workspaceName(),
                caller.workspaceId(), USER);
        return caller;
    }

    private UUID project(Caller caller) {
        return projectClient.createProject("topics-" + UUID.randomUUID(), caller.apiKey(), caller.workspaceName());
    }

    private void optIn(String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            handle.attach(TopicWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
    }

    private Invocation.Builder request(Caller caller, UUID projectId) {
        return client.target(RESOURCE_PATH.formatted(baseUrl))
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, caller.apiKey())
                .header(RequestContext.WORKSPACE_HEADER, caller.workspaceName());
    }

    private ProjectTopics get(Caller caller, UUID projectId) {
        try (var response = request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            return response.readEntity(ProjectTopics.class);
        }
    }

    private UUID run(String workspaceId, UUID projectId, TopicRun.Status status) {
        var run = TopicRun.builder()
                .runId(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .mode(TopicRun.Mode.NIGHTLY_UPDATE)
                .status(status)
                .startedAt(Instant.now())
                .build();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(run);
            return null;
        });
        return run.runId();
    }

    private static Topic topic(String workspaceId, UUID projectId, LocalDate born, LocalDate inactiveSince,
            LocalDate retiredAt) {
        var id = UUID.randomUUID();
        return Topic.builder()
                .id(id)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(born)
                .inactiveSince(inactiveSince)
                .retiredAt(retiredAt)
                .retiredReason(retiredAt == null ? null : "merged")
                .build();
    }

    /** {@code count} traces created at noon of {@code day}, labelled by night run {@code runId}. */
    private static List<TraceTopic> night(String workspaceId, UUID projectId, UUID topicId, LocalDate day, int count,
            UUID runId) {
        return Collections.nCopies(count, 0).stream()
                .map(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(ID_GENERATOR.generateId())
                        .traceCreatedAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC))
                        .topicId(topicId)
                        .source(TraceTopic.Source.NIGHT)
                        .runId(runId.toString())
                        .build())
                .toList();
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, UUID topicId, LocalDate day) {
        return TraceTopic.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC))
                .topicId(topicId)
                .source(TraceTopic.Source.ARRIVAL)
                .runId("")
                .build();
    }

    private static TopicVector vector(String workspaceId, UUID projectId, LocalDate day) {
        return TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC))
                .taskVersion(1)
                .embedModel("bge-small")
                .embedDimensions(3)
                .text("an intent")
                .vector(List.of(0.1f, 0.2f, 0.3f))
                .build();
    }
}
