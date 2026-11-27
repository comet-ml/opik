package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
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
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.PostCutoverTraces.TraceRow;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.TopicsReadTestUtils;
import com.comet.opik.api.resources.utils.TopicsReadTestUtils.Caller;
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.domain.topics.Topic;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TraceTopic;
import com.comet.opik.domain.topics.TraceTopicsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsStage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import jakarta.ws.rs.client.Entity;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

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
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TopicsReadTestUtils.saveRun;
import static com.comet.opik.api.resources.utils.TopicsReadTestUtils.topic;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The traces filter by topic, in the trace list, count, stats and stream, the trace KPI cards and the trace metrics,
 * against real MySQL, ClickHouse and Redis.
 */
@DisplayName("traces filter by topic")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesFilterByTopicResourceTest {

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
                        new CustomConfig("topics.discovery.enabled", "false")))
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
    private TopicsReadTestUtils topics;

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
        this.topics = new TopicsReadTestUtils(wireMock.server(), client, baseUrl, projectClient, template, opikConfig);
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

    private static final String OTHER = TraceTopic.OTHER.toString();

    /** The project's traces by the case each one stands for, and the topics they are labelled with. */
    private record Seed(Caller caller, UUID projectId, Topic labelled, Topic arrived, UUID saved,
            Map<String, Trace> traces) {

        List<Trace> traces(String... cases) {
            return Arrays.stream(cases).map(traces::get).toList();
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
        var deleted = topics.createTraces(seed.caller(), seed.projectId(), 1).getFirst();
        traceTopicsDAO.insert(List.of(row(seed.caller(), seed.projectId(), deleted, seed.labelled().id(),
                seed.saved()))).block();
        TopicsTraces.tombstone(connectionFactory, seed.caller().workspaceId(), seed.projectId(), deleted.id(),
                deleted.createdAt(), seed.labelled().id());
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

        var body = topics.get(seed.caller(), seed.projectId());

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

    @ParameterizedTest(name = "no live topic, workspace opted in {0}: = and != give no trace, with no error")
    @ValueSource(booleans = {false, true})
    void noLiveTopic(boolean optedIn) {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        if (optedIn) {
            topics.optIn(caller.workspaceId());
        }
        topics.createTraces(caller, projectId, 2);

        for (var value : List.of(ID_GENERATOR.generateId().toString(), OTHER)) {
            for (var operator : List.of(Operator.EQUAL, Operator.NOT_EQUAL)) {
                assertFilter(caller, projectId, operator, value, List.of());
            }
        }
    }

    @ParameterizedTest(name = "{0}: a project with topics gives no trace, with no error")
    @ValueSource(strings = {"topics off", "workspace listed at vectors"})
    void topicsOffFilter(String reason) {
        var seed = seed();
        if (reason.equals("topics off")) {
            opikConfig.getTopics().setEnabled(false);
        } else {
            topics.optIn(seed.caller().workspaceId(), TopicsStage.VECTORS);
        }

        assertFilter(seed, Operator.EQUAL, seed.labelled().id().toString(), List.of());
    }

    Stream<Arguments> counters() {
        return Stream.of(
                arguments("trace KPI cards", (BiFunction<Seed, List<TraceFilter>, Long>) this::kpiCount),
                arguments("project metrics", (BiFunction<Seed, List<TraceFilter>, Long>) this::metricCount));
    }

    @ParameterizedTest(name = "{0}: a topic filter counts the traces the list keeps; with topics off, none")
    @MethodSource("counters")
    void counts(String endpoint, BiFunction<Seed, List<TraceFilter>, Long> count) {
        var seed = seed();
        var labelled = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, seed.labelled().id().toString()));
        var other = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, OTHER));
        var notArrived = List.of(filter(TraceField.TOPIC_ID, Operator.NOT_EQUAL,
                seed.arrived().id().toString()));

        assertThat(count.apply(seed, labelled)).isEqualTo(seed.traces("labelled", "labelled again").size());
        assertThat(count.apply(seed, other)).isEqualTo(seed.traces("retired topic", "other").size());
        assertThat(count.apply(seed, notArrived))
                .isEqualTo(list(seed.caller(), seed.projectId(), notArrived).total());
        assertThat(count.apply(seed, List.of())).isEqualTo(7);

        opikConfig.getTopics().setEnabled(false);

        assertThat(count.apply(seed, labelled)).isZero();
    }

    @Test
    @DisplayName("a topic_id that is not an id matches no trace, with no error: the list, its count, the stats, the "
            + "stream, the trace KPI cards and the trace metrics")
    void notAnId() {
        var seed = seed();
        var notAnId = List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, "not-an-id"));

        assertTraces(seed.caller(), seed.projectId(), notAnId, List.of());
        assertThat(kpiCount(seed, notAnId)).isZero();
        assertThat(metricCount(seed, notAnId)).isZero();
    }

    Stream<Arguments> guardedEndpoints() {
        return Stream.of(
                arguments("the trace list and its count", (BiFunction<Seed, List<TraceFilter>, Response>) (
                        seed, filters) -> traces(seed, "", filters)),
                arguments("the trace stats", (BiFunction<Seed, List<TraceFilter>, Response>) (
                        seed, filters) -> traces(seed, "/stats", filters)),
                arguments("the trace KPI cards", (BiFunction<Seed, List<TraceFilter>, Response>) (
                        seed, filters) -> projectClient.getKpiCardsRaw(seed.projectId(),
                                KpiCardRequest.builder()
                                        .entityType(KpiCardRequest.EntityType.TRACES)
                                        .filters(JsonUtils.writeValueAsString(filters))
                                        .intervalStart(Instant.now().minus(Duration.ofDays(1)))
                                        .build(),
                                seed.caller().apiKey(), seed.caller().workspaceName())),
                arguments("the trace metrics", (BiFunction<Seed, List<TraceFilter>, Response>) (
                        seed, filters) -> client
                                .target("%s/v1/private/projects/%s/metrics".formatted(baseUrl, seed.projectId()))
                                .request()
                                .header(HttpHeaders.AUTHORIZATION, seed.caller().apiKey())
                                .header(RequestContext.WORKSPACE_HEADER, seed.caller().workspaceName())
                                .post(Entity.json(ProjectMetricRequest.builder()
                                        .metricType(MetricType.TRACE_COUNT)
                                        .interval(TimeInterval.DAILY)
                                        .intervalStart(Instant.now().minus(Duration.ofDays(1)))
                                        .traceFilters(filters)
                                        .build()))));
    }

    /**
     * A real ClickHouse trip on each guarded endpoint: {@code topics.filter.maxMemoryUsage} of 1 byte stops the query
     * with {@code MEMORY_LIMIT_EXCEEDED}. Without a topic filter the query carries no guard, so the same setting
     * changes nothing; with the setting back, the topic filter answers again.
     */
    @ParameterizedTest(name = "guards: {0} trip the memory guard (max_memory_usage) only with a topic filter, and "
            + "answer 503 with Retry-After")
    @MethodSource("guardedEndpoints")
    void guardTrips(String endpoint, BiFunction<Seed, List<TraceFilter>, Response> call) {
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
     * written after them, so they are the latest rows of their traces. The project's row names only the first run;
     * the later run's rows still count.
     */
    @Test
    @DisplayName("the list filtered by topic, read in spans newest first, gives the page and count the whole range "
            + "gives: every page of sizes 1, 4 and 10, for =, != and Other, with and without from_time and a second "
            + "filter, on traces spread from minutes to weeks back")
    void spansGiveTheWholeRangesPages() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var first = topic(caller.workspaceId(), projectId, today.minusDays(10));
        var second = topic(caller.workspaceId(), projectId, today.minusDays(10));
        var saved = ID_GENERATOR.generateId();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(first, second));
            saveRun(handle, caller.workspaceId(), projectId, saved, Instant.now().truncatedTo(ChronoUnit.MICROS));
            return null;
        });
        // 2 minutes to about 39 days back, denser towards now: pages fill in spans of every length.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var traces = new ArrayList<TraceRow>();
        var rows = new ArrayList<TraceTopic>();
        for (int i = 0; i < 120; i++) {
            var createdAt = now.minusSeconds((long) (120 * Math.pow(1.09, i)));
            var trace = i % 6 == 5
                    ? new TraceRow(caller.workspaceId(), projectId, ID_GENERATOR.generateId(createdAt), createdAt,
                            Source.EXPERIMENT.getValue(), VisibilityMode.DEFAULT.getValue())
                    : TraceRow.shown(caller.workspaceId(), projectId, ID_GENERATOR.generateId(createdAt), createdAt);
            traces.add(trace);
            var topicId = switch (i % 5) {
                case 0, 1 -> first.id();
                case 2 -> second.id();
                case 3 -> TraceTopic.OTHER;
                default -> null;
            };
            if (topicId != null) {
                rows.add(row(caller, projectId, trace.id(), createdAt, topicId, saved));
            }
            if (i % 7 == 0) {
                rows.add(row(caller, projectId, trace.id(), createdAt, second.id(), null));
            }
        }
        PostCutoverTraces.insertTraces(connectionFactory, traces);
        traceTopicsDAO.insert(rows).block();

        var fromTime = Map.of("from_time", now.minus(Duration.ofDays(10)).toString());
        var sdk = filter(TraceField.SOURCE, Operator.EQUAL, Source.SDK.getValue());
        var cases = new ArrayList<Map.Entry<List<TraceFilter>, Map<String, String>>>();
        for (var topicFilter : List.of(filter(TraceField.TOPIC_ID, Operator.EQUAL, first.id().toString()),
                filter(TraceField.TOPIC_ID, Operator.EQUAL, second.id().toString()),
                filter(TraceField.TOPIC_ID, Operator.EQUAL, OTHER),
                filter(TraceField.TOPIC_ID, Operator.NOT_EQUAL, first.id().toString()))) {
            cases.add(Map.entry(List.of(topicFilter), Map.of()));
            cases.add(Map.entry(List.of(topicFilter), fromTime));
            cases.add(Map.entry(List.of(topicFilter, sdk), Map.of()));
        }
        var spans = opikConfig.getTopics().getFilter().getFirstPageWindow();
        for (var listCase : cases) {
            for (int size : List.of(1, 4, 10)) {
                // Size 1 up to page 12 only: every later page is a whole-range read, as size 4's tail already is.
                for (int page = 1; page <= (size == 1 ? 12 : 120 / size + 1); page++) {
                    var params = new HashMap<>(listCase.getValue());
                    params.put("page", String.valueOf(page));
                    var inSpans = traceClient.getTraces(null, projectId, caller.apiKey(), caller.workspaceName(),
                            listCase.getKey(), List.of(), size, params);
                    opikConfig.getTopics().getFilter().setFirstPageWindow(io.dropwizard.util.Duration.seconds(0));
                    try {
                        var whole = traceClient.getTraces(null, projectId, caller.apiKey(), caller.workspaceName(),
                                listCase.getKey(), List.of(), size, params);
                        assertThat(inSpans).as("%s, size %d, page %d", listCase, size, page).isEqualTo(whole);
                        if (whole.content().isEmpty()) {
                            break;
                        }
                    } finally {
                        opikConfig.getTopics().getFilter().setFirstPageWindow(spans);
                    }
                }
            }
            if (listCase == cases.getFirst()) {
                // The spans were read: with no from_time, only a span read has a lower bound in its log comment.
                assertThat(spanReads(projectId)).isPositive();
            }
        }
    }

    private long spanReads(UUID projectId) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> Flux.from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                        .flatMap(Result::getRowsUpdated)
                        .thenMany(Flux.from(connection.createStatement("""
                                SELECT count() AS reads FROM system.query_log
                                WHERE type = 'QueryFinish' AND is_initial_query
                                AND log_comment LIKE '%find_traces_by_project_id%'
                                AND position(log_comment, :project_id) > 0
                                AND log_comment NOT LIKE '%uuidFromTime=null%'
                                """).bind("project_id", projectId.toString()).execute()))
                        .flatMap(result -> result.map((row, metadata) -> row.get("reads", Long.class))))
                .blockLast();
    }

    private static TraceTopic row(Caller caller, UUID projectId, UUID traceId, Instant createdAt, UUID topicId,
            UUID runId) {
        return TraceTopic.builder()
                .workspaceId(caller.workspaceId())
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(createdAt)
                .topicId(topicId)
                .source(runId == null ? TraceTopic.Source.ARRIVAL : TraceTopic.Source.RUN)
                .runId(runId)
                .build();
    }

    private Seed seed() {
        var caller = topics.caller();
        var projectId = topics.project(caller);
        topics.optIn(caller.workspaceId());
        var today = LocalDate.now(ZoneOffset.UTC);
        var labelled = topic(caller.workspaceId(), projectId, today.minusDays(10));
        var arrived = topic(caller.workspaceId(), projectId, today.minusDays(10));
        // Retired since: no topics row, its trace_topics rows remain.
        var retired = topic(caller.workspaceId(), projectId, today.minusDays(10));
        var saved = ID_GENERATOR.generateId();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(labelled, arrived));
            saveRun(handle, caller.workspaceId(), projectId, saved, Instant.now().truncatedTo(ChronoUnit.MICROS));
            return null;
        });
        var later = ID_GENERATOR.generateId();
        var cases = List.of("labelled", "labelled again", "arrival over a run", "relabelled by a later run",
                "retired topic", "other", "no row");
        var created = topics.createTraces(caller, projectId, cases.size());
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
        return new Seed(caller, projectId, labelled, arrived, saved, traces);
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
                    .runId(seed.saved())
                    .build());
        }
        PostCutoverTraces.insertTraces(connectionFactory, traces);
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
                .runId(runId)
                .build();
    }

    private void assertFilter(Seed seed, Operator operator, String value, List<Trace> expected) {
        assertFilter(seed.caller(), seed.projectId(), operator, value, expected);
    }

    private void assertFilter(Caller caller, UUID projectId, Operator operator, String value,
            List<Trace> expected) {
        assertTraces(caller, projectId, List.of(filter(TraceField.TOPIC_ID, operator, value)), expected);
    }

    /**
     * The list and the stream give exactly the expected traces, each as the API reads it by id, and the list's count
     * and the stats' trace count give their number.
     */
    private void assertTraces(Caller caller, UUID projectId, List<TraceFilter> filters, List<Trace> expected) {
        var page = list(caller, projectId, filters);
        var stats = traceClient.getTraceStats(null, projectId, caller.apiKey(), caller.workspaceName(), filters,
                Map.of());
        var streamed = traceClient.getStreamAndAssertContent(caller.apiKey(), caller.workspaceName(),
                TraceSearchStreamRequest.builder().projectId(projectId).filters(filters).build());

        var description = filters.stream()
                .map(filter -> "%s %s %s".formatted(filter.field().getQueryParamField(),
                        filter.operator().getQueryParamOperator(), filter.value()))
                .collect(Collectors.joining(", "));
        assertThat(page.content()).as(description)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .ignoringFields(TraceAssertions.IGNORED_FIELDS_TRACES)
                .isEqualTo(expected);
        assertThat(page.total()).as(description).isEqualTo(expected.size());
        assertThat(stats.stats().stream()
                .filter(stat -> StatsMapper.TRACE_COUNT.equals(stat.getName()))
                .map(stat -> ((Number) stat.getValue()).longValue())
                .findFirst()
                .orElse(0L)).as(description).isEqualTo(expected.size());
        assertThat(streamed).as(description)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .ignoringFields(TraceAssertions.IGNORED_FIELDS_TRACES)
                .isEqualTo(expected);
    }

    private Trace.TracePage list(Caller caller, UUID projectId, List<TraceFilter> filters) {
        return traceClient.getTraces(null, projectId, caller.apiKey(), caller.workspaceName(), filters,
                List.of(), 100, Map.of());
    }

    private static TraceFilter filter(TraceField field, Operator operator, String value) {
        return TraceFilter.builder().field(field).operator(operator).value(value).build();
    }

}
