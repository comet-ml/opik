package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.ProjectTopics.OtherRunChange;
import com.comet.opik.api.ProjectTopics.RunChange;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.domain.topics.Topic;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicProjectState;
import com.comet.opik.domain.topics.TopicRanking;
import com.comet.opik.domain.topics.TopicRun;
import com.comet.opik.domain.topics.TopicRunCount;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    private static final Path RUNBOOK = Path.of("docs", "ai-labels-runbook.md");

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
    private TraceResourceClient traceClient;
    private PodamFactory podamFactory;
    private TransactionTemplate template;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicVectorsDAO vectorsDAO;
    private OpikConfiguration opikConfig;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector, OpikConfiguration opikConfig) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.podamFactory = PodamFactoryUtils.newPodamFactory();
        this.projectClient = new ProjectResourceClient(client, baseUrl, podamFactory);
        this.traceClient = new TraceResourceClient(client, baseUrl);
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
    @DisplayName("ready: per topic counts, shares, ranks, series and New as seeded; a retired topic's rows "
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
                TopicStats::isNew).containsExactly(
                        tuple(first.id(), 1, 6L, false),
                        tuple(newborn.id(), 2, 5L, true),
                        tuple(inactive.id(), 3, 10L, false));
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
        assertThat(other.share()).isCloseTo(3.0 / 24, within(1e-12));
    }

    @Test
    @DisplayName("run_change: the last two saved runs' counts, equal to the runbook's query; none for a topic new in "
            + "the last run; an older run is not read")
    void runChange() throws Exception {
        var caller = caller();
        var projectId = project(caller);
        optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var grew = topic(caller.workspaceId(), projectId, today.minusDays(20), null, null);
        var shrank = topic(caller.workspaceId(), projectId, today.minusDays(20), null, null);
        var newborn = topic(caller.workspaceId(), projectId, today.minusDays(1), null, null);
        var lastAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(grew, shrank, newborn));
            var runs = handle.attach(TopicRunDAO.class);
            runs.insertCounts(runCounts(caller.workspaceId(), projectId, lastAt.minus(Duration.ofDays(2)), 5,
                    grew.toBuilder().size(90).build(), shrank.toBuilder().size(5).build()));
            runs.insertCounts(runCounts(caller.workspaceId(), projectId, lastAt.minus(Duration.ofDays(1)), 40,
                    grew.toBuilder().size(20).build(), shrank.toBuilder().size(40).build()));
            runs.insertCounts(runCounts(caller.workspaceId(), projectId, lastAt, 10,
                    grew.toBuilder().size(60).build(), shrank.toBuilder().size(25).build(),
                    newborn.toBuilder().size(5).build()));
            return null;
        });

        var body = get(caller, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(runChange(body, grew)).usingRecursiveComparison().isEqualTo(RunChange.builder()
                .count(40).countRatio(2.0).share(60.0 / 100 - 20.0 / 100).rank(1).build());
        assertThat(runChange(body, shrank)).usingRecursiveComparison().isEqualTo(RunChange.builder()
                .count(-15).countRatio(-0.375).share(25.0 / 100 - 40.0 / 100).rank(-1).build());
        assertThat(runChange(body, newborn)).isNull();
        assertThat(body.other().runChange()).usingRecursiveComparison().isEqualTo(OtherRunChange.builder()
                .count(-30).countRatio(-0.75).share(10.0 / 100 - 40.0 / 100).build());

        // The runbook's query, run as written, gives the same changes: Other with no rank, no row for the newborn.
        record Change(long count, Double countRatio, double share, Integer rank) {
        }
        var fromApi = new HashMap<UUID, Change>();
        body.topics().stream().filter(topic -> topic.runChange() != null).forEach(topic -> fromApi.put(topic.id(),
                new Change(topic.runChange().count(), topic.runChange().countRatio(), topic.runChange().share(),
                        topic.runChange().rank())));
        var other = body.other().runChange();
        fromApi.put(TraceTopic.OTHER, new Change(other.count(), other.countRatio(), other.share(), null));
        var fromRunbook = new HashMap<UUID, Change>();
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword());
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(runbookQuery("### Changes since the last run",
                        caller.workspaceId(), projectId))) {
            while (resultSet.next()) {
                fromRunbook.put(UUID.fromString(resultSet.getString("topic_id")), new Change(
                        resultSet.getLong("count"),
                        resultSet.getObject("count_ratio") == null ? null : resultSet.getDouble("count_ratio"),
                        resultSet.getDouble("share"),
                        resultSet.getObject("rank") == null ? null : resultSet.getInt("rank")));
            }
        }
        assertThat(fromRunbook).usingRecursiveComparison()
                .withComparatorForType((a, b) -> Math.abs(a - b) < 1e-12 ? 0 : Double.compare(a, b), Double.class)
                .isEqualTo(fromApi);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("the toggles' topics_enabled is topics.enabled; false exactly when this API answers 501")
    void togglesTopicsEnabled(boolean enabled) {
        var caller = caller();
        opikConfig.getTopics().setEnabled(enabled);

        try (var response = client.target("%s/v1/private/toggles/".formatted(baseUrl))
                .request()
                .header(HttpHeaders.AUTHORIZATION, caller.apiKey())
                .header(RequestContext.WORKSPACE_HEADER, caller.workspaceName())
                .get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(JsonNode.class).get("topics_enabled").asBoolean()).isEqualTo(enabled);
        }
        try (var response = request(caller, project(caller)).get()) {
            assertThat(response.getStatus() == 501).isEqualTo(!enabled);
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

    @Nested
    @DisplayName("traces filter by topic")
    class TracesFilterByTopic {

        private static final String OTHER = TraceTopic.OTHER.toString();

        /** The project's traces by the case each one stands for, and the topics they are labelled with. */
        private record Seed(Caller caller, UUID projectId, Topic labelled, Topic arrived, Map<String, UUID> traces) {

            Set<UUID> traces(String... cases) {
                return Arrays.stream(cases).map(traces::get).collect(Collectors.toSet());
            }
        }

        @Test
        @DisplayName("=, !=, in and not_in by the read API's rules, the same in the list, its count, stats and the "
                + "stream: a retired topic's trace is in Other, an arrival row wins over a night row, a failed run's "
                + "rows do not count, a trace with no row fails every operator")
        void operators() {
            var seed = seed();
            var labelled = seed.labelled().id().toString();
            var arrived = seed.arrived().id().toString();

            assertFilter(seed, Operator.EQUAL, labelled,
                    seed.traces("labelled", "labelled again", "relabelled by a failed run"));
            assertFilter(seed, Operator.EQUAL, arrived, seed.traces("arrival over night"));
            assertFilter(seed, Operator.EQUAL, OTHER, seed.traces("retired topic", "other"));
            assertFilter(seed, Operator.NOT_EQUAL, labelled,
                    seed.traces("arrival over night", "retired topic", "other"));
            assertFilter(seed, Operator.IN, labelled + "," + OTHER,
                    seed.traces("labelled", "labelled again", "relabelled by a failed run", "retired topic", "other"));
            assertFilter(seed, Operator.NOT_IN, arrived + "," + OTHER,
                    seed.traces("labelled", "labelled again", "relabelled by a failed run"));
        }

        @Test
        @DisplayName("over the read API's window by created_at, the list's count per topic and for Other is the "
                + "API's count")
        void reconcilesWithReadApi() {
            var seed = seed();
            var today = LocalDate.now(ZoneOffset.UTC);

            var body = get(seed.caller(), seed.projectId());

            assumeTrue(today.equals(body.today()), "the UTC day changed between the seed and the request");
            var window = List.of(
                    filter(TraceField.CREATED_AT, Operator.GREATER_THAN_EQUAL,
                            body.windowFrom().atStartOfDay(ZoneOffset.UTC).toInstant().toString()),
                    filter(TraceField.CREATED_AT, Operator.LESS_THAN,
                            body.windowUntil().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString()));
            var fromApi = new HashMap<UUID, Long>();
            body.topics().forEach(topic -> fromApi.put(topic.id(), topic.count()));
            fromApi.put(TraceTopic.OTHER, body.other().count());
            assertThat(fromApi).containsExactlyInAnyOrderEntriesOf(Map.of(
                    seed.labelled().id(), 3L, seed.arrived().id(), 1L, TraceTopic.OTHER, 2L));
            var fromList = new HashMap<UUID, Long>();
            fromApi.keySet().forEach(topicId -> fromList.put(topicId, list(seed.caller(), seed.projectId(),
                    Stream.concat(window.stream(), Stream.of(
                            filter(TraceField.TOPIC_ID, Operator.EQUAL, topicId.toString()))).toList())
                    .total()));
            assertThat(fromList).isEqualTo(fromApi);
        }

        @Test
        @DisplayName("a workspace without Topics: every operator gives no trace, with no error")
        void workspaceWithoutTopics() {
            var caller = caller();
            var projectId = project(caller);
            createTraces(caller, projectId, 2);
            var topicId = UUID.randomUUID().toString();

            for (var operator : List.of(Operator.EQUAL, Operator.NOT_EQUAL, Operator.IN, Operator.NOT_IN)) {
                assertFilter(caller, projectId, operator, topicId, Set.of());
            }
        }

        @Test
        @DisplayName("topics off: a project with topics gives no trace, with no error")
        void topicsOffFilter() {
            var seed = seed();
            opikConfig.getTopics().setEnabled(false);

            assertFilter(seed, Operator.EQUAL, seed.labelled().id().toString(), Set.of());
        }

        /**
         * Eight traces, one per case. The night rows come first; the arrival row and the failed run's rows are
         * written after them, so they are the latest rows of their traces.
         */
        private Seed seed() {
            var caller = caller();
            var projectId = project(caller);
            optIn(caller.workspaceId());
            var today = LocalDate.now(ZoneOffset.UTC);
            var labelled = topic(caller.workspaceId(), projectId, today.minusDays(10), null, null);
            var arrived = topic(caller.workspaceId(), projectId, today.minusDays(10), null, null);
            var retired = topic(caller.workspaceId(), projectId, today.minusDays(10), null, today.minusDays(1));
            template.inTransaction(WRITE, handle -> {
                handle.attach(TopicDAO.class).insertTopics(List.of(labelled, arrived, retired));
                return null;
            });
            var saved = run(caller.workspaceId(), projectId, TopicRun.Status.SAVED);
            var failed = run(caller.workspaceId(), projectId, TopicRun.Status.FAILED);
            var cases = List.of("labelled", "labelled again", "arrival over night", "relabelled by a failed run",
                    "retired topic", "other", "no row", "only a failed run's row");
            var created = createTraces(caller, projectId, cases.size());
            var traces = new HashMap<String, Trace>();
            for (int i = 0; i < cases.size(); i++) {
                traces.put(cases.get(i), created.get(i));
            }
            traceTopicsDAO.insert(List.of(
                    row(caller, projectId, traces.get("labelled"), labelled.id(), saved),
                    row(caller, projectId, traces.get("labelled again"), labelled.id(), saved),
                    row(caller, projectId, traces.get("arrival over night"), labelled.id(), saved),
                    row(caller, projectId, traces.get("relabelled by a failed run"), labelled.id(), saved),
                    row(caller, projectId, traces.get("retired topic"), retired.id(), saved),
                    row(caller, projectId, traces.get("other"), TraceTopic.OTHER, saved))).block();
            traceTopicsDAO.insert(List.of(
                    row(caller, projectId, traces.get("arrival over night"), arrived.id(), null),
                    row(caller, projectId, traces.get("relabelled by a failed run"), arrived.id(), failed),
                    row(caller, projectId, traces.get("only a failed run's row"), arrived.id(), failed))).block();
            var ids = new HashMap<String, UUID>();
            traces.forEach((name, trace) -> ids.put(name, trace.id()));
            return new Seed(caller, projectId, labelled, arrived, ids);
        }

        /** A night row of {@code runId}, or an arrival row when it is null, at the trace's {@code created_at}. */
        private static TraceTopic row(Caller caller, UUID projectId, Trace trace, UUID topicId, UUID runId) {
            return TraceTopic.builder()
                    .workspaceId(caller.workspaceId())
                    .projectId(projectId)
                    .traceId(trace.id())
                    .traceCreatedAt(trace.createdAt())
                    .topicId(topicId)
                    .source(runId == null ? TraceTopic.Source.ARRIVAL : TraceTopic.Source.NIGHT)
                    .runId(runId == null ? "" : runId.toString())
                    .build();
        }

        /** {@code count} traces in the project, read back for their {@code created_at}, in creation order. */
        private List<Trace> createTraces(Caller caller, UUID projectId, int count) {
            var projectName = projectClient.getProject(projectId, caller.apiKey(), caller.workspaceName()).name();
            var traces = IntStream.range(0, count)
                    .mapToObj(i -> podamFactory.manufacturePojo(Trace.class).toBuilder()
                            .id(ID_GENERATOR.generateId())
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .comments(null)
                            .build())
                    .toList();
            traceClient.batchCreateTraces(traces, caller.apiKey(), caller.workspaceName());
            var byId = list(caller, projectId, List.of()).content().stream()
                    .collect(Collectors.toMap(Trace::id, trace -> trace));
            return traces.stream().map(trace -> byId.get(trace.id())).toList();
        }

        private void assertFilter(Seed seed, Operator operator, String value, Set<UUID> expected) {
            assertFilter(seed.caller(), seed.projectId(), operator, value, expected);
        }

        /** The list, its count, the stats' trace count and the stream give the same traces. */
        private void assertFilter(Caller caller, UUID projectId, Operator operator, String value,
                Set<UUID> expected) {
            var filters = List.of(filter(TraceField.TOPIC_ID, operator, value));

            var page = list(caller, projectId, filters);
            var stats = traceClient.getTraceStats(null, projectId, caller.apiKey(), caller.workspaceName(), filters,
                    Map.of());
            var streamed = traceClient.getStreamAndAssertContent(caller.apiKey(), caller.workspaceName(),
                    TraceSearchStreamRequest.builder().projectId(projectId).filters(filters).build());

            var description = "%s %s".formatted(operator.getQueryParamOperator(), value);
            assertThat(page.content()).as(description).extracting(Trace::id)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(page.total()).as(description).isEqualTo(expected.size());
            assertThat(stats.stats().stream()
                    .filter(stat -> StatsMapper.TRACE_COUNT.equals(stat.getName()))
                    .map(stat -> ((Number) stat.getValue()).longValue())
                    .findFirst()
                    .orElse(0L)).as(description).isEqualTo(expected.size());
            assertThat(streamed).as(description).extracting(Trace::id).containsExactlyInAnyOrderElementsOf(expected);
        }

        private Trace.TracePage list(Caller caller, UUID projectId, List<TraceFilter> filters) {
            return traceClient.getTraces(null, projectId, caller.apiKey(), caller.workspaceName(), filters,
                    List.of(), 100, Map.of());
        }

        private static TraceFilter filter(TraceField field, Operator operator, String value) {
            return TraceFilter.builder().field(field).operator(operator).value(value).build();
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

    /** A saved run's counts: the topics ranked by size as a night ranks them, then Other. */
    private static List<TopicRunCount> runCounts(String workspaceId, UUID projectId, Instant savedAt, long other,
            Topic... sized) {
        return TopicRanking.runCounts(ID_GENERATOR.generateId(), workspaceId, projectId, List.of(sized), other,
                savedAt);
    }

    private static RunChange runChange(ProjectTopics body, Topic topic) {
        return body.topics().stream().filter(stats -> stats.id().equals(topic.id())).findFirst().orElseThrow()
                .runChange();
    }

    /** The single SQL block of a runbook section, with the workspace and the project filled in. */
    private static String runbookQuery(String heading, String workspaceId, UUID projectId) throws IOException {
        var runbook = Files.readString(RUNBOOK);
        int start = runbook.indexOf(heading);
        assertThat(start).isNotNegative();
        var section = runbook.substring(start, runbook.indexOf("\n### ", start + heading.length()));
        var blocks = Pattern.compile("```sql\n(.*?)```", Pattern.DOTALL).matcher(section).results()
                .map(match -> match.group(1))
                .toList();
        assertThat(blocks).hasSize(1);
        return blocks.getFirst().strip().replaceAll(";$", "")
                .replace("<workspace_id>", workspaceId)
                .replace("<project_id>", projectId.toString());
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
