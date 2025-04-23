package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.domain.ProjectMetricsService;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.Visibility.PRIVATE;
import static com.comet.opik.api.Visibility.PUBLIC;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.PROJECT_NOT_FOUND_MESSAGE;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project Metrics Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ProjectMetricsResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/private/projects/%s/metrics";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphabetic(10);
    private static final Random RANDOM = new Random();

    private static final int TIME_BUCKET_4 = 4;
    private static final int TIME_BUCKET_3 = 3;
    private static final int TIME_BUCKET_1 = 1;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;
    private GuardrailsGenerator guardrailsGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
        this.guardrailsGenerator = new GuardrailsGenerator();

        ClientSupportUtils.config(client);

        mockTargetWorkspace();
    }

    private void mockTargetWorkspace() {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Api Key Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApiKey {

        private final String fakeApikey = UUID.randomUUID().toString();

        Stream<Arguments> publicCredentials() {
            return Stream.of(
                    arguments(API_KEY, PRIVATE, 200),
                    arguments(API_KEY, PUBLIC, 200),
                    arguments("", PRIVATE, 404),
                    arguments("", PUBLIC, 200),
                    arguments(fakeApikey, PRIVATE, 404),
                    arguments(fakeApikey, PUBLIC, 200));
        }

        @BeforeEach
        void setUp() {

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(fakeApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        @DisplayName("get project metrics: when api key is present, then return proper response")
        void getProjectMetrics__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility, int expectedCode) {
            mockTargetWorkspace();

            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build(), API_KEY,
                    WORKSPACE_NAME);
            mockGetWorkspaceIdByName(WORKSPACE_NAME, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(ProjectMetricRequest.builder()
                            .intervalStart(Instant.now().minus(1, ChronoUnit.HOURS))
                            .intervalEnd(Instant.now())
                            .metricType(MetricType.TRACE_COUNT)
                            .interval(TimeInterval.HOURLY).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(ProjectMetricResponse.class);
                    assertThat(actualEntity.projectId()).isEqualTo(projectId);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                }
            }
        }
    }

    @Nested
    @DisplayName("Session Token Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SessionTokenCookie {

        private final String sessionToken = UUID.randomUUID().toString();
        private final String fakeSessionToken = UUID.randomUUID().toString();

        Stream<Arguments> publicCredentials() {
            return Stream.of(
                    arguments(sessionToken, PRIVATE, "OK_" + UUID.randomUUID(), 200),
                    arguments(sessionToken, PUBLIC, "OK_" + UUID.randomUUID(), 200),
                    arguments(fakeSessionToken, PRIVATE, UUID.randomUUID().toString(), 404),
                    arguments(fakeSessionToken, PUBLIC, UUID.randomUUID().toString(), 200));
        }

        @BeforeAll
        void setUp() {
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching("OK_.+")))
                            .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, WORKSPACE_ID))));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(fakeSessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        @DisplayName("get project metrics: when session token is present, then return proper response")
        void getProjectMetrics__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build(), API_KEY,
                    workspaceName);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(ProjectMetricRequest.builder()
                            .intervalStart(Instant.now().minus(1, ChronoUnit.HOURS))
                            .intervalEnd(Instant.now())
                            .metricType(MetricType.TRACE_COUNT)
                            .interval(TimeInterval.HOURLY).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(ProjectMetricResponse.class);
                    assertThat(actualEntity.projectId()).isEqualTo(projectId);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                }
            }
        }
    }

    @Nested
    @DisplayName("Number of traces")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class NumberOfTracesTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // create traces in several buckets
            var expected = List.of(3, 2, 1);
            createTraces(projectName, subtract(marker, TIME_BUCKET_3, interval), expected.getFirst());
            // allow one empty hour
            createTraces(projectName, subtract(marker, TIME_BUCKET_1, interval), expected.get(1));
            createTraces(projectName, marker, expected.getLast());

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_TRACES), Integer.class,
                    Map.of(ProjectMetricsDAO.NAME_TRACES, expected.getFirst()),
                    Map.of(ProjectMetricsDAO.NAME_TRACES, expected.get(1)),
                    Map.of(ProjectMetricsDAO.NAME_TRACES, expected.getLast()));
        }

        @ParameterizedTest
        @MethodSource
        void invalidParameters(Entity request, String expectedErr) {
            // setup
            mockTargetWorkspace();

            // SUT
            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, UUID.randomUUID()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(request)) {

                // assertions
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(response.hasEntity()).isTrue();

                var actualError = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

                assertThat(actualError.getMessage()).isEqualTo(expectedErr);
            }
        }

        public static Stream<Arguments> invalidParameters() {
            Instant now = Instant.now();
            var validReq = ProjectMetricRequest.builder()
                    .intervalStart(now.minus(1, ChronoUnit.HOURS))
                    .intervalEnd(now)
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(TimeInterval.HOURLY).build();

            return Stream.of(
                    arguments(named("start later than end", Entity.json(validReq.toBuilder()
                            .intervalEnd(now.minus(2, ChronoUnit.HOURS))
                            .build())), ProjectMetricsService.ERR_START_BEFORE_END),
                    arguments(named("start equal to end", Entity.json(validReq.toBuilder()
                            .intervalStart(now)
                            .intervalEnd(now)
                            .build())), ProjectMetricsService.ERR_START_BEFORE_END));
        }

        @Test
        void invalidMetricType() {
            // setup
            mockTargetWorkspace();

            // SUT
            Instant now = Instant.now();
            var request = Entity.entity(JsonUtils.writeValueAsString(ProjectMetricRequest.builder()
                    .intervalStart(now.minus(1, ChronoUnit.HOURS))
                    .intervalEnd(now)
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(TimeInterval.HOURLY).build().toBuilder()
                    .metricType(MetricType.DURATION)
                    .build())
                    .replace(MetricType.DURATION.toString(), "non-existing-metric"),
                    ContentType.APPLICATION_JSON.toString());
            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, UUID.randomUUID()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(request)) {

                // assertions
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(response.hasEntity()).isTrue();

                var actualError = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

                assertThat(actualError.getMessage()).startsWith(
                        "Unable to process JSON. Cannot deserialize value of type `com.comet.opik.api.metrics.MetricType` from String \"non-existing-metric\"");
            }
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            Map<String, Integer> emptyTraces = new HashMap<>() {
                {
                    put(ProjectMetricsDAO.NAME_TRACES, null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_TRACES), Integer.class, emptyTraces,
                    emptyTraces, emptyTraces);
        }

        private void createTraces(String projectName, Instant marker, int count) {
            List<Trace> traces = IntStream.range(0, count)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(marker.plus(i, ChronoUnit.SECONDS))
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        }
    }

    @Nested
    @DisplayName("Feedback scores")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FeedbackScoresTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var scoresMinus3 = createFeedbackScores(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            var scoresMinus1 = createFeedbackScores(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var scores = createFeedbackScores(projectName, marker, names);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, names, BigDecimal.class, scoresMinus3, scoresMinus1, scores);
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put("", null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(""), BigDecimal.class, empty, empty, empty);
        }

        private Map<String, BigDecimal> createFeedbackScores(
                String projectName, Instant marker, List<String> scoreNames) {
            return IntStream.range(0, 5)
                    .mapToObj(i -> {
                        // create a trace
                        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                                .projectName(projectName)
                                .startTime(marker.plus(i, ChronoUnit.SECONDS))
                                .build();

                        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

                        // create several feedback scores for that trace
                        List<FeedbackScoreBatchItem> scores = scoreNames.stream()
                                .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                        .name(name)
                                        .projectName(projectName)
                                        .id(trace.id())
                                        .build())
                                .toList();

                        traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

                        return scores;
                    }).flatMap(List::stream)
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::name))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> calcAverage(e.getValue().stream().map(FeedbackScoreBatchItem::value)
                                    .toList())));
        }

        private static BigDecimal calcAverage(List<BigDecimal> scores) {
            BigDecimal sum = scores.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(new BigDecimal(scores.size()), RoundingMode.UP);
        }
    }

    @Nested
    @DisplayName("Token usage")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TokenUsageTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var usageMinus3 = createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            var usageMinus1 = createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var usage = createSpans(projectName, marker, names);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, names, Long.class, usageMinus3, usageMinus1, usage);
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            getAndAssertEmpty(projectId, interval, marker);
        }

        @ParameterizedTest
        @EmptySource
        @NullSource
        void emptyUsage(List<String> names) {
            TimeInterval interval = TimeInterval.HOURLY;
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            createSpans(projectName, marker, names);

            getAndAssertEmpty(projectId, interval, marker);
        }

        private Map<String, Long> createSpans(
                String projectName, Instant marker, List<String> usageNames) {
            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(marker.plusSeconds(i))
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            List<Span> spans = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(usageNames == null
                                    ? null
                                    : usageNames.stream().collect(
                                            Collectors.toMap(name -> name, n -> RANDOM.nextInt())))
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

            return spans.stream().map(Span::usage)
                    .filter(Objects::nonNull)
                    .flatMap(i -> i.entrySet().stream())
                    .collect(Collectors.groupingBy(Map.Entry::getKey))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().stream()
                                    .filter(usage -> usage.getKey().equals(entry.getKey()))
                                    .mapToLong(Map.Entry::getValue).sum()));
        }

        private void getAndAssertEmpty(UUID projectId, TimeInterval interval, Instant marker) {
            Map<String, Long> empty = new HashMap<>() {
                {
                    put("", null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(""), Long.class, empty, empty, empty);
        }
    }

    @Nested
    @DisplayName("Cost")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CostTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var costMinus3 = Map.of(ProjectMetricsDAO.NAME_COST,
                    createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval)));
            var costMinus1 = Map.of(ProjectMetricsDAO.NAME_COST,
                    createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval)));
            var costCurrent = Map.of(ProjectMetricsDAO.NAME_COST, createSpans(projectName, marker));

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.COST)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_COST), BigDecimal.class, costMinus3, costMinus1,
                    costCurrent);
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put(ProjectMetricsDAO.NAME_COST, null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.COST)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_COST), BigDecimal.class, empty, empty, empty);
        }

        private BigDecimal createSpans(
                String projectName, Instant marker) {
            var MODEL_NAME = "gpt-3.5-turbo";
            var provider = "openai";

            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(marker.plusSeconds(i))
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            List<Span> spans = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .model(MODEL_NAME)
                            .provider(provider)
                            .usage(Map.of(
                                    "prompt_tokens", RANDOM.nextInt(),
                                    "completion_tokens", RANDOM.nextInt()))
                            .traceId(trace.id())
                            .totalEstimatedCost(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
            return spans.stream()
                    .map(span -> CostService.calculateCost(MODEL_NAME, provider, span.usage(), null))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    @Nested
    @DisplayName("Duration")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DurationTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            List<BigDecimal> durationsMinus3 = createTraces(projectName, subtract(marker, TIME_BUCKET_3, interval));
            List<BigDecimal> durationsMinus1 = createTraces(projectName, subtract(marker, TIME_BUCKET_1, interval));
            List<BigDecimal> durationsCurrent = createTraces(projectName, marker);

            var durationMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_DURATION_P50, durationsMinus3.get(0),
                    ProjectMetricsDAO.NAME_DURATION_P90, durationsMinus3.get(1),
                    ProjectMetricsDAO.NAME_DURATION_P99, durationsMinus3.getLast());
            var durationMinus1 = Map.of(
                    ProjectMetricsDAO.NAME_DURATION_P50, durationsMinus1.get(0),
                    ProjectMetricsDAO.NAME_DURATION_P90, durationsMinus1.get(1),
                    ProjectMetricsDAO.NAME_DURATION_P99, durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    ProjectMetricsDAO.NAME_DURATION_P50, durationsCurrent.get(0),
                    ProjectMetricsDAO.NAME_DURATION_P90, durationsCurrent.get(1),
                    ProjectMetricsDAO.NAME_DURATION_P99, durationsCurrent.getLast());

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .build(),
                    marker,
                    List.of(ProjectMetricsDAO.NAME_DURATION_P50, ProjectMetricsDAO.NAME_DURATION_P90,
                            ProjectMetricsDAO.NAME_DURATION_P99),
                    BigDecimal.class,
                    durationMinus3,
                    durationMinus1,
                    durationCurrent);
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put(ProjectMetricsDAO.NAME_DURATION_P50, null);
                    put(ProjectMetricsDAO.NAME_DURATION_P90, null);
                    put(ProjectMetricsDAO.NAME_DURATION_P99, null);
                }
            };

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .build(),
                    marker,
                    List.of(ProjectMetricsDAO.NAME_DURATION_P50, ProjectMetricsDAO.NAME_DURATION_P90,
                            ProjectMetricsDAO.NAME_DURATION_P99),
                    BigDecimal.class,
                    empty,
                    empty,
                    empty);
        }

        private List<BigDecimal> createTraces(String projectName, Instant marker) {
            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(marker)
                            .endTime(marker.plusMillis(RANDOM.nextInt(1000)))
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            return StatsUtils.calculateQuantiles(
                    traces.stream()
                            .filter(entity -> entity.endTime() != null)
                            .map(entity -> entity.startTime().until(entity.endTime(), ChronoUnit.MICROS))
                            .map(duration -> duration / 1_000.0)
                            .toList(),
                    List.of(0.50, 0.90, 0.99));
        }
    }

    @Nested
    @DisplayName("Guardrails failed count")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GuardrailsFailedCountTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var guardrailsMinus3 = Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT,
                    createTracesWithGuardrails(projectName, subtract(marker, TIME_BUCKET_3, interval)));
            var guardrailsMinus1 = Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT,
                    createTracesWithGuardrails(projectName, subtract(marker, TIME_BUCKET_1, interval)));
            var guardrails = Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT,
                    createTracesWithGuardrails(projectName, marker));

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.GUARDRAILS_FAILED_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT), Long.class,
                    guardrailsMinus3, guardrailsMinus1, guardrails);
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            getAndAssertEmpty(projectId, interval, marker);
        }

        private Long createTracesWithGuardrails(String projectName, Instant marker) {
            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(marker.plusSeconds(i))
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            return traces.stream()
                    .map(trace -> {
                        var guardrails = guardrailsGenerator.generateGuardrailsForTrace(trace.id(), randomUUID(),
                                trace.projectName());
                        guardrailsResourceClient.addBatch(guardrails, API_KEY, WORKSPACE_NAME);
                        return guardrails;
                    })
                    .flatMap(List::stream)
                    .filter(guardrail -> guardrail.result() == GuardrailResult.FAILED)
                    .count();
        }

        private void getAndAssertEmpty(UUID projectId, TimeInterval interval, Instant marker) {
            Map<String, Long> empty = new HashMap<>() {
                {
                    put(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT, null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.GUARDRAILS_FAILED_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT), Long.class, empty, empty,
                    empty);
        }
    }

    private <T extends Number> ProjectMetricResponse<T> getProjectMetrics(
            UUID projectId, ProjectMetricRequest request, Class<T> aClass) {
        try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();

            Type parameterize = TypeUtils.parameterize(ProjectMetricResponse.class, aClass);
            return response.readEntity(new GenericType<>(parameterize));
        }
    }

    private <T extends Number> void getMetricsAndAssert(
            UUID projectId, ProjectMetricRequest request, Instant marker, List<String> names, Class<T> aClass,
            Map<String, T> minus3, Map<String, T> minus1, Map<String, T> current) {
        var response = getProjectMetrics(projectId, request, aClass);

        var expected = createExpected(marker, request.interval(), names, minus3, minus1, current);

        // assertions
        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.metricType()).isEqualTo(request.metricType());
        assertThat(response.interval()).isEqualTo(request.interval());
        assertThat(response.results()).hasSize(names.size());

        assertThat(response.results()).hasSize(expected.size());
        assertThat(response.results())
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .ignoringCollectionOrder()
                .isEqualTo(expected);
    }

    private static <T extends Number> List<ProjectMetricResponse.Results<T>> createExpected(
            Instant marker, TimeInterval interval, List<String> names, Map<String, T> dataMinus3,
            Map<String, T> dataMinus1, Map<String, T> dataNow) {
        return names.stream()
                .map(name -> {
                    var expectedUsage = Arrays.asList(
                            null,
                            dataMinus3.get(name),
                            null,
                            dataMinus1.get(name),
                            dataNow.get(name));

                    return ProjectMetricResponse.Results.<T>builder()
                            .name(name)
                            .data(IntStream.range(0, expectedUsage.size())
                                    .mapToObj(i -> DataPoint.<T>builder()
                                            .time(subtract(marker, expectedUsage.size() - i - 1, interval))
                                            .value(expectedUsage.get(i)).build())
                                    .toList())
                            .build();
                }).toList();
    }

    private static Instant subtract(Instant instant, int count, TimeInterval interval) {
        if (interval == TimeInterval.WEEKLY) {
            count *= 7;
        }

        return instant.minus(count, interval == TimeInterval.HOURLY ? ChronoUnit.HOURS : ChronoUnit.DAYS);
    }

    private static Instant getIntervalStart(TimeInterval interval) {
        if (interval == TimeInterval.WEEKLY) {
            return findLastMonday(LocalDate.now()).atStartOfDay(ZoneId.of("UTC")).toInstant();
        }

        return Instant.now().truncatedTo(interval == TimeInterval.HOURLY ? ChronoUnit.HOURS : ChronoUnit.DAYS);
    }

    private static LocalDate findLastMonday(LocalDate today) {
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            return today;
        }

        return today.minusDays(today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    private void mockGetWorkspaceIdByName(String workspaceName, String workspaceId) {
        AuthTestUtils.mockGetWorkspaceIdByName(wireMock.server(), workspaceName, workspaceId);
    }
}
