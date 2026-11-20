package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.api.resources.utils.TopicsReadTestUtils;
import com.comet.opik.api.resources.utils.TopicsReadTestUtils.Caller;
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.api.resources.utils.TopicsTraces.TraceRow;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicEmbedding;
import com.comet.opik.domain.topics.TopicProject;
import com.comet.opik.domain.topics.TopicReadService;
import com.comet.opik.domain.topics.TopicRunDAO;
import com.comet.opik.domain.topics.TopicVector;
import com.comet.opik.domain.topics.TopicVectorsDAO;
import com.comet.opik.domain.topics.TraceTopic;
import com.comet.opik.domain.topics.TraceTopicsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
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
import static com.comet.opik.api.resources.utils.TopicsReadTestUtils.saveRun;
import static com.comet.opik.api.resources.utils.TopicsReadTestUtils.topic;
import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static final String RATE_LIMIT_BUCKET = "getProjectTopics";
    private static final int RATE_LIMIT = 3;
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reusable, on its own network: the cut-over below changes the traces topology, and a reused container would
    // hand it to the next test class (inserts into a Distributed traces are asynchronous).
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, clickHouse, mysql, zookeeper).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        // topics.enabled needs ai-labels, which reads only the cut-over traces table.
        PostCutoverTraces.migrate(clickHouse);
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
                        new CustomConfig("inference.url", wireMock.server().baseUrl()),
                        new CustomConfig("inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.nightly.enabled", "false"),
                        new CustomConfig("topics.firstBuild.enabled", "false")))
                .build());
    }

    private String baseUrl;
    private ClientSupport client;
    private TopicsReadTestUtils topics;
    private TransactionTemplate template;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicVectorsDAO vectorsDAO;
    private OpikConfiguration opikConfig;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector, OpikConfiguration opikConfig) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = injector.getInstance(TransactionTemplate.class);
        this.traceTopicsDAO = injector.getInstance(TraceTopicsDAO.class);
        this.vectorsDAO = injector.getInstance(TopicVectorsDAO.class);
        this.connectionFactory = injector.getInstance(ConnectionFactory.class);
        this.opikConfig = opikConfig;
        this.topics = new TopicsReadTestUtils(wireMock.server(), client, baseUrl,
                new ProjectResourceClient(client, baseUrl, PodamFactoryUtils.newPodamFactory()), template, opikConfig);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouse.stop();
        zookeeper.stop();
        network.close();
    }

    @AfterEach
    void tearDown() {
        opikConfig.getTopics().setEnabled(true);
    }

    @Test
    @DisplayName("ready: per topic counts, shares, ranks, series and New as seeded; a retired topic's rows count as "
            + "Other; rows outside the window do not count; a run's rows count without its topic_runs row")
    void ready() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var firstBuild = today.minusDays(20);
        var first = topic(caller.workspaceId(), projectId, firstBuild);
        var newborn = topic(caller.workspaceId(), projectId, today.minusDays(3));
        var largest = topic(caller.workspaceId(), projectId, firstBuild);
        // Retired since: no topics row, its trace_topics rows remain.
        var retired = topic(caller.workspaceId(), projectId, firstBuild);
        var lastRun = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var saved = ID_GENERATOR.generateId();
        template.inTransaction(WRITE, handle -> {
            var firstRun = ID_GENERATOR.generateId();
            saveRun(handle, caller.workspaceId(), projectId, firstRun,
                    firstBuild.atTime(2, 0).toInstant(ZoneOffset.UTC));
            var dao = handle.attach(TopicDAO.class);
            dao.saveList(caller.workspaceId(), projectId, firstRun, saved, lastRun,
                    today.atStartOfDay().toInstant(ZoneOffset.UTC), null, TopicEmbedding.MODEL);
            dao.insertTopics(List.of(first, newborn, largest));
            return null;
        });
        var rows = new ArrayList<TraceTopic>();
        rows.addAll(run(caller.workspaceId(), projectId, first.id(), today.minusDays(25), 1, saved));
        rows.addAll(run(caller.workspaceId(), projectId, first.id(), today.minusDays(1), 3, saved));
        rows.addAll(run(caller.workspaceId(), projectId, first.id(), today, 2, saved));
        rows.addAll(run(caller.workspaceId(), projectId, newborn.id(), today, 4, saved));
        rows.add(arrival(caller.workspaceId(), projectId, newborn.id(), today));
        rows.addAll(run(caller.workspaceId(), projectId, largest.id(), today.minusDays(1), 10, saved));
        rows.addAll(run(caller.workspaceId(), projectId, retired.id(), today.minusDays(1), 2, saved));
        rows.addAll(run(caller.workspaceId(), projectId, TraceTopic.OTHER, today, 1, saved));
        rows.addAll(run(caller.workspaceId(), projectId, first.id(), today.minusDays(30), 5, saved));
        insertShown(rows);

        var body = topics.get(caller, projectId);

        assumeTrue(today.equals(body.today()), "the UTC day changed between the seed and the request");
        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.windowFrom()).isEqualTo(today.minusDays(29));
        assertThat(body.lastRunAt()).isEqualTo(lastRun);
        assertThat(body.totalTraces()).isEqualTo(24L);
        assertThat(body.labelledFrom()).isEqualTo(today.minusDays(25));
        assertThat(body.vectorsInWindow()).isNull();
        assertThat(body.topics()).extracting(TopicStats::id, TopicStats::rank, TopicStats::count,
                TopicStats::isNew).containsExactly(
                        tuple(largest.id(), 1, 10L, false),
                        tuple(first.id(), 2, 6L, false),
                        tuple(newborn.id(), 3, 5L, true));
        var firstStats = body.topics().get(1);
        assertThat(firstStats.name()).isEqualTo(first.name());
        assertThat(firstStats.share()).isCloseTo(6.0 / 24, within(1e-12));
        assertThat(firstStats.series()).hasSize(30)
                .contains(DayCount.builder().day(today.minusDays(25)).count(1).build(),
                        DayCount.builder().day(today.minusDays(1)).count(3).build(),
                        DayCount.builder().day(today).count(2).build());
        assertThat(body.other().series()).hasSize(30)
                .contains(DayCount.builder().day(today.minusDays(1)).count(2).build(),
                        DayCount.builder().day(today).count(1).build());
        assertThat(body.other().series().stream().mapToLong(DayCount::count).sum()).isEqualTo(3);
    }

    @Test
    @DisplayName("collecting, queued: no live topic and no run; the window's vectors and the first build's minimum, "
            + "without the ready fields, attempt or last_run_at")
    void collecting() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        vectorsDAO.insert(List.of(
                vector(caller.workspaceId(), projectId, today),
                vector(caller.workspaceId(), projectId, today.minusDays(29)),
                vector(caller.workspaceId(), projectId, today.minusDays(30)))).block();

        try (var response = topics.request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            var json = response.readEntity(JsonNode.class);
            assumeTrue(today.toString().equals(json.get("today").asText()),
                    "the UTC day changed between the seed and the request");
            assertThat(json.get("status").asText()).isEqualTo("collecting");
            assertThat(json.get("vectors_in_window").asLong()).isEqualTo(2);
            assertThat(json.get("min_vectors").asLong())
                    .isEqualTo(opikConfig.getTopics().getBuild().getMinVectors());
            assertThat(json.get("topics")).isEmpty();
            assertThat(json.has("total_traces")).isFalse();
            assertThat(json.has("other")).isFalse();
            assertThat(json.has("labelled_from")).isFalse();
            assertThat(json.has("last_run_at")).isFalse();
            assertThat(json.has("attempt")).isFalse();
        }
    }

    @Test
    @DisplayName("collecting, building: the project's attempt is running, so attempt is running; the failed attempt "
            + "before it does not count")
    void collectingBuilding() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        attempt(caller.workspaceId(), projectId, TopicProject.RunStatus.FAILED, startedAt.minus(Duration.ofDays(1)));
        attempt(caller.workspaceId(), projectId, TopicProject.RunStatus.RUNNING, startedAt);

        var json = json(caller, projectId);

        assertThat(json.get("status").asText()).isEqualTo("collecting");
        assertAttempt(json, "running");
        assertThat(json.has("last_run_at")).isFalse();
    }

    @Test
    @DisplayName("collecting, failed: the project's attempt failed, so attempt is failed")
    void collectingFailed() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        attempt(caller.workspaceId(), projectId, TopicProject.RunStatus.FAILED,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        var json = json(caller, projectId);

        assertThat(json.get("status").asText()).isEqualTo("collecting");
        assertAttempt(json, "failed");
        assertThat(json.has("last_run_at")).isFalse();
    }

    @Test
    @DisplayName("collecting, built with no topics: the project's attempt saved and left no live topic, so no attempt "
            + "and last_run_at set; the failed attempt before it does not count")
    void collectingBuiltWithNoTopics() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var savedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var saved = ID_GENERATOR.generateId();
        attempt(caller.workspaceId(), projectId, TopicProject.RunStatus.FAILED, savedAt.minus(Duration.ofDays(1)));
        savedRun(caller.workspaceId(), projectId, saved, savedAt);

        var json = json(caller, projectId);

        assertThat(json.get("status").asText()).isEqualTo("collecting");
        assertThat(json.has("attempt")).isFalse();
        assertThat(Instant.parse(json.get("last_run_at").asText())).isEqualTo(savedAt);
    }

    @Test
    @DisplayName("collecting, failed after saved: a run saved and left no live topic, then the project's attempt "
            + "failed, so attempt is failed and last_run_at is set")
    void collectingFailedAfterSaved() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var savedAt = startedAt.minus(Duration.ofDays(1));
        var saved = ID_GENERATOR.generateId();
        savedRun(caller.workspaceId(), projectId, saved, savedAt);
        attempt(caller.workspaceId(), projectId, TopicProject.RunStatus.FAILED, startedAt);

        var json = json(caller, projectId);

        assertThat(json.get("status").asText()).isEqualTo("collecting");
        assertAttempt(json, "failed");
        assertThat(Instant.parse(json.get("last_run_at").asText())).isEqualTo(savedAt);
    }

    @Test
    @DisplayName("disabled, also with live topics: no topics, other, vectors_in_window or attempt; ready has no "
            + "attempt either, also while the project's attempt is running")
    void noAttemptUnlessCollecting() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(
                    topic(caller.workspaceId(), projectId, LocalDate.now(ZoneOffset.UTC).minusDays(1))));
            saveRun(handle, caller.workspaceId(), projectId, ID_GENERATOR.generateId(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            // In ai-labels, not yet in topics.enabledWorkspaces: disabled.
            handle.attach(AiLabelWorkspaceDAO.class).upsert(caller.workspaceId(), true, "test");
            return null;
        });
        attempt(caller.workspaceId(), projectId, TopicProject.RunStatus.RUNNING,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        var disabled = json(caller, projectId);
        topics.optIn(caller.workspaceId());
        var ready = json(caller, projectId);

        assertThat(disabled.get("status").asText()).isEqualTo("disabled");
        assertThat(disabled.get("topics")).isEmpty();
        assertThat(disabled.has("other")).isFalse();
        assertThat(disabled.has("vectors_in_window")).isFalse();
        assertThat(disabled.has("attempt")).isFalse();
        assertThat(ready.get("status").asText()).isEqualTo("ready");
        assertThat(ready.has("attempt")).isFalse();
    }

    @Test
    @DisplayName("400 with the too-large message when the counts read hits its rows limit (max_rows_to_read): the real "
            + "driver's error, not a mock")
    void clickHouseRowsLimitIs400() {
        var caller = topics.caller();
        var projectId = readyProject(caller);
        var find = opikConfig.getTopics().getVectors().getFind();
        var maxRowsToRead = find.getMaxRowsToRead();
        find.setMaxRowsToRead(1);

        try (var response = topics.request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isNull();
            assertThat(response.readEntity(ErrorMessage.class).errors())
                    .containsExactly(TopicReadService.TOO_LARGE_MESSAGE);
            // The driver's own error is the v1 client's ClickHouseException, not the v2 client's ServerException.
            var today = LocalDate.now(ZoneOffset.UTC);
            var from = today.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC);
            var to = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            assertThatThrownBy(() -> traceTopicsDAO
                    .countByTopicAndDay(caller.workspaceId(), projectId, from, to, 10).collectList().block())
                    .satisfies(error -> assertThat(Throwables.getCausalChain(error))
                            .anyMatch(cause -> cause instanceof com.clickhouse.client.ClickHouseException)
                            .noneMatch(cause -> cause instanceof com.clickhouse.client.api.ServerException));
        } finally {
            find.setMaxRowsToRead(maxRowsToRead);
        }
    }

    @Test
    @DisplayName("503 with Retry-After when the counts read hits its memory limit (max_memory_usage): a retry can pass")
    void clickHouseMemoryLimitIs503() {
        var caller = topics.caller();
        var projectId = readyProject(caller);
        var find = opikConfig.getTopics().getVectors().getFind();
        var maxMemoryUsage = find.getMaxMemoryUsage();
        find.setMaxMemoryUsage(1);

        try (var response = topics.request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        } finally {
            find.setMaxMemoryUsage(maxMemoryUsage);
        }
    }

    @Test
    @DisplayName("404 for a project of another workspace")
    void otherWorkspacesProject() {
        var caller = topics.caller();
        var projectId = topics.project(topics.caller());

        try (var response = topics.request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("400 without project_id")
    void noProjectId() {
        var caller = topics.caller();

        try (var response = client.target(TopicsReadTestUtils.RESOURCE_PATH.formatted(baseUrl)).request()
                .header(HttpHeaders.AUTHORIZATION, caller.apiKey())
                .header(RequestContext.WORKSPACE_HEADER, caller.workspaceName())
                .get()) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("501 with no body when topics are off, before the project check")
    void topicsOff() {
        var caller = topics.caller();
        opikConfig.getTopics().setEnabled(false);

        try (var response = topics.request(caller, UUID.randomUUID()).get()) {
            assertThat(response.getStatus()).isEqualTo(501);
            assertThat(response.hasEntity()).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"true, true, true", "true, false, false", "false, true, false", "false, false, false"})
    @DisplayName("the toggles' topics_enabled is topics.enabled and the caller's workspace eligible; when false, the "
            + "API answers 501 or status disabled")
    void togglesTopicsEnabled(boolean enabled, boolean eligible, boolean expected) {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        if (eligible) {
            topics.optIn(caller.workspaceId());
        }
        opikConfig.getTopics().setEnabled(enabled);

        try (var response = client.target("%s/v1/private/toggles/".formatted(baseUrl))
                .request()
                .header(HttpHeaders.AUTHORIZATION, caller.apiKey())
                .header(RequestContext.WORKSPACE_HEADER, caller.workspaceName())
                .get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(JsonNode.class).get("topics_enabled").asBoolean()).isEqualTo(expected);
        }
        try (var response = topics.request(caller, projectId).get()) {
            if (!enabled) {
                assertThat(response.getStatus()).isEqualTo(501);
                return;
            }
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(ProjectTopics.class).status()).isEqualTo(expected
                    ? ProjectTopics.Status.COLLECTING
                    : ProjectTopics.Status.DISABLED);
        }
    }

    @Test
    @DisplayName("403 without the project data view permission")
    void forbidden() {
        var caller = new Caller(UUID.randomUUID().toString(), "ws-" + UUID.randomUUID(),
                UUID.randomUUID().toString());
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), caller.apiKey(), caller.workspaceName(),
                WorkspaceUserPermission.PROJECT_DATA_VIEW.getValue());

        try (var response = topics.request(caller, UUID.randomUUID()).get()) {
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("429 after the workspace's limit of requests")
    void rateLimited() {
        var caller = topics.caller();
        var projectId = topics.project(caller);

        for (int i = 0; i < RATE_LIMIT; i++) {
            try (var response = topics.request(caller, projectId).get()) {
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }
        try (var response = topics.request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(429);
        }
    }

    /** A ready project: one live topic with three traces in the window. */
    private UUID readyProject(Caller caller) {
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var live = topic(caller.workspaceId(), projectId, today.minusDays(5));
        var saved = ID_GENERATOR.generateId();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(live));
            saveRun(handle, caller.workspaceId(), projectId, saved, Instant.now().truncatedTo(ChronoUnit.MICROS));
            return null;
        });
        insertShown(run(caller.workspaceId(), projectId, live.id(), today, 3, saved));
        return projectId;
    }

    /** The rows, and a trace Logs shows for each of their traces: the API counts only those. */
    private void insertShown(List<TraceTopic> rows) {
        traceTopicsDAO.insert(rows).block();
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, rows.stream()
                .map(row -> TraceRow.shown(row.workspaceId(), row.projectId(), row.traceId(), row.traceCreatedAt()))
                .distinct()
                .toList());
    }

    /** The body as the client gets it. */
    private JsonNode json(Caller caller, UUID projectId) {
        try (var response = topics.request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            return response.readEntity(JsonNode.class);
        }
    }

    private static void assertAttempt(JsonNode json, String status) {
        var attempt = json.get("attempt");
        assertThat(attempt.get("status").asText()).isEqualTo(status);
        assertThat(attempt.size()).isEqualTo(1);
    }

    /** What run {@code runId} leaves in the project's row when it saves at {@code savedAt}. */
    private void savedRun(String workspaceId, UUID projectId, UUID runId, Instant savedAt) {
        template.inTransaction(WRITE, handle -> {
            saveRun(handle, workspaceId, projectId, runId, savedAt);
            return null;
        });
    }

    /** A new attempt of the project started at {@code startedAt}, left running or failed. */
    private void attempt(String workspaceId, UUID projectId, TopicProject.RunStatus status, Instant startedAt) {
        var runId = ID_GENERATOR.generateId();
        template.inTransaction(WRITE, handle -> {
            var runDAO = handle.attach(TopicRunDAO.class);
            runDAO.start(workspaceId, projectId, runId, TopicProject.RunMode.FIRST_BUILD, startedAt);
            if (status == TopicProject.RunStatus.FAILED) {
                runDAO.markFailed(workspaceId, projectId, runId, startedAt.plus(Duration.ofMinutes(5)),
                        TopicProject.RunError.ABANDONED);
            }
            return null;
        });
    }

    /** {@code count} traces created at noon of {@code day}, labelled by run {@code runId}. */
    private static List<TraceTopic> run(String workspaceId, UUID projectId, UUID topicId, LocalDate day, int count,
            UUID runId) {
        return Collections.nCopies(count, 0).stream()
                .map(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(ID_GENERATOR.generateId())
                        .traceCreatedAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC))
                        .topicId(topicId)
                        .source(TraceTopic.Source.RUN)
                        .runId(runId)
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
                .build();
    }

    private static TopicVector vector(String workspaceId, UUID projectId, LocalDate day) {
        return TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TopicEmbedding.SUMMARY_TASK)
                .traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC))
                .taskVersion(1)
                .embedModel("bge-small")
                .embedDimensions(3)
                .text("an intent")
                .vector(new float[]{0.1f, 0.2f, 0.3f})
                .build();
    }
}
