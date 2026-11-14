package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.DayCount;
import com.comet.opik.api.Source;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
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
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.api.resources.utils.TopicsTraces.TraceRow;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.domain.topics.Topic;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicProjectState;
import com.comet.opik.domain.topics.TraceTopic;
import com.comet.opik.domain.topics.TraceTopicsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The traces filter by topic, in the trace list, count, stats and stream, the trace KPI cards and the trace metrics,
 * against real MySQL, ClickHouse and Redis.
 */
@DisplayName("traces filter by topic")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesFilterByTopicResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/topics";
    private static final String USER = "user-" + UUID.randomUUID();
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
                        new CustomConfig("topics.firstBuild.enabled", "false")))
                .build());
    }

    private String baseUrl;
    private ClientSupport client;
    private ProjectResourceClient projectClient;
    private TraceResourceClient traceClient;
    private ProjectMetricsResourceClient metricsClient;
    private PodamFactory podamFactory;
    private TransactionTemplate template;
    private TraceTopicsDAO traceTopicsDAO;
    private OpikConfiguration opikConfig;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector, OpikConfiguration opikConfig) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.podamFactory = PodamFactoryUtils.newPodamFactory();
        this.projectClient = new ProjectResourceClient(client, baseUrl, podamFactory);
        this.traceClient = new TraceResourceClient(client, baseUrl);
        this.metricsClient = new ProjectMetricsResourceClient(client, baseUrl);
        this.template = injector.getInstance(TransactionTemplate.class);
        this.traceTopicsDAO = injector.getInstance(TraceTopicsDAO.class);
        this.connectionFactory = injector.getInstance(ConnectionFactory.class);
        this.opikConfig = opikConfig;
    }

    @AfterEach
    void tearDown() {
        opikConfig.getTopics().setEnabled(true);
    }

    private static final String OTHER = TraceTopic.OTHER.toString();

    /** The project's traces by the case each one stands for, and the topics they are labelled with. */
    private record Seed(Caller caller, UUID projectId, Topic labelled, Topic arrived, UUID saved,
            Map<String, UUID> traces) {

        Set<UUID> traces(String... cases) {
            return Arrays.stream(cases).map(traces::get).collect(Collectors.toSet());
        }
    }

    @Test
    @DisplayName("= and != by the read API's rules, the same in the list, its count, stats and the stream: a "
            + "retired topic's trace is in Other, an arrival row wins over a run row, a later run's row wins over "
            + "an earlier one's, a trace with no row fails both")
    void operators() {
        var seed = seed();
        var labelled = seed.labelled().id().toString();
        var arrived = seed.arrived().id().toString();

        assertFilter(seed, Operator.EQUAL, labelled, seed.traces("labelled", "labelled again"));
        assertFilter(seed, Operator.EQUAL, arrived,
                seed.traces("arrival over a run", "relabelled by a later run"));
        assertFilter(seed, Operator.EQUAL, OTHER, seed.traces("retired topic", "other"));
        assertFilter(seed, Operator.NOT_EQUAL, labelled, seed.traces("arrival over a run",
                "relabelled by a later run", "retired topic", "other"));
    }

    @Test
    @DisplayName("a trace whose latest topic row is a tombstone passes no operator, != included, and "
            + "is not in the KPI or metrics count of a topic filter")
    void tombstoneInNoOperator() {
        var seed = seed();
        var deleted = ID_GENERATOR.generateId();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, List.of(TraceRow.shown(
                seed.caller().workspaceId(), seed.projectId(), deleted, now.minus(Duration.ofMinutes(5)))));
        traceTopicsDAO.insert(List.of(TraceTopic.builder()
                .workspaceId(seed.caller().workspaceId())
                .projectId(seed.projectId())
                .traceId(deleted)
                .traceCreatedAt(now.minus(Duration.ofMinutes(5)))
                .topicId(seed.labelled().id())
                .source(TraceTopic.Source.RUN)
                .runId(seed.saved().toString())
                .build())).block();
        TopicsTraces.tombstone(connectionFactory, seed.caller().workspaceId(), seed.projectId(), deleted,
                now.minus(Duration.ofMinutes(5)), seed.labelled().id());
        var labelled = seed.labelled().id().toString();
        var arrived = seed.arrived().id().toString();

        assertFilter(seed, Operator.EQUAL, labelled, seed.traces("labelled", "labelled again"));
        assertFilter(seed, Operator.EQUAL, OTHER, seed.traces("retired topic", "other"));
        assertFilter(seed, Operator.NOT_EQUAL, labelled, seed.traces("arrival over a run",
                "relabelled by a later run", "retired topic", "other"));
        var labelledFilter = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, labelled));
        var notArrived = List.of(filter(TraceField.TOPIC_ID, Operator.NOT_EQUAL, arrived));
        assertThat(kpiCount(seed, labelledFilter)).isEqualTo(2);
        assertThat(kpiCount(seed, notArrived)).isEqualTo(4);
        assertThat(metricCount(seed, labelledFilter)).isEqualTo(2);
        assertThat(metricCount(seed, notArrived)).isEqualTo(4);
    }

    @Test
    @DisplayName("as Logs lists a topic (the read API's window by created_at, source sdk, visibility default), "
            + "the list's count per topic and for Other is the API's count; experiment, playground, optimization, "
            + "evaluator and hidden traces have topic rows but are in neither")
    void reconcilesWithReadApi() {
        var seed = seed();
        var notInLogs = notInLogs(seed);
        var today = LocalDate.now(ZoneOffset.UTC);

        var body = get(seed.caller(), seed.projectId());

        assumeTrue(today.equals(body.today()), "the UTC day changed between the seed and the request");
        var window = List.of(
                filter(TraceField.CREATED_AT, Operator.GREATER_THAN_EQUAL,
                        body.windowFrom().atStartOfDay(ZoneOffset.UTC).toInstant().toString()),
                filter(TraceField.CREATED_AT, Operator.LESS_THAN,
                        body.today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString()));
        // What the frontend adds on the Logs page: generateLogsSourceFilter and generateVisibilityFilters.
        var logs = Stream.concat(window.stream(), Stream.of(
                filter(TraceField.SOURCE, Operator.EQUAL, Source.SDK.getValue()),
                filter(TraceField.VISIBILITY_MODE, Operator.EQUAL, VisibilityMode.DEFAULT.getValue()))).toList();
        var fromApi = new HashMap<UUID, Long>();
        body.topics().forEach(topic -> fromApi.put(topic.id(), topic.count()));
        fromApi.put(TraceTopic.OTHER, body.other().series().stream().mapToLong(DayCount::count).sum());
        assertThat(fromApi).containsExactlyInAnyOrderEntriesOf(Map.of(
                seed.labelled().id(), 2L, seed.arrived().id(), 2L, TraceTopic.OTHER, 2L));
        assertThat(body.totalTraces()).isEqualTo(6L);
        var fromLogs = new HashMap<UUID, Long>();
        var withoutLogsFilters = new HashMap<UUID, Long>();
        fromApi.keySet().forEach(topicId -> {
            var topic = filter(TraceField.TOPIC_ID, Operator.EQUAL, topicId.toString());
            fromLogs.put(topicId, list(seed.caller(), seed.projectId(),
                    Stream.concat(logs.stream(), Stream.of(topic)).toList()).total());
            withoutLogsFilters.put(topicId, list(seed.caller(), seed.projectId(),
                    Stream.concat(window.stream(), Stream.of(topic)).toList()).total());
        });
        assertThat(fromLogs).isEqualTo(fromApi);
        // The topic filter alone keeps them: the counts leave them out, not the topic rows.
        assertThat(withoutLogsFilters).containsExactlyInAnyOrderEntriesOf(Map.of(
                seed.labelled().id(), 2L + notInLogs.get(seed.labelled().id()), seed.arrived().id(), 2L,
                TraceTopic.OTHER, 2L + notInLogs.get(TraceTopic.OTHER)));
    }

    @Test
    @DisplayName("a workspace without Topics: = and != give no trace, with no error")
    void workspaceWithoutTopics() {
        var caller = caller();
        var projectId = project(caller);
        createTraces(caller, projectId, 2);
        var topicId = UUID.randomUUID().toString();

        for (var operator : List.of(Operator.EQUAL, Operator.NOT_EQUAL)) {
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

    @Test
    @DisplayName("trace KPI cards: a topic filter counts the traces the list keeps; with topics off, none")
    void kpiCards() {
        var seed = seed();
        var labelled = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, seed.labelled().id().toString()));
        var other = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, OTHER));
        var notArrived = List.of(filter(TraceField.TOPIC_ID, Operator.NOT_EQUAL,
                seed.arrived().id().toString()));

        assertThat(kpiCount(seed, labelled)).isEqualTo(seed.traces("labelled", "labelled again").size());
        assertThat(kpiCount(seed, other)).isEqualTo(seed.traces("retired topic", "other").size());
        assertThat(kpiCount(seed, notArrived)).isEqualTo(list(seed.caller(), seed.projectId(), notArrived).total());
        assertThat(kpiCount(seed, List.of())).isEqualTo(7);

        opikConfig.getTopics().setEnabled(false);

        assertThat(kpiCount(seed, labelled)).isZero();
    }

    @Test
    @DisplayName("project metrics: a topic filter counts the traces the list keeps; with topics off, none")
    void projectMetrics() {
        var seed = seed();
        var labelled = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, seed.labelled().id().toString()));
        var notArrived = List.of(filter(TraceField.TOPIC_ID, Operator.NOT_EQUAL,
                seed.arrived().id().toString()));

        assertThat(metricCount(seed, labelled)).isEqualTo(seed.traces("labelled", "labelled again").size());
        assertThat(metricCount(seed, notArrived))
                .isEqualTo(list(seed.caller(), seed.projectId(), notArrived).total());
        assertThat(metricCount(seed, List.of())).isEqualTo(7);

        opikConfig.getTopics().setEnabled(false);

        assertThat(metricCount(seed, labelled)).isZero();
    }

    @Test
    @DisplayName("a topic_id that is not an id matches no trace, with no error: the list, its count, the stats, the "
            + "stream, the trace KPI cards and the trace metrics")
    void notAnId() {
        var seed = seed();
        var notAnId = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, "not-an-id"));

        assertTraces(seed.caller(), seed.projectId(), notAnId, Set.of());
        assertThat(kpiCount(seed, notAnId)).isZero();
        assertThat(metricCount(seed, notAnId)).isZero();
    }

    @Test
    @DisplayName("guards: the trace list and its count trip the memory guard (max_memory_usage) only with a topic "
            + "filter, and answer 503 with Retry-After")
    void listGuardTrips() {
        assertGuardTrips((seed, filters) -> traces(seed, "", filters));
    }

    @Test
    @DisplayName("guards: the trace stats trip the memory guard only with a topic filter, and answer 503 with "
            + "Retry-After")
    void statsGuardTrips() {
        assertGuardTrips((seed, filters) -> traces(seed, "/stats", filters));
    }

    @Test
    @DisplayName("guards: the trace KPI cards trip the memory guard only with a topic filter, and answer 503 with "
            + "Retry-After")
    void kpiCardsGuardTrips() {
        assertGuardTrips((seed, filters) -> projectClient.getKpiCardsRaw(seed.projectId(),
                KpiCardRequest.builder()
                        .entityType(KpiCardRequest.EntityType.TRACES)
                        .filters(JsonUtils.writeValueAsString(filters))
                        .intervalStart(Instant.now().minus(Duration.ofDays(1)))
                        .build(),
                seed.caller().apiKey(), seed.caller().workspaceName()));
    }

    @Test
    @DisplayName("guards: the trace metrics trip the memory guard only with a topic filter, and answer 503 with "
            + "Retry-After")
    void projectMetricsGuardTrips() {
        assertGuardTrips((seed, filters) -> client
                .target("%s/v1/private/projects/%s/metrics".formatted(baseUrl, seed.projectId()))
                .request()
                .header(HttpHeaders.AUTHORIZATION, seed.caller().apiKey())
                .header(RequestContext.WORKSPACE_HEADER, seed.caller().workspaceName())
                .post(Entity.json(ProjectMetricRequest.builder()
                        .metricType(MetricType.TRACE_COUNT)
                        .interval(TimeInterval.DAILY)
                        .intervalStart(Instant.now().minus(Duration.ofDays(1)))
                        .traceFilters(filters)
                        .build())));
    }

    /**
     * A real ClickHouse trip: {@code topics.filter.maxMemoryUsage} of 1 byte stops the query with
     * {@code MEMORY_LIMIT_EXCEEDED}. Without a topic filter the query carries no guard, so the same setting
     * changes nothing; with the setting back, the topic filter answers again.
     */
    private void assertGuardTrips(BiFunction<Seed, List<TraceFilter>, Response> call) {
        var seed = seed();
        var topicFilter = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, seed.labelled().id().toString()));
        var guards = opikConfig.getTopics().getFilter();
        var maxMemoryUsage = guards.getMaxMemoryUsage();
        guards.setMaxMemoryUsage(1);
        try {
            try (var response = call.apply(seed, topicFilter)) {
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
                assertThat(response.readEntity(ErrorMessage.class).errors())
                        .containsExactly("Topics are busy; retry shortly");
            }
            try (var response = call.apply(seed, List.of())) {
                assertThat(response.getStatus()).isEqualTo(200);
            }
        } finally {
            guards.setMaxMemoryUsage(maxMemoryUsage);
        }
        try (var response = call.apply(seed, topicFilter)) {
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    private Response traces(Seed seed, String path, List<TraceFilter> filters) {
        var target = client.target("%s/v1/private/traces%s".formatted(baseUrl, path))
                .queryParam("project_id", seed.projectId());
        if (!filters.isEmpty()) {
            target = target.queryParam("filters", TestUtils.toURLEncodedQueryParam(filters));
        }
        return target.request()
                .header(HttpHeaders.AUTHORIZATION, seed.caller().apiKey())
                .header(RequestContext.WORKSPACE_HEADER, seed.caller().workspaceName())
                .get();
    }

    private long kpiCount(Seed seed, List<TraceFilter> filters) {
        var response = projectClient.getKpiCards(seed.projectId(), KpiCardRequest.builder()
                .entityType(KpiCardRequest.EntityType.TRACES)
                .filters(JsonUtils.writeValueAsString(filters))
                .intervalStart(Instant.now().minus(Duration.ofDays(1)))
                .build(), seed.caller().apiKey(), seed.caller().workspaceName());
        return response.stats().stream()
                .filter(stat -> stat.type() == KpiCardResponse.KpiMetricType.COUNT)
                .map(stat -> stat.currentValue().longValue())
                .findFirst()
                .orElseThrow();
    }

    private long metricCount(Seed seed, List<TraceFilter> filters) {
        var response = metricsClient.getProjectMetrics(seed.projectId(), ProjectMetricRequest.builder()
                .metricType(MetricType.TRACE_COUNT)
                .interval(TimeInterval.DAILY)
                .intervalStart(Instant.now().minus(Duration.ofDays(1)))
                .traceFilters(filters)
                .build(), Long.class, seed.caller().apiKey(), seed.caller().workspaceName());
        return response.results().stream()
                .flatMap(result -> result.data().stream())
                .map(DataPoint::value)
                .filter(Objects::nonNull)
                .mapToLong(Number::longValue)
                .sum();
    }

    /**
     * Seven traces, one per case. The first run's rows come first; the arrival row and the later run's row are
     * written after them, so they are the latest rows of their traces. Neither run has a topic_runs row: the runs'
     * retention deleted them, and their rows still count.
     */
    private Seed seed() {
        var caller = caller();
        var projectId = project(caller);
        optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var labelled = topic(caller.workspaceId(), projectId, today.minusDays(10), null);
        var arrived = topic(caller.workspaceId(), projectId, today.minusDays(10), null);
        var retired = topic(caller.workspaceId(), projectId, today.minusDays(10), today.minusDays(1));
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(labelled, arrived, retired));
            dao.insertState(savedState(caller.workspaceId(), projectId));
            return null;
        });
        var saved = ID_GENERATOR.generateId();
        var later = ID_GENERATOR.generateId();
        var cases = List.of("labelled", "labelled again", "arrival over a run", "relabelled by a later run",
                "retired topic", "other", "no row");
        var created = createTraces(caller, projectId, cases.size());
        var traces = new HashMap<String, Trace>();
        for (int i = 0; i < cases.size(); i++) {
            traces.put(cases.get(i), created.get(i));
        }
        traceTopicsDAO.insert(List.of(
                row(caller, projectId, traces.get("labelled"), labelled.id(), saved),
                row(caller, projectId, traces.get("labelled again"), labelled.id(), saved),
                row(caller, projectId, traces.get("arrival over a run"), labelled.id(), saved),
                row(caller, projectId, traces.get("relabelled by a later run"), labelled.id(), saved),
                row(caller, projectId, traces.get("retired topic"), retired.id(), saved),
                row(caller, projectId, traces.get("other"), TraceTopic.OTHER, saved))).block();
        traceTopicsDAO.insert(List.of(
                row(caller, projectId, traces.get("arrival over a run"), arrived.id(), null),
                row(caller, projectId, traces.get("relabelled by a later run"), arrived.id(), later)))
                .block();
        var ids = new HashMap<String, UUID>();
        traces.forEach((name, trace) -> ids.put(name, trace.id()));
        return new Seed(caller, projectId, labelled, arrived, saved, ids);
    }

    /**
     * Traces the Logs page does not list, each labelled by the saved run: experiment, playground and optimization
     * traces, an evaluator's and an experiment's hidden trace, and a hidden SDK trace. Written straight into the
     * table: the API never takes a visibility, and online scoring and experiments write their traces themselves.
     * Returns how many each topic got.
     */
    private Map<UUID, Long> notInLogs(Seed seed) {
        var createdAt = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);
        var kinds = List.of(
                Map.entry(Source.EXPERIMENT, VisibilityMode.DEFAULT),
                Map.entry(Source.PLAYGROUND, VisibilityMode.DEFAULT),
                Map.entry(Source.OPTIMIZATION, VisibilityMode.DEFAULT),
                Map.entry(Source.EVALUATOR, VisibilityMode.HIDDEN),
                Map.entry(Source.EXPERIMENT, VisibilityMode.HIDDEN),
                Map.entry(Source.SDK, VisibilityMode.HIDDEN));
        var traces = new ArrayList<TraceRow>();
        var rows = new ArrayList<TraceTopic>();
        for (int i = 0; i < kinds.size(); i++) {
            var id = ID_GENERATOR.generateId();
            traces.add(new TraceRow(seed.caller().workspaceId(), seed.projectId(), id, createdAt,
                    kinds.get(i).getKey().getValue(), kinds.get(i).getValue().getValue()));
            rows.add(TraceTopic.builder()
                    .workspaceId(seed.caller().workspaceId())
                    .projectId(seed.projectId())
                    .traceId(id)
                    .traceCreatedAt(createdAt)
                    .topicId(i % 2 == 0 ? seed.labelled().id() : TraceTopic.OTHER)
                    .source(TraceTopic.Source.RUN)
                    .runId(seed.saved().toString())
                    .build());
        }
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, traces);
        traceTopicsDAO.insert(rows).block();
        return rows.stream().collect(Collectors.groupingBy(TraceTopic::topicId, Collectors.counting()));
    }

    /** A run row of {@code runId}, or an arrival row when it is null, at the trace's {@code created_at}. */
    private static TraceTopic row(Caller caller, UUID projectId, Trace trace, UUID topicId, UUID runId) {
        return TraceTopic.builder()
                .workspaceId(caller.workspaceId())
                .projectId(projectId)
                .traceId(trace.id())
                .traceCreatedAt(trace.createdAt())
                .topicId(topicId)
                .source(runId == null ? TraceTopic.Source.ARRIVAL : TraceTopic.Source.RUN)
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
                        // As the SDK logs it, so the Logs filters keep it.
                        .source(Source.SDK)
                        .usage(null)
                        .feedbackScores(null)
                        .comments(null)
                        .build())
                .toList();
        traceClient.batchCreateTraces(traces, caller.apiKey(), caller.workspaceName());
        // After the cut-over the API writes through the Distributed traces table, which ships rows in the
        // background (prefer_localhost_replica = 0 in users.xml): wait until the list shows every trace.
        var byId = Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> list(caller, projectId, List.of()).content().stream()
                        .collect(Collectors.toMap(Trace::id, trace -> trace)),
                        listed -> listed.keySet().containsAll(traces.stream().map(Trace::id).toList()));
        return traces.stream().map(trace -> byId.get(trace.id())).toList();
    }

    private void assertFilter(Seed seed, Operator operator, String value, Set<UUID> expected) {
        assertFilter(seed.caller(), seed.projectId(), operator, value, expected);
    }

    private void assertFilter(Caller caller, UUID projectId, Operator operator, String value,
            Set<UUID> expected) {
        assertTraces(caller, projectId, List.of(filter(TraceField.TOPIC_ID, operator, value)), expected);
    }

    /** The list, its count, the stats' trace count and the stream give the same traces. */
    private void assertTraces(Caller caller, UUID projectId, List<TraceFilter> filters, Set<UUID> expected) {
        var page = list(caller, projectId, filters);
        var stats = traceClient.getTraceStats(null, projectId, caller.apiKey(), caller.workspaceName(), filters,
                Map.of());
        var streamed = traceClient.getStreamAndAssertContent(caller.apiKey(), caller.workspaceName(),
                TraceSearchStreamRequest.builder().projectId(projectId).filters(filters).build());

        var description = filters.stream()
                .map(filter -> "%s %s %s".formatted(filter.field().getQueryParamField(),
                        filter.operator().getQueryParamOperator(), filter.value()))
                .collect(Collectors.joining(", "));
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

    /** Enabled in ai_label_workspaces, and added to topics.enabledWorkspaces as a Helm change would. */
    private void optIn(String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
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

    /** The state of a project a run saved. */
    private static TopicProjectState savedState(String workspaceId, UUID projectId) {
        var today = LocalDate.now(ZoneOffset.UTC);
        return TopicProjectState.builder()
                .projectId(projectId)
                .workspaceId(workspaceId)
                .rebuilds(1)
                .firstBuiltAt(today.minusDays(20).atTime(2, 0).toInstant(ZoneOffset.UTC))
                .lastRunId(ID_GENERATOR.generateId())
                .lastSuccessAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .windowUntil(today.atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();
    }

    private static Topic topic(String workspaceId, UUID projectId, LocalDate born, LocalDate retiredAt) {
        var id = UUID.randomUUID();
        return Topic.builder()
                .id(id)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(born)
                .retiredAt(retiredAt)
                .retiredReason(retiredAt == null ? null : "merged")
                .build();
    }
}
