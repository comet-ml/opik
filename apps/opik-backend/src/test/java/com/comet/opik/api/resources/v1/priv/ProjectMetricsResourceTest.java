package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Project;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
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
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.domain.ProjectMetricsService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple4;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.TraceThreadStatus.ACTIVE;
import static com.comet.opik.api.Visibility.PRIVATE;
import static com.comet.opik.api.Visibility.PUBLIC;
import static com.comet.opik.api.filter.Operator.CONTAINS;
import static com.comet.opik.api.filter.Operator.EQUAL;
import static com.comet.opik.api.filter.Operator.GREATER_THAN;
import static com.comet.opik.api.filter.Operator.IS_EMPTY;
import static com.comet.opik.api.filter.Operator.IS_NOT_EMPTY;
import static com.comet.opik.api.filter.Operator.NOT_EQUAL;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
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
import static java.util.Collections.singletonMap;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project Metrics Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ProjectMetricsResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/private/projects/%s/metrics";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.secure().nextAlphabetic(10);
    private static final Random RANDOM = new Random();

    private static final int TIME_BUCKET_4 = 4;
    private static final int TIME_BUCKET_3 = 3;
    private static final int TIME_BUCKET_1 = 1;

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, clickHouseContainer, mysql, zookeeperContainer).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                mysql.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), redisContainer.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private IdGenerator idGenerator;

    private String baseURI;
    private ClientSupport client;
    private ProjectMetricsResourceClient projectMetricsResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;
    private GuardrailsGenerator guardrailsGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.projectMetricsResourceClient = new ProjectMetricsResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
        this.guardrailsGenerator = new GuardrailsGenerator();

        ClientSupportUtils.config(client);

        mockTargetWorkspace();
        this.idGenerator = idGenerator;
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
        void happyPathWithFilter(Function<Trace, TraceFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // create traces in several buckets
            var expected = List.of(3, 2, 1);
            var traces = createTraces(projectName, subtract(marker, TIME_BUCKET_3, interval), expected.getFirst());
            // allow one empty hour
            createTraces(projectName, subtract(marker, TIME_BUCKET_1, interval), expected.get(1));
            createTraces(projectName, marker, expected.getLast());

            // create feedback scores for the first bucket traces
            List<FeedbackScoreBatchItem> scores = getScoreBatchItems(traces);

            traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            // create guardrails for the first trace
            var guardrail = guardrailsGenerator.generateGuardrailsForTrace(
                    traces.getFirst().id(), randomUUID(), projectName).getFirst().toBuilder()
                    .result(GuardrailResult.PASSED)
                    .build();

            guardrailsResourceClient.addBatch(List.of(guardrail), API_KEY, WORKSPACE_NAME);
            var expectedValues = Arrays.asList(1, 2, 3, 2, 1, null);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .traceFilters(List.of(getFilter.apply(traces.getFirst())))
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_TRACES), Integer.class,
                    singletonMap(ProjectMetricsDAO.NAME_TRACES, expectedValues.get(expectedIndexes.get(0))),
                    singletonMap(ProjectMetricsDAO.NAME_TRACES, expectedValues.get(expectedIndexes.get(1))),
                    singletonMap(ProjectMetricsDAO.NAME_TRACES, expectedValues.get(expectedIndexes.get(2))));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.happyPathWithFilterArguments();
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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

        private List<Trace> createTraces(String projectName, Instant marker, int count) {
            List<Trace> traces = IntStream.range(0, count)
                    .mapToObj(i -> {
                        Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Trace.class).toBuilder()
                                .id(idGenerator.generateId(traceStartTime))
                                .projectName(projectName)
                                .startTime(traceStartTime)
                                .build();
                    })
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            return traces;
        }
    }

    private static List<FeedbackScoreBatchItem> getScoreBatchItems(List<Trace> traces) {
        return traces.stream()
                .flatMap(trace -> trace.feedbackScores()
                        .stream()
                        .map(score -> mapFeedbackScore(score, trace)))
                .toList();
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
        @MethodSource
        void happyPathWithFilter(Function<Trace, TraceFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);
            Instant traceStartTime = subtract(marker, TIME_BUCKET_3, interval).plus(6, ChronoUnit.SECONDS);
            final Trace traceForFilterInit = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .startTime(traceStartTime)
                    .id(idGenerator.generateId(traceStartTime))
                    .build();

            Tuple4<List<FeedbackScoreBatchItem>, List<FeedbackScoreBatchItem>, List<FeedbackScoreBatchItem>, List<FeedbackScoreBatchItem>> pair = Mono
                    .zip(
                            Mono.fromCallable(() -> createFeedbackScores(projectName,
                                    subtract(marker, TIME_BUCKET_3, interval), names, 1, traceForFilterInit)),
                            Mono.fromCallable(() -> createFeedbackScores(projectName,
                                    subtract(marker, TIME_BUCKET_3, interval), names, 5, null)),
                            Mono.fromCallable(() -> createFeedbackScores(projectName,
                                    subtract(marker, TIME_BUCKET_1, interval), names, 5, null)),
                            Mono.fromCallable(() -> createFeedbackScores(projectName, marker, names, 5, null)))
                    .block();

            var scoresMinus3ForFilter = pair.getT1();
            var scoresMinus3 = pair.getT2();
            var scoresMinus1 = pair.getT3();
            var scores = pair.getT4();

            final Trace traceForFilter = traceForFilterInit.toBuilder()
                    .feedbackScores(feedbackScoresMapper(scoresMinus3ForFilter))
                    .build();

            var filteredScoresMinus3Map = aggregateFeedbackScores(scoresMinus3ForFilter);
            var scoresExcludingFilteredMinus3Map = aggregateFeedbackScores(scoresMinus3);
            var totalScoresMinus3Map = aggregateFeedbackScores(
                    Stream.concat(scoresMinus3ForFilter.stream(), scoresMinus3.stream()).toList());
            var scoresMinus1Map = aggregateFeedbackScores(scoresMinus1);
            var scoresMap = aggregateFeedbackScores(scores);

            var expectedValues = Arrays.asList(filteredScoresMinus3Map, scoresExcludingFilteredMinus3Map,
                    totalScoresMinus3Map, scoresMinus1Map,
                    scoresMap, null);

            // create guardrails for the first trace
            var guardrail = guardrailsGenerator.generateGuardrailsForTrace(
                    traceForFilter.id(), randomUUID(), projectName).getFirst().toBuilder()
                    .result(GuardrailResult.PASSED)
                    .build();

            guardrailsResourceClient.addBatch(List.of(guardrail), API_KEY, WORKSPACE_NAME);

            var filter = getFilter.apply(traceForFilter);
            if (filter.field() == TraceField.FEEDBACK_SCORES && filter.operator() == IS_EMPTY) {

                filter = filter.toBuilder()
                        .key("NonExistingScore")
                        .build();

                expectedIndexes = List.of(2, 3, 4);
            }

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .traceFilters(List.of(filter))
                    .build(), marker, names, BigDecimal.class, expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)), expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.happyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
            return aggregateFeedbackScores(createFeedbackScores(projectName, marker, scoreNames, 5, null));
        }

        private List<FeedbackScoreBatchItem> createFeedbackScores(
                String projectName, Instant marker, List<String> scoreNames, int tracesCount, Trace traceForFilter) {
            return IntStream.range(0, tracesCount)
                    .mapToObj(i -> {
                        // create a trace
                        Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        Trace trace = traceForFilter != null
                                ? traceForFilter
                                : factory.manufacturePojo(Trace.class).toBuilder()
                                        .projectName(projectName)
                                        .startTime(traceStartTime)
                                        .id(idGenerator.generateId(traceStartTime))
                                        .build();

                        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

                        // create several feedback scores for that trace
                        List<FeedbackScoreBatchItem> scores = scoreNames.stream()
                                .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                        .name(name)
                                        .projectName(projectName)
                                        .id(trace.id())
                                        .build())
                                .collect(Collectors.toList());

                        traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

                        return scores;
                    }).flatMap(List::stream)
                    .toList();
        }

        private Map<String, BigDecimal> aggregateFeedbackScores(List<FeedbackScoreBatchItem> scores) {
            return scores.stream()
                    .collect(Collectors.groupingBy(FeedbackScoreItem::name))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> calcAverage(e.getValue().stream().map(FeedbackScoreItem::value)
                                    .toList())));
        }
    }

    @Nested
    @DisplayName("Thread feedback scores")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadFeedbackScoresTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var scoresMinus3 = createThreadFeedbackScores(projectName, subtract(marker, TIME_BUCKET_3, interval),
                    names);
            var scoresMinus1 = createThreadFeedbackScores(projectName, subtract(marker, TIME_BUCKET_1, interval),
                    names);
            var scores = createThreadFeedbackScores(projectName, marker, names);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, names, BigDecimal.class, scoresMinus3, scoresMinus1, scores);
        }

        @ParameterizedTest
        @MethodSource
        @Disabled
        void happyPathWithFilter(Function<TraceThread, TraceThreadFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var threadsWithScoresMinus3 = createThreadWithFeedbackScores(projectName,
                    subtract(marker, TIME_BUCKET_3, interval),
                    names);
            var threadForFilterId = threadsWithScoresMinus3.getLeft().getFirst();

            var filteredScoresMinus3 = aggregateFeedbackScores(List.of(threadsWithScoresMinus3.getRight().getFirst()));
            var restScoresMinus3 = aggregateFeedbackScores(
                    threadsWithScoresMinus3.getRight().subList(1, threadsWithScoresMinus3.getRight().size()));

            var scoresMinus1 = createThreadFeedbackScores(projectName, subtract(marker, TIME_BUCKET_1, interval),
                    names);
            var scores = createThreadFeedbackScores(projectName, marker, names);

            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put("", null);
                }
            };

            var expectedValues = new ArrayList<>(
                    List.of(filteredScoresMinus3, restScoresMinus3, scoresMinus1, scores, empty));

            var createdThread = traceResourceClient.getTraceThread(threadForFilterId, projectId, API_KEY,
                    WORKSPACE_NAME);

            assertThat(createdThread.feedbackScores().size()).isEqualTo(5);

            Instant traceStartTime = subtract(marker, TIME_BUCKET_3, interval).plus(40, ChronoUnit.SECONDS);
            // create one more trace for a thread to have some data for filtering
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .threadId(threadForFilterId)
                    .startTime(traceStartTime)
                    .id(idGenerator.generateId(traceStartTime))
                    .build();

            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Add tags to the thread
            var update = factory.manufacturePojo(TraceThreadUpdate.class);
            traceResourceClient.updateThread(update, createdThread.threadModelId(), API_KEY, WORKSPACE_NAME, 204);

            // get one more time, to have actual data for lastUpdatedAt
            var updatedThread = traceResourceClient.getTraceThread(threadForFilterId, projectId, API_KEY,
                    WORKSPACE_NAME);

            boolean allEmpty = expectedIndexes.stream().allMatch(i -> i == expectedValues.size() - 1);

            var filter = getFilter.apply(updatedThread);
            if (filter.field() == TraceThreadField.FEEDBACK_SCORES && filter.operator() == IS_EMPTY) {

                filter = filter.toBuilder()
                        .key("NonExistingScore")
                        .build();

                expectedValues.add(1, aggregateFeedbackScores(threadsWithScoresMinus3.getRight()));

                return;
            }

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .threadFilters(List.of(filter))
                    .build(), marker, allEmpty ? List.of("") : names, BigDecimal.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.threadHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put("", null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(""), BigDecimal.class, empty, empty, empty);
        }

        private Map<String, BigDecimal> createThreadFeedbackScores(
                String projectName, Instant marker, List<String> scoreNames) {
            var threadsWithScores = createThreadWithFeedbackScores(projectName, marker, scoreNames);

            return aggregateFeedbackScores(threadsWithScores.getRight());
        }

        private Map<String, BigDecimal> aggregateFeedbackScores(
                List<List<FeedbackScoreBatchItemThread>> scores) {
            return scores.stream()
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(FeedbackScoreItem::name))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> calcAverage(e.getValue().stream().map(FeedbackScoreItem::value)
                                    .toList())));
        }

        private Pair<List<String>, List<List<FeedbackScoreBatchItemThread>>> createThreadWithFeedbackScores(
                String projectName, Instant marker, List<String> scoreNames) {

            List<String> threadIds = IntStream.range(0, 3).mapToObj(i -> UUID.randomUUID().toString()).toList();

            var score = IntStream.range(0, threadIds.size())
                    .mapToObj(i -> {
                        String threadId = threadIds.get(i);

                        // create a trace in the thread to ensure the thread exists
                        Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                                .projectName(projectName)
                                .threadId(threadId)
                                .startTime(traceStartTime)
                                .id(idGenerator.generateId(traceStartTime))
                                .build();

                        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

                        // close the thread
                        traceResourceClient.closeTraceThread(threadId, null, projectName, API_KEY,
                                WORKSPACE_NAME);

                        // create several feedback scores for that thread
                        List<FeedbackScoreBatchItemThread> scores = scoreNames.stream()
                                .map(name -> factory.manufacturePojo(FeedbackScoreBatchItemThread.class)
                                        .toBuilder()
                                        .name(name)
                                        .projectName(projectName)
                                        .threadId(threadId)
                                        .build())
                                .collect(Collectors.toList());

                        traceResourceClient.threadFeedbackScores(scores, API_KEY, WORKSPACE_NAME);

                        return scores;
                    }).toList();

            return Pair.of(threadIds, score);
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
        @DisplayName("interval_end is optional - filters token usage from interval_start onwards")
        void whenIntervalEndOmitted_thenFilterTokenUsageFromIntervalStart(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var usageMinus3 = createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            var usageMinus1 = createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var usage = createSpans(projectName, marker, names);

            // SUT - omit interval_end
            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .build();

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, Long.class, API_KEY,
                    WORKSPACE_NAME);

            // Verify response contains all metric names
            assertThat(response.results()).hasSizeGreaterThanOrEqualTo(names.size());
            // With no interval_end, WITH FILL is omitted, so only actual data points are returned
            response.results().forEach(result -> {
                assertThat(result.data()).hasSizeGreaterThanOrEqualTo(1);
            });
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Trace, TraceFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var tracesWithSpans = createTracesWithSpans(projectName, subtract(marker, TIME_BUCKET_3, interval), names,
                    6);
            var traceForFilter = tracesWithSpans.getLeft().getFirst();
            var filteredUsageMinus3 = aggregateSpansUsage(
                    tracesWithSpans.getRight().stream().filter(span -> span.traceId() == traceForFilter.id()).toList());
            var usageMinus3 = aggregateSpansUsage(
                    tracesWithSpans.getRight().stream().filter(span -> span.traceId() != traceForFilter.id()).toList());
            var usageMinus3Total = aggregateSpansUsage(tracesWithSpans.getRight());

            var usageMinus1 = createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var usage = createSpans(projectName, marker, names);

            var expectedValues = Arrays.asList(filteredUsageMinus3, usageMinus3, usageMinus3Total, usageMinus1, usage,
                    null);

            // create feedback scores for the first trace
            List<FeedbackScoreBatchItem> scores = getScoreBatchItems(tracesWithSpans);

            traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            // create guardrails for the first trace
            var guardrail = guardrailsGenerator.generateGuardrailsForTrace(
                    traceForFilter.id(), randomUUID(), projectName).getFirst().toBuilder()
                    .result(GuardrailResult.PASSED)
                    .build();

            guardrailsResourceClient.addBatch(List.of(guardrail), API_KEY, WORKSPACE_NAME);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .traceFilters(List.of(getFilter.apply(traceForFilter)))
                    .build(), marker, names, Long.class, expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.happyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            createSpans(projectName, marker, names);

            getAndAssertEmpty(projectId, interval, marker);
        }

        private Map<String, Long> createSpans(
                String projectName, Instant marker, List<String> usageNames) {
            return aggregateSpansUsage(createTracesWithSpans(
                    projectName, marker, usageNames, 5).getRight());
        }

        private Pair<List<Trace>, List<Span>> createTracesWithSpans(
                String projectName, Instant marker, List<String> usageNames, int tracesCount) {
            List<Trace> traces = IntStream.range(0, tracesCount)
                    .mapToObj(i -> {
                        Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Trace.class).toBuilder()
                                .id(idGenerator.generateId(traceStartTime))
                                .projectName(projectName)
                                .startTime(traceStartTime)
                                .build();
                    })
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            List<Span> spans = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(usageNames == null
                                    ? null
                                    : usageNames.stream().collect(
                                            Collectors.toMap(name -> name, n -> Math.abs(RANDOM.nextInt()))))
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

            return Pair.of(traces, spans);
        }

        private Map<String, Long> aggregateSpansUsage(List<Span> spans) {
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

    private static List<FeedbackScoreBatchItem> getScoreBatchItems(Pair<List<Trace>, List<Span>> tracesWithSpans) {
        return getScoreBatchItems(tracesWithSpans.getLeft());
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
        @DisplayName("interval_end is optional - filters cost from interval_start onwards")
        void whenIntervalEndOmitted_thenFilterCostFromIntervalStart(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval));
            createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval));
            createSpans(projectName, marker);

            // SUT - omit interval_end
            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.COST)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .build();

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, BigDecimal.class, API_KEY,
                    WORKSPACE_NAME);

            // Verify response
            assertThat(response.results()).hasSize(1);
            assertThat(response.results().getFirst().name()).isEqualTo(ProjectMetricsDAO.NAME_COST);
            // With no interval_end, WITH FILL is omitted, so only actual data points are returned
            assertThat(response.results().getFirst().data()).hasSizeGreaterThanOrEqualTo(3);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Trace, TraceFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var tracesWithSpans = createSpans(projectName, subtract(marker, TIME_BUCKET_3, interval), 6);
            var traceForFilter = tracesWithSpans.getLeft().getFirst();
            var filteredCostMinus3 = calculateCost(
                    tracesWithSpans.getRight().stream().filter(span -> span.traceId() == traceForFilter.id()).toList());
            var costMinus3Total = calculateCost(tracesWithSpans.getRight());

            var costMinus1 = Map.of(ProjectMetricsDAO.NAME_COST,
                    createSpans(projectName, subtract(marker, TIME_BUCKET_1, interval)));
            var costCurrent = Map.of(ProjectMetricsDAO.NAME_COST, createSpans(projectName, marker));

            var expectedValues = Arrays.asList(Map.of(ProjectMetricsDAO.NAME_COST, filteredCostMinus3),
                    Map.of(ProjectMetricsDAO.NAME_COST, costMinus3Total.subtract(filteredCostMinus3)),
                    Map.of(ProjectMetricsDAO.NAME_COST, costMinus3Total), costMinus1, costCurrent, null);

            List<FeedbackScoreBatchItem> scores = getScoreBatchItems(tracesWithSpans);

            // create feedback scores for the first trace
            traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            // create guardrails for the first trace
            var guardrail = guardrailsGenerator.generateGuardrailsForTrace(
                    traceForFilter.id(), randomUUID(), projectName).getFirst().toBuilder()
                    .result(GuardrailResult.PASSED)
                    .build();

            guardrailsResourceClient.addBatch(List.of(guardrail), API_KEY, WORKSPACE_NAME);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.COST)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .traceFilters(List.of(getFilter.apply(traceForFilter)))
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_COST), BigDecimal.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.happyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
            return calculateCost(createSpans(projectName, marker, 5).getRight());
        }

        private Pair<List<Trace>, List<Span>> createSpans(
                String projectName, Instant marker, int traceCount) {

            List<Trace> traces = IntStream.range(0, traceCount)
                    .mapToObj(i -> {
                        Instant traceStartTime = marker.plusSeconds(i);
                        return factory.manufacturePojo(Trace.class).toBuilder()
                                .projectName(projectName)
                                .startTime(traceStartTime)
                                .id(idGenerator.generateId(traceStartTime))
                                .build();
                    })
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            List<Span> spans = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .totalEstimatedCost(BigDecimal.valueOf(Math.abs(RANDOM.nextInt(10000))))
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
            return Pair.of(traces, spans);
        }

        private BigDecimal calculateCost(List<Span> spans) {
            return spans.stream()
                    .map(Span::totalEstimatedCost)
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            List<BigDecimal> durationsMinus3 = createTraces(projectName, subtract(marker, TIME_BUCKET_3, interval));
            List<BigDecimal> durationsMinus1 = createTraces(projectName, subtract(marker, TIME_BUCKET_1, interval));
            List<BigDecimal> durationsCurrent = createTraces(projectName, marker);

            var durationMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsMinus3.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsMinus3.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsMinus3.getLast());
            var durationMinus1 = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsMinus1.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsMinus1.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsCurrent.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsCurrent.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsCurrent.getLast());

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .build(),
                    marker,
                    List.of(ProjectMetricsDAO.NAME_TRACE_DURATION_P50, ProjectMetricsDAO.NAME_TRACE_DURATION_P90,
                            ProjectMetricsDAO.NAME_TRACE_DURATION_P99),
                    BigDecimal.class,
                    durationMinus3,
                    durationMinus1,
                    durationCurrent);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Trace, TraceFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var startTime = subtract(marker, TIME_BUCKET_3, interval).plusMillis(RANDOM.nextInt(50));
            var endTime = subtract(marker, TIME_BUCKET_3, interval).plus(4, ChronoUnit.HOURS);

            List<Trace> traceForFilter = createTraces(projectName, subtract(marker, TIME_BUCKET_3, interval), 1,
                    startTime, endTime);
            List<Trace> tracesMinus3 = createTraces(projectName, subtract(marker, TIME_BUCKET_3, interval), 5, null,
                    endTime.plusMillis(RANDOM.nextInt(50)));

            List<BigDecimal> durationsTraceForFilter = calculateQuantiles(traceForFilter);
            List<BigDecimal> durationsMinus3 = calculateQuantiles(tracesMinus3);
            List<BigDecimal> durationsTotalMinus3 = calculateQuantiles(
                    Stream.concat(traceForFilter.stream(), tracesMinus3.stream()).toList());

            List<BigDecimal> durationsMinus1 = createTraces(projectName, subtract(marker, TIME_BUCKET_1, interval));
            List<BigDecimal> durationsCurrent = createTraces(projectName, marker);

            var durationTraceForFilter = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsTraceForFilter.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsTraceForFilter.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsTraceForFilter.getLast());
            var durationMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsMinus3.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsMinus3.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsMinus3.getLast());
            var durationTotalMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsTotalMinus3.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsTotalMinus3.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsTotalMinus3.getLast());
            var durationMinus1 = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsMinus1.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsMinus1.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P50, durationsCurrent.get(0),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P90, durationsCurrent.get(1),
                    ProjectMetricsDAO.NAME_TRACE_DURATION_P99, durationsCurrent.getLast());

            var expectedValues = Arrays.asList(durationTraceForFilter, durationMinus3, durationTotalMinus3,
                    durationMinus1, durationCurrent, null);

            // create feedback scores for the first trace

            List<FeedbackScoreBatchItem> scores = getScoreBatchItems(traceForFilter);

            traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            // create guardrails for the first trace
            var guardrail = guardrailsGenerator.generateGuardrailsForTrace(
                    traceForFilter.getFirst().id(), randomUUID(), projectName).getFirst().toBuilder()
                    .result(GuardrailResult.PASSED)
                    .build();

            guardrailsResourceClient.addBatch(List.of(guardrail), API_KEY, WORKSPACE_NAME);

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .traceFilters(List.of(getFilter.apply(traceForFilter.getFirst())))
                            .build(),
                    marker,
                    List.of(ProjectMetricsDAO.NAME_TRACE_DURATION_P50, ProjectMetricsDAO.NAME_TRACE_DURATION_P90,
                            ProjectMetricsDAO.NAME_TRACE_DURATION_P99),
                    BigDecimal.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.happyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put(ProjectMetricsDAO.NAME_TRACE_DURATION_P50, null);
                    put(ProjectMetricsDAO.NAME_TRACE_DURATION_P90, null);
                    put(ProjectMetricsDAO.NAME_TRACE_DURATION_P99, null);
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
                    List.of(ProjectMetricsDAO.NAME_TRACE_DURATION_P50, ProjectMetricsDAO.NAME_TRACE_DURATION_P90,
                            ProjectMetricsDAO.NAME_TRACE_DURATION_P99),
                    BigDecimal.class,
                    empty,
                    empty,
                    empty);
        }

        private List<BigDecimal> createTraces(String projectName, Instant marker) {
            List<Trace> traces = createTraces(projectName, marker, 5, null, null);

            return calculateQuantiles(traces);
        }

        private List<Trace> createTraces(String projectName, Instant marker, int tracesCount, Instant startTime,
                Instant endTime) {
            List<Trace> traces = IntStream.range(0, tracesCount)
                    .mapToObj(i -> {
                        Instant traceStartTime = startTime != null
                                ? startTime
                                : marker.plusMillis(RANDOM.nextInt(50, 100));
                        return factory.manufacturePojo(Trace.class).toBuilder()
                                .id(idGenerator.generateId(traceStartTime))
                                .projectName(projectName)
                                .startTime(traceStartTime)
                                .endTime(endTime != null ? endTime : marker.plusMillis(RANDOM.nextInt(100, 1000)))
                                .build();
                    })
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            return traces;
        }

        private List<BigDecimal> calculateQuantiles(List<Trace> traces) {
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
        @MethodSource
        void happyPathWithFilter(Function<Trace, TraceFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var tracesWithGuardrails = createTracesWithGuardrails(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), 6);
            var traceForFilter = tracesWithGuardrails.getLeft().getFirst();
            var filteredGuardrailsMinus3 = tracesWithGuardrails.getRight().getFirst(); // guardrails count for first trace
            var guardrailsMinus3Total = tracesWithGuardrails.getRight().stream().mapToLong(Long::longValue).sum();

            var guardrailsMinus1 = Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT,
                    createTracesWithGuardrails(projectName, subtract(marker, TIME_BUCKET_1, interval)));
            var guardrails = Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT,
                    createTracesWithGuardrails(projectName, marker));

            var expectedValues = Arrays.asList(
                    Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT, filteredGuardrailsMinus3),
                    Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT,
                            guardrailsMinus3Total - filteredGuardrailsMinus3),
                    Map.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT, guardrailsMinus3Total),
                    guardrailsMinus1,
                    guardrails,
                    null);

            // create feedback scores for the first trace
            List<FeedbackScoreBatchItem> feedbackScores = getScoreBatchItems(List.of(traceForFilter));

            traceResourceClient.feedbackScores(feedbackScores, API_KEY, WORKSPACE_NAME);

            var filter = getFilter.apply(traceForFilter);

            if (filter.field() == TraceField.GUARDRAILS) {
                return;
            }

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.GUARDRAILS_FAILED_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .traceFilters(List.of(filter))
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_GUARDRAILS_FAILED_COUNT), Long.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.happyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            getAndAssertEmpty(projectId, interval, marker);
        }

        private Long createTracesWithGuardrails(String projectName, Instant marker) {
            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Trace.class).toBuilder()
                                .projectName(projectName)
                                .startTime(traceStartTime)
                                .id(idGenerator.generateId(traceStartTime))
                                .build();
                    })
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            return traces.stream()
                    .map(trace -> {
                        List<Guardrail> guardrails = guardrailsGenerator.generateGuardrailsForTrace(trace.id(),
                                randomUUID(),
                                trace.projectName());
                        guardrailsResourceClient.addBatch(guardrails, API_KEY, WORKSPACE_NAME);
                        return guardrails;
                    })
                    .flatMap(List::stream)
                    .filter(guardrail -> guardrail.result() == GuardrailResult.FAILED)
                    .count();
        }

        private Pair<List<Trace>, List<Long>> createTracesWithGuardrails(String projectName, Instant marker,
                int tracesCount) {
            List<Trace> traces = IntStream.range(0, tracesCount)
                    .mapToObj(i -> {
                        Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Trace.class).toBuilder()
                                .projectName(projectName)
                                .startTime(traceStartTime)
                                .id(idGenerator.generateId(traceStartTime))
                                .build();
                    })
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

            List<Long> guardrailCounts = traces.stream()
                    .map(trace -> {
                        var guardrails = guardrailsGenerator.generateGuardrailsForTrace(trace.id(), randomUUID(),
                                trace.projectName());
                        var guardrailsWithAtLeastOneFailed = IntStream.range(0, guardrails.size())
                                .mapToObj(i -> i == 0
                                        ? guardrails.get(i).toBuilder().result(GuardrailResult.FAILED).build()
                                        : guardrails.get(i))
                                .toList();
                        guardrailsResourceClient.addBatch(guardrailsWithAtLeastOneFailed, API_KEY, WORKSPACE_NAME);
                        return guardrailsWithAtLeastOneFailed.stream()
                                .filter(guardrail -> guardrail.result() == GuardrailResult.FAILED)
                                .count();
                    })
                    .toList();

            return Pair.of(traces, guardrailCounts);
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

    private static FeedbackScoreBatchItem mapFeedbackScore(FeedbackScore score, Trace trace) {
        return FeedbackScoreBatchItem.builder()
                .reason(score.reason())
                .name(score.name())
                .value(score.value())
                .source(score.source())
                .projectName(trace.projectName())
                .id(trace.id())
                .build();
    }

    @Nested
    @DisplayName("Thread count")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadCountTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            var projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(), API_KEY,
                    WORKSPACE_NAME);
            Instant marker = getIntervalStart(interval);

            // Create traces with different thread_ids at different times with different thread counts
            Long threadCountMinus3 = (long) createTracesWithThreads(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), 2, null).size();
            Long threadCountMinus1 = (long) createTracesWithThreads(projectName,
                    subtract(marker, TIME_BUCKET_1, interval), 4, null).size();
            Long threadCountNow = (long) createTracesWithThreads(projectName, marker, 3, null).size();

            // SUT
            Map<String, Long> minus3 = Map.of(ProjectMetricsDAO.NAME_THREADS, threadCountMinus3);
            Map<String, Long> minus1 = Map.of(ProjectMetricsDAO.NAME_THREADS, threadCountMinus1);
            Map<String, Long> current = Map.of(ProjectMetricsDAO.NAME_THREADS, threadCountNow);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_THREADS), Long.class,
                    minus3, minus1, current);
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        @DisplayName("interval_end is optional - filters threads from interval_start onwards without filling gaps")
        void whenIntervalEndOmitted_thenFilterThreadsFromIntervalStart(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            var projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(), API_KEY,
                    WORKSPACE_NAME);
            Instant marker = getIntervalStart(interval);

            // Create traces with different thread_ids at different times
            Long threadCountMinus3 = (long) createTracesWithThreads(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), 2, null).size();
            Long threadCountMinus1 = (long) createTracesWithThreads(projectName,
                    subtract(marker, TIME_BUCKET_1, interval), 4, null).size();
            Long threadCountNow = (long) createTracesWithThreads(projectName, marker, 3, null).size();

            // SUT - omit interval_end
            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    // interval_end intentionally omitted to test optional behavior
                    .build();

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, Long.class, API_KEY,
                    WORKSPACE_NAME);

            // When interval_end is omitted, WITH FILL is not used, so only actual data points are returned (no nulls for gaps)
            assertThat(response.results()).hasSize(1);
            var result = response.results().getFirst();
            assertThat(result.name()).isEqualTo(ProjectMetricsDAO.NAME_THREADS);
            assertThat(result.data()).hasSize(3); // Only 3 actual data points, no filled null values

            // Verify the actual data points exist
            var dataPointTimes = result.data().stream().map(dp -> dp.time()).toList();
            assertThat(dataPointTimes).contains(
                    subtract(marker, TIME_BUCKET_3, interval),
                    subtract(marker, TIME_BUCKET_1, interval),
                    marker);

            var dataPointValues = result.data().stream().map(dp -> dp.value()).toList();
            assertThat(dataPointValues).containsExactlyInAnyOrder(threadCountMinus3, threadCountMinus1, threadCountNow);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<TraceThread, TraceThreadFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces with different thread_ids at different times with different thread counts
            List<List<Trace>> tracesPerThreadMinus3 = createTracesWithThreads(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), 5, 3);
            List<List<Trace>> tracesPerThreadMinus1 = createTracesWithThreads(projectName,
                    subtract(marker, TIME_BUCKET_1, interval), 4, null);
            List<List<Trace>> tracesPerThreadNow = createTracesWithThreads(projectName, marker, 3, null);

            var createdThread = traceResourceClient.getTraceThread(
                    tracesPerThreadMinus3.getFirst().getFirst().threadId(), projectId, API_KEY, WORKSPACE_NAME);

            // Add tags to the thread
            var update = factory.manufacturePojo(TraceThreadUpdate.class);
            traceResourceClient.updateThread(update, createdThread.threadModelId(), API_KEY, WORKSPACE_NAME, 204);

            // Add feedback scores to the thread
            var scores = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItemThread.class)
                    .stream()
                    .map(score -> (FeedbackScoreBatchItemThread) score.toBuilder()
                            .threadId(createdThread.id())
                            .projectName(projectName)
                            .build())
                    .toList();
            traceResourceClient.threadFeedbackScores(scores, API_KEY, WORKSPACE_NAME);

            // get one more time, to have actual data for lastUpdatedAt
            var updatedThread = traceResourceClient.getTraceThread(
                    tracesPerThreadMinus3.getFirst().getFirst().threadId(), projectId, API_KEY, WORKSPACE_NAME);

            Map<String, Long> minus1 = Map.of(ProjectMetricsDAO.NAME_THREADS, (long) tracesPerThreadMinus1.size());
            Map<String, Long> current = Map.of(ProjectMetricsDAO.NAME_THREADS, (long) tracesPerThreadNow.size());

            var expectedValues = Arrays.asList(Map.of(ProjectMetricsDAO.NAME_THREADS, 1L),
                    Map.of(ProjectMetricsDAO.NAME_THREADS, (long) tracesPerThreadMinus3.size() - 1), minus1,
                    current, null);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .threadFilters(List.of(getFilter.apply(updatedThread)))
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_THREADS), Long.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.threadHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            var projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(), API_KEY,
                    WORKSPACE_NAME);
            mockGetWorkspaceIdByName(WORKSPACE_NAME, WORKSPACE_ID);

            Instant marker = getIntervalStart(interval);

            // SUT
            getAndAssertEmpty(projectId, interval, marker);
        }

        private List<List<Trace>> createTracesWithThreads(String projectName, Instant marker, int threadCount,
                Integer tracesPerThread) {
            // Create traces with different thread_ids to simulate multiple threads
            List<String> threadIds = IntStream.range(0, threadCount)
                    .mapToObj(i -> RandomStringUtils.secure().nextAlphabetic(10))
                    .toList();

            List<Trace> allTraces = new ArrayList<>();

            // Create multiple traces per thread to test that threads are counted, not traces
            var tracesForThreads = IntStream.range(0, threadIds.size()).mapToObj(threadIdIdx -> {
                List<Trace> traces = IntStream
                        .range(0, tracesPerThread == null || threadIdIdx != 0 ? 2 : tracesPerThread) // 2 traces per thread except for the first thread, needed for number of messages filter
                        .mapToObj(i -> {
                            Instant traceStartTime = marker.plusSeconds((long) threadIdIdx * (i + 1));
                            return factory.manufacturePojo(Trace.class).toBuilder()
                                    .id(idGenerator.generateId(traceStartTime))
                                    .projectName(projectName)
                                    .threadId(threadIds.get(threadIdIdx))
                                    .startTime(traceStartTime)
                                    .build();
                        })
                        .toList();

                allTraces.addAll(traces);
                return traces;
            }).toList();

            traceResourceClient.batchCreateTraces(allTraces, API_KEY, WORKSPACE_NAME);

            Mono.delay(Duration.ofMillis(100)).block();

            // Close threads to ensure they are written to the trace_threads table
            traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);

            return tracesForThreads;
        }

        private void getAndAssertEmpty(UUID projectId, TimeInterval interval, Instant marker) {
            Map<String, Long> empty = singletonMap(ProjectMetricsDAO.NAME_THREADS, null);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(ProjectMetricsDAO.NAME_THREADS), Long.class, empty, empty, empty);
        }
    }

    @Nested
    @DisplayName("Thread duration")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadDurationTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            var projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(), API_KEY,
                    WORKSPACE_NAME);
            Instant marker = getIntervalStart(interval);

            // Create thread durations at different times
            List<BigDecimal> durationsMinus3 = createTracesWithThreadDuration(projectName,
                    subtract(marker, TIME_BUCKET_3, interval));
            List<BigDecimal> durationsMinus1 = createTracesWithThreadDuration(projectName,
                    subtract(marker, TIME_BUCKET_1, interval));
            List<BigDecimal> durationsCurrent = createTracesWithThreadDuration(projectName, marker);

            // SUT
            var durationMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, durationsMinus3.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, durationsMinus3.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, durationsMinus3.getLast());
            var durationMinus1 = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, durationsMinus1.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, durationsMinus1.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, durationsCurrent.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, durationsCurrent.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, durationsCurrent.getLast());

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker,
                    List.of(ProjectMetricsDAO.NAME_THREAD_DURATION_P50, ProjectMetricsDAO.NAME_THREAD_DURATION_P90,
                            ProjectMetricsDAO.NAME_THREAD_DURATION_P99),
                    BigDecimal.class, durationMinus3, durationMinus1, durationCurrent);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<TraceThread, TraceThreadFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create thread durations with specific patterns for filtering
            var tracesWithThreadDurationsMinus3 = createTraceThreads(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), 5);
            var threadForFilterId = tracesWithThreadDurationsMinus3.getLeft().getFirst();

            var filteredDurationsMinus3 = calculateQuantiles(tracesWithThreadDurationsMinus3.getRight().subList(0, 1));
            var durationsMinus3 = calculateQuantiles(tracesWithThreadDurationsMinus3.getRight().subList(1,
                    tracesWithThreadDurationsMinus3.getRight().size()));

            var durationsMinus1 = createTracesWithThreadDuration(projectName,
                    subtract(marker, TIME_BUCKET_1, interval));
            var durationsCurrent = createTracesWithThreadDuration(projectName, marker);

            var filteredDurationMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, filteredDurationsMinus3.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, filteredDurationsMinus3.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, filteredDurationsMinus3.getLast());
            var restDurationMinus3 = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, durationsMinus3.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, durationsMinus3.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, durationsMinus3.getLast());
            var durationMinus1 = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, durationsMinus1.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, durationsMinus1.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P50, durationsCurrent.getFirst(),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P90, durationsCurrent.get(1),
                    ProjectMetricsDAO.NAME_THREAD_DURATION_P99, durationsCurrent.getLast());

            var expectedValues = Arrays.asList(filteredDurationMinus3, restDurationMinus3,
                    durationMinus1, durationCurrent, null);

            var createdThread = traceResourceClient.getTraceThread(threadForFilterId, projectId, API_KEY,
                    WORKSPACE_NAME);

            // Add tags to the thread
            var update = factory.manufacturePojo(TraceThreadUpdate.class);
            traceResourceClient.updateThread(update, createdThread.threadModelId(), API_KEY, WORKSPACE_NAME, 204);

            // Add feedback scores to the thread
            var scores = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItemThread.class)
                    .stream()
                    .map(score -> (FeedbackScoreBatchItemThread) score.toBuilder()
                            .threadId(createdThread.id())
                            .projectName(projectName)
                            .build())
                    .toList();
            traceResourceClient.threadFeedbackScores(scores, API_KEY, WORKSPACE_NAME);

            // get one more time, to have actual data for lastUpdatedAt
            var updatedThread = traceResourceClient.getTraceThread(threadForFilterId, projectId, API_KEY,
                    WORKSPACE_NAME);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .threadFilters(List.of(getFilter.apply(updatedThread)))
                    .build(), marker,
                    List.of(ProjectMetricsDAO.NAME_THREAD_DURATION_P50, ProjectMetricsDAO.NAME_THREAD_DURATION_P90,
                            ProjectMetricsDAO.NAME_THREAD_DURATION_P99),
                    BigDecimal.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.threadHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();
            var projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(), API_KEY,
                    WORKSPACE_NAME);
            mockGetWorkspaceIdByName(WORKSPACE_NAME, WORKSPACE_ID);

            Instant marker = getIntervalStart(interval);

            // SUT
            getAndAssertEmpty(projectId, interval, marker);
        }

        private List<BigDecimal> createTracesWithThreadDuration(String projectName, Instant marker) {
            var threadDurations = createTraceThreads(projectName, marker, null).getRight();

            return calculateQuantiles(threadDurations);
        }

        private List<BigDecimal> calculateQuantiles(List<Double> threadDurations) {
            return StatsUtils.calculateQuantiles(threadDurations,
                    List.of(0.50, 0.90, 0.99));
        }

        private Pair<List<String>, List<Double>> createTraceThreads(String projectName, Instant marker,
                Integer tracesCount) {
            // Create different threads with different durations
            List<String> threadIds = IntStream.range(0, 3)
                    .mapToObj(i -> RandomStringUtils.secure().nextAlphabetic(10))
                    .toList();

            List<Double> threadDurations = new ArrayList<>();
            List<Trace> allTraces = new ArrayList<>();

            // Create traces with specific durations for each thread
            for (int i = 0; i < threadIds.size(); i++) {
                String threadId = threadIds.get(i);

                // Different thread duration patterns: increasing durations
                int baseDurationMs = (i + 1) * 300 + RANDOM.nextInt(200);
                int numTraces = 2 + i; // Variable number of traces per thread

                Instant threadStartTime = marker.plusMillis(i * 100L); // Space threads apart
                Instant threadEndTime = threadStartTime.plusMillis(baseDurationMs);

                // Create multiple traces within the thread
                List<Trace> traces = IntStream.range(0, tracesCount != null && i == 0 ? tracesCount : numTraces) // needed for number of messages filter
                        .mapToObj(j -> {
                            // First trace starts at thread start time
                            Instant traceStart = j == 0 ? threadStartTime : threadStartTime.plusMillis(j * 50L);
                            // Last trace ends at thread end time, others have shorter durations
                            Instant traceEnd = j == (numTraces - 1) ? threadEndTime : traceStart.plusMillis(100);

                            return factory.manufacturePojo(Trace.class).toBuilder()
                                    .id(idGenerator.generateId(traceStart))
                                    .projectName(projectName)
                                    .threadId(threadId)
                                    .startTime(traceStart)
                                    .endTime(traceEnd)
                                    .build();
                        })
                        .toList();

                allTraces.addAll(traces);

                // Calculate actual thread duration: (last trace end time) - (first trace start time)
                threadDurations.add(threadStartTime.until(threadEndTime, ChronoUnit.MICROS) / 1000.0);
            }

            traceResourceClient.batchCreateTraces(allTraces, API_KEY, WORKSPACE_NAME);

            Mono.delay(Duration.ofMillis(100)).block(); // wait for threads to be indexed

            // Close threads to ensure they are written to the trace_threads table
            traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);

            return Pair.of(threadIds, threadDurations);
        }

        private void getAndAssertEmpty(UUID projectId, TimeInterval interval, Instant marker) {
            Map<String, BigDecimal> empty = new HashMap<>();
            empty.put(ProjectMetricsDAO.NAME_THREAD_DURATION_P50, null);
            empty.put(ProjectMetricsDAO.NAME_THREAD_DURATION_P90, null);
            empty.put(ProjectMetricsDAO.NAME_THREAD_DURATION_P99, null);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker,
                    List.of(ProjectMetricsDAO.NAME_THREAD_DURATION_P50, ProjectMetricsDAO.NAME_THREAD_DURATION_P90,
                            ProjectMetricsDAO.NAME_THREAD_DURATION_P99),
                    BigDecimal.class, empty, empty, empty);
        }
    }

    private <T extends Number> void getMetricsAndAssert(
            UUID projectId, ProjectMetricRequest request, Instant marker, List<String> names, Class<T> aClass,
            Map<String, T> minus3, Map<String, T> minus1, Map<String, T> current) {
        var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, aClass, API_KEY,
                WORKSPACE_NAME);

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
                            dataMinus3 == null ? null : dataMinus3.get(name),
                            null,
                            dataMinus1 == null ? null : dataMinus1.get(name),
                            dataNow == null ? null : dataNow.get(name));

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

    private static BigDecimal calcAverage(List<BigDecimal> scores) {
        BigDecimal sum = scores.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(scores.size()), RoundingMode.UP);
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

    private List<FeedbackScore> feedbackScoresMapper(List<FeedbackScoreBatchItem> items) {
        return items.stream()
                .map(item -> factory.manufacturePojo(FeedbackScore.class).toBuilder()
                        .name(item.name())
                        .value(item.value())
                        .reason(item.reason())
                        .categoryName(item.categoryName())
                        .source(item.source())
                        .build())
                .toList();
    }

    static Stream<Arguments> happyPathWithFilterArguments() {
        return Stream.of(Arguments.of(
                (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                        .field(TraceField.ID)
                        .operator(EQUAL)
                        .value(trace.id().toString())
                        .build(),
                Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.NAME)
                                .operator(EQUAL)
                                .value(trace.name())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.NAME)
                                .operator(NOT_EQUAL)
                                .value(trace.name())
                                .build(),
                        Arrays.asList(1, 3, 4)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.START_TIME)
                                .operator(EQUAL)
                                .value(trace.startTime().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.END_TIME)
                                .operator(EQUAL)
                                .value(trace.endTime().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.INPUT)
                                .operator(EQUAL)
                                .value(trace.input().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.OUTPUT)
                                .operator(EQUAL)
                                .value(trace.output().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.CUSTOM)
                                .operator(EQUAL)
                                .value(trace.input().propertyStream().toList().getFirst().getValue().asText())
                                .key("input." + trace.input().propertyStream().toList().getFirst().getKey())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.CUSTOM)
                                .operator(EQUAL)
                                .value(trace.output().propertyStream().toList().getFirst().getValue().asText())
                                .key("output." + trace.output().propertyStream().toList().getFirst().getKey())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.METADATA)
                                .operator(EQUAL)
                                .value(trace.metadata().propertyStream().toList().getFirst().getValue().asText())
                                .key(trace.metadata().propertyStream().toList().getFirst().getKey())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.TAGS)
                                .operator(CONTAINS)
                                .value(trace.tags().stream().findFirst().orElse(""))
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.DURATION)
                                .operator(GREATER_THAN)
                                .value(String.valueOf(Duration.of(3, ChronoUnit.HOURS).toMillis()))
                                .build(),
                        Arrays.asList(2, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.THREAD_ID)
                                .operator(EQUAL)
                                .value(trace.threadId())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.VISIBILITY_MODE)
                                .operator(EQUAL)
                                .value(VisibilityMode.DEFAULT.getValue())
                                .build(),
                        Arrays.asList(2, 3, 4)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.ERROR_INFO)
                                .operator(IS_NOT_EMPTY)
                                .value("")
                                .build(),
                        Arrays.asList(2, 3, 4)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.GUARDRAILS)
                                .operator(EQUAL)
                                .value(GuardrailResult.PASSED.getResult())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.FEEDBACK_SCORES)
                                .operator(EQUAL)
                                .key(trace.feedbackScores().getFirst().name())
                                .value(trace.feedbackScores().getFirst().value().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.FEEDBACK_SCORES)
                                .operator(IS_EMPTY)
                                .key(trace.feedbackScores().getFirst().name())
                                .value("")
                                .build(),
                        Arrays.asList(1, 3, 4)));
    }

    static Stream<Arguments> threadHappyPathWithFilterArguments() {
        return Stream.of(Arguments.of(
                (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                        .field(TraceThreadField.ID)
                        .operator(EQUAL)
                        .value(thread.id())
                        .build(),
                Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.ID)
                                .operator(NOT_EQUAL)
                                .value(thread.id())
                                .build(),
                        Arrays.asList(1, 2, 3)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.FIRST_MESSAGE)
                                .operator(EQUAL)
                                .value(thread.firstMessage().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.LAST_MESSAGE)
                                .operator(EQUAL)
                                .value(thread.lastMessage().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.NUMBER_OF_MESSAGES)
                                .operator(EQUAL)
                                .value(String.valueOf(thread.numberOfMessages()))
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.DURATION)
                                .operator(EQUAL)
                                .value(BigDecimal.valueOf(thread.duration()).toPlainString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.CREATED_AT)
                                .operator(EQUAL)
                                .value(thread.createdAt().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.LAST_UPDATED_AT)
                                .operator(EQUAL)
                                .value(thread.lastUpdatedAt().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.START_TIME)
                                .operator(EQUAL)
                                .value(thread.startTime().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.END_TIME)
                                .operator(EQUAL)
                                .value(thread.endTime().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.STATUS)
                                .operator(EQUAL)
                                .value(ACTIVE.getValue())
                                .build(),
                        Arrays.asList(4, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.TAGS)
                                .operator(CONTAINS)
                                .value(thread.tags().stream().findFirst().orElse(""))
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.FEEDBACK_SCORES)
                                .operator(EQUAL)
                                .key(thread.feedbackScores().getFirst().name())
                                .value(thread.feedbackScores().getFirst().value().toString())
                                .build(),
                        Arrays.asList(0, 4, 4)),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.FEEDBACK_SCORES)
                                .operator(IS_EMPTY)
                                .key(thread.feedbackScores().getFirst().name())
                                .value("")
                                .build(),
                        Arrays.asList(1, 2, 3)));
    }

    static Stream<Arguments> spanHappyPathWithFilterArguments() {
        return Stream.of(Arguments.of(
                (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                        .field(SpanField.ID)
                        .operator(EQUAL)
                        .value(span.id().toString())
                        .build(),
                Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.NAME)
                                .operator(EQUAL)
                                .value(span.name())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.NAME)
                                .operator(NOT_EQUAL)
                                .value(span.name())
                                .build(),
                        Arrays.asList(1, 3, 4)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.START_TIME)
                                .operator(EQUAL)
                                .value(span.startTime().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.END_TIME)
                                .operator(EQUAL)
                                .value(span.endTime().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.INPUT)
                                .operator(EQUAL)
                                .value(span.input().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.OUTPUT)
                                .operator(EQUAL)
                                .value(span.output().toString())
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.FEEDBACK_SCORES)
                                .operator(EQUAL)
                                .key("score1")
                                .value("1.0")
                                .build(),
                        Arrays.asList(0, 5, 5)),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.FEEDBACK_SCORES)
                                .operator(IS_EMPTY)
                                .key("score2")
                                .value("")
                                .build(),
                        Arrays.asList(1, 3, 4)));
    }

    @Nested
    @DisplayName("Span count")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanCountTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // create spans in several buckets
            var expected = List.of(3, 2, 1);
            createSpansForCount(projectName, subtract(marker, TIME_BUCKET_3, interval), expected.getFirst());
            createSpansForCount(projectName, subtract(marker, TIME_BUCKET_1, interval), expected.get(1));
            createSpansForCount(projectName, marker, expected.getLast());

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of("spans"), Integer.class,
                    Map.of("spans", expected.getFirst()),
                    Map.of("spans", expected.get(1)),
                    Map.of("spans", expected.getLast()));
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Span, SpanFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // create spans in several buckets
            var expected = List.of(3, 2, 1);
            var spans = createSpansForCount(projectName, subtract(marker, TIME_BUCKET_3, interval),
                    expected.getFirst());
            // allow one empty hour
            createSpansForCount(projectName, subtract(marker, TIME_BUCKET_1, interval), expected.get(1));
            createSpansForCount(projectName, marker, expected.getLast());

            // create feedback scores for the first bucket spans
            List<FeedbackScoreBatchItem> scores = List
                    .of(factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                            factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .id(spans.getFirst().id())
                                    .name("score2")
                                    .value(BigDecimal.ONE)
                                    .projectName(projectName)
                                    .build());

            spanResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            var expectedValues = Arrays.asList(1, 2, 3, 2, 1, null);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .spanFilters(List.of(getFilter.apply(spans.getFirst())))
                    .build(), marker, List.of("spans"), Integer.class,
                    singletonMap("spans", expectedValues.get(expectedIndexes.get(0))),
                    singletonMap("spans", expectedValues.get(expectedIndexes.get(1))),
                    singletonMap("spans", expectedValues.get(expectedIndexes.get(2))));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.spanHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            Map<String, Integer> emptySpans = new HashMap<>() {
                {
                    put("spans", null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of("spans"), Integer.class, emptySpans, emptySpans, emptySpans);
        }

        private List<Span> createSpansForCount(String projectName, Instant marker, int count) {
            List<Span> spans = IntStream.range(0, count)
                    .mapToObj(i -> {
                        Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Span.class).toBuilder()
                                .id(idGenerator.generateId(spanStartTime))
                                .projectName(projectName)
                                .startTime(spanStartTime)
                                .build();
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

            return spans;
        }
    }

    @Nested
    @DisplayName("Span duration")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanDurationTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            List<BigDecimal> durationsMinus3 = createSpansWithDuration(projectName,
                    subtract(marker, TIME_BUCKET_3, interval));
            List<BigDecimal> durationsMinus1 = createSpansWithDuration(projectName,
                    subtract(marker, TIME_BUCKET_1, interval));
            List<BigDecimal> durationsCurrent = createSpansWithDuration(projectName, marker);

            var durationMinus3 = Map.of(
                    "duration.p50", durationsMinus3.get(0),
                    "duration.p90", durationsMinus3.get(1),
                    "duration.p99", durationsMinus3.getLast());
            var durationMinus1 = Map.of(
                    "duration.p50", durationsMinus1.get(0),
                    "duration.p90", durationsMinus1.get(1),
                    "duration.p99", durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    "duration.p50", durationsCurrent.get(0),
                    "duration.p90", durationsCurrent.get(1),
                    "duration.p99", durationsCurrent.getLast());

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.SPAN_DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .build(),
                    marker,
                    List.of("duration.p50", "duration.p90", "duration.p99"),
                    BigDecimal.class,
                    durationMinus3,
                    durationMinus1,
                    durationCurrent);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Span, SpanFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var startTime = subtract(marker, TIME_BUCKET_3, interval).plusMillis(RANDOM.nextInt(50));
            var endTime = subtract(marker, TIME_BUCKET_3, interval).plus(4, ChronoUnit.HOURS);

            List<Span> spans = createSpansWithDurationInternal(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), 5, startTime, endTime);

            List<BigDecimal> durationsMinus1 = createSpansWithDuration(projectName,
                    subtract(marker, TIME_BUCKET_1, interval));
            List<BigDecimal> durationsCurrent = createSpansWithDuration(projectName, marker);

            // create feedback scores for the first span
            List<FeedbackScoreBatchItem> scores = List.of(
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score2")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build());

            spanResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            List<BigDecimal> durationsFirstSpan = calculateQuantiles(List.of(spans.getFirst()));
            List<BigDecimal> durationsOtherSpans = calculateQuantiles(spans.subList(1, spans.size()));
            List<BigDecimal> durationsAllSpans = calculateQuantiles(spans);

            var durationFirstSpan = Map.of(
                    "duration.p50", durationsFirstSpan.get(0),
                    "duration.p90", durationsFirstSpan.get(1),
                    "duration.p99", durationsFirstSpan.getLast());
            var durationOtherSpans = Map.of(
                    "duration.p50", durationsOtherSpans.get(0),
                    "duration.p90", durationsOtherSpans.get(1),
                    "duration.p99", durationsOtherSpans.getLast());
            var durationAllSpans = Map.of(
                    "duration.p50", durationsAllSpans.get(0),
                    "duration.p90", durationsAllSpans.get(1),
                    "duration.p99", durationsAllSpans.getLast());
            var durationMinus1 = Map.of(
                    "duration.p50", durationsMinus1.get(0),
                    "duration.p90", durationsMinus1.get(1),
                    "duration.p99", durationsMinus1.getLast());
            var durationCurrent = Map.of(
                    "duration.p50", durationsCurrent.get(0),
                    "duration.p90", durationsCurrent.get(1),
                    "duration.p99", durationsCurrent.getLast());

            var expectedValues = Arrays.asList(
                    durationFirstSpan, // 0: first span only
                    durationOtherSpans, // 1: other spans (not first)
                    durationAllSpans, // 2: all spans in TIME_BUCKET_3
                    durationMinus1, // 3: TIME_BUCKET_1
                    durationCurrent, // 4: marker
                    null); // 5: empty/no match

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.SPAN_DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .spanFilters(List.of(getFilter.apply(spans.getFirst())))
                            .build(),
                    marker,
                    List.of("duration.p50", "duration.p90", "duration.p99"),
                    BigDecimal.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.spanHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put("duration.p50", null);
                    put("duration.p90", null);
                    put("duration.p99", null);
                }
            };

            getMetricsAndAssert(
                    projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.SPAN_DURATION)
                            .interval(interval)
                            .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                            .intervalEnd(Instant.now())
                            .build(),
                    marker,
                    List.of("duration.p50", "duration.p90", "duration.p99"),
                    BigDecimal.class,
                    empty,
                    empty,
                    empty);
        }

        private List<BigDecimal> createSpansWithDuration(String projectName, Instant marker) {
            List<Span> spans = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        Instant spanStartTime = marker.plusMillis(RANDOM.nextInt(50, 100));
                        Instant spanEndTime = marker.plusMillis(RANDOM.nextInt(100, 1000));

                        return factory.manufacturePojo(Span.class).toBuilder()
                                .id(idGenerator.generateId(spanStartTime))
                                .projectName(projectName)
                                .startTime(spanStartTime)
                                .endTime(spanEndTime)
                                .build();
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

            return StatsUtils.calculateQuantiles(
                    spans.stream()
                            .filter(span -> span.endTime() != null)
                            .map(span -> span.startTime().until(span.endTime(), ChronoUnit.MICROS))
                            .map(duration -> duration / 1_000.0)
                            .toList(),
                    List.of(0.50, 0.90, 0.99));
        }

        private List<Span> createSpansWithDurationInternal(String projectName, Instant marker, int count,
                Instant startTime, Instant endTime) {
            List<Span> spans = IntStream.range(0, count)
                    .mapToObj(i -> {
                        Instant spanStartTime = startTime != null
                                ? startTime
                                : marker.plusMillis(RANDOM.nextInt(50, 100));
                        Instant spanEndTime = endTime != null ? endTime : marker.plusMillis(RANDOM.nextInt(100, 1000));

                        return factory.manufacturePojo(Span.class).toBuilder()
                                .id(idGenerator.generateId(spanStartTime))
                                .projectName(projectName)
                                .startTime(spanStartTime)
                                .endTime(spanEndTime)
                                .build();
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
            return spans;
        }

        private List<BigDecimal> calculateQuantiles(List<Span> spans) {
            return StatsUtils.calculateQuantiles(
                    spans.stream()
                            .filter(entity -> entity.endTime() != null)
                            .map(entity -> entity.startTime().until(entity.endTime(), ChronoUnit.MICROS))
                            .map(duration -> duration / 1_000.0)
                            .toList(),
                    List.of(0.50, 0.90, 0.99));
        }
    }

    @Nested
    @DisplayName("Span token usage")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanTokenUsageTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var usageMinus3 = createSpansWithTokenUsage(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            var usageMinus1 = createSpansWithTokenUsage(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var usage = createSpansWithTokenUsage(projectName, marker, names);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, names, Long.class, usageMinus3, usageMinus1, usage);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Span, SpanFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var spans = createSpansWithTokenUsageInternal(projectName, subtract(marker, TIME_BUCKET_3, interval), 5,
                    names);
            var usageMinus1 = createSpansWithTokenUsage(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var usage = createSpansWithTokenUsage(projectName, marker, names);

            // create feedback scores for the first span
            List<FeedbackScoreBatchItem> scores = List.of(
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score2")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build());

            spanResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

            var usageTotalMinus3 = aggregateTokenUsage(spans, names);
            var usageFirstSpan = aggregateTokenUsage(List.of(spans.getFirst()), names);
            var usageOtherSpans = aggregateTokenUsage(spans.subList(1, spans.size()), names);

            var expectedValues = Arrays.asList(
                    usageFirstSpan, // 0: first span only
                    usageOtherSpans, // 1: other spans (not first)
                    usageTotalMinus3, // 2: all spans in TIME_BUCKET_3
                    usageMinus1, // 3: TIME_BUCKET_1
                    usage, // 4: marker
                    null); // 5: empty/no match

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .spanFilters(List.of(getFilter.apply(spans.getFirst())))
                    .build(), marker, names, Long.class,
                    expectedValues.get(expectedIndexes.get(0)),
                    expectedValues.get(expectedIndexes.get(1)),
                    expectedValues.get(expectedIndexes.get(2)));
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.spanHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
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
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            createSpansWithTokenUsage(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            createSpansWithTokenUsage(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            createSpansWithTokenUsage(projectName, marker, names);

            getAndAssertEmpty(projectId, interval, marker);
        }

        private Map<String, Long> createSpansWithTokenUsage(String projectName, Instant marker,
                List<String> usageNames) {
            List<Span> spans = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Span.class).toBuilder()
                                .id(idGenerator.generateId(spanStartTime))
                                .projectName(projectName)
                                .startTime(spanStartTime)
                                .usage(usageNames == null
                                        ? null
                                        : usageNames.stream().collect(
                                                Collectors.toMap(name -> name, n -> Math.abs(RANDOM.nextInt()))))
                                .build();
                    })
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

        private List<Span> createSpansWithTokenUsageInternal(String projectName, Instant marker, int count,
                List<String> usageNames) {
            List<Span> spans = IntStream.range(0, count)
                    .mapToObj(i -> {
                        Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Span.class).toBuilder()
                                .id(idGenerator.generateId(spanStartTime))
                                .projectName(projectName)
                                .usage(usageNames == null
                                        ? null
                                        : usageNames.stream()
                                                .collect(Collectors.toMap(
                                                        name -> name,
                                                        name -> RANDOM.nextInt(10, 100))))
                                .startTime(spanStartTime)
                                .build();
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
            return spans;
        }

        private Map<String, Long> aggregateTokenUsage(List<Span> spans, List<String> usageNames) {
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
                    .metricType(MetricType.SPAN_TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(""), Long.class, empty, empty, empty);
        }
    }

    @Nested
    @DisplayName("Span feedback scores")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanFeedbackScoresTest {

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var scoresMinus3 = createSpanFeedbackScores(projectName, subtract(marker, TIME_BUCKET_3, interval), names);
            var scoresMinus1 = createSpanFeedbackScores(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var scores = createSpanFeedbackScores(projectName, marker, names);

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, names, BigDecimal.class, scoresMinus3, scoresMinus1, scores);
        }

        @ParameterizedTest
        @MethodSource
        void happyPathWithFilter(Function<Span, SpanFilter> getFilter, List<Integer> expectedIndexes) {
            // setup
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var scoresForFilterSpan = createSpanFeedbackScoresInternalWithSpans(projectName,
                    subtract(marker, TIME_BUCKET_3, interval), names, 1);
            // Offset by 1 second to avoid overlapping start times with scoresForFilterSpan
            var scoresMinus3 = createSpanFeedbackScoresInternalWithSpans(projectName,
                    subtract(marker, TIME_BUCKET_3, interval).plusSeconds(1), names, 5);

            var scoresMinus1 = createSpanFeedbackScores(projectName, subtract(marker, TIME_BUCKET_1, interval), names);
            var scores = createSpanFeedbackScores(projectName, marker, names);

            // Add score1 and score2 for FEEDBACK_SCORES filter tests
            List<FeedbackScoreBatchItem> additionalScores = List.of(
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(scoresForFilterSpan.getLeft().getFirst().id())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(scoresForFilterSpan.getLeft().getFirst().id())
                            .name("score2")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build());

            spanResourceClient.feedbackScores(additionalScores, API_KEY, WORKSPACE_NAME);

            // Calculate aggregations - scoresForFilter includes the additional score1/score2
            var allScoresForFilterSpan = Stream.concat(scoresForFilterSpan.getRight().stream(),
                    additionalScores.stream()).toList();
            var scoresForFilter = aggregateSpanFeedbackScores(allScoresForFilterSpan);
            var scoresOnlyMinus3 = aggregateSpanFeedbackScores(scoresMinus3.getRight());
            var allScoresFilterMinus3 = aggregateSpanFeedbackScores(
                    Stream.concat(allScoresForFilterSpan.stream(), scoresMinus3.getRight().stream()).toList());

            boolean allEmpty = expectedIndexes.stream().allMatch(i -> i == 5);

            var filter = getFilter.apply(scoresForFilterSpan.getLeft().getFirst());
            // Extended names list for when filter matches filter span (includes score1, score2)
            List<String> extendedNames = Stream.concat(names.stream(), Stream.of("score1", "score2")).toList();

            // Determine which score names to expect based on filter result
            // Index 0 = filter span (has score1/score2), Index 1 = other spans (no score1/score2)
            List<String> expectedNames;
            if (allEmpty) {
                expectedNames = List.of("");
            } else if (expectedIndexes.get(0) == 0) {
                // Filter matches filter span which has additional scores
                expectedNames = extendedNames;
            } else if (expectedIndexes.get(0) == 1) {
                // Filter matches only other spans (not filter span) - e.g., IS_EMPTY score2
                expectedNames = names;
            } else {
                // Filter matches all spans - allScoresFilterMinus3 includes additional scores
                expectedNames = extendedNames;
            }

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .spanFilters(List.of(filter))
                    .build(), marker, expectedNames, BigDecimal.class,
                    expectedIndexes.get(0) == 0
                            ? scoresForFilter
                            : expectedIndexes.get(0) == 1 ? scoresOnlyMinus3 : allScoresFilterMinus3,
                    expectedIndexes.get(1) == 3 ? scoresMinus1 : new HashMap<>() {
                        {
                            put("", null);
                        }
                    },
                    expectedIndexes.get(2) == 4 ? scores : new HashMap<>() {
                        {
                            put("", null);
                        }
                    });
        }

        Stream<Arguments> happyPathWithFilter() {
            return ProjectMetricsResourceTest.spanHappyPathWithFilterArguments();
        }

        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void emptyData(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            Map<String, BigDecimal> empty = new HashMap<>() {
                {
                    put("", null);
                }
            };

            getMetricsAndAssert(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, TIME_BUCKET_4, interval))
                    .intervalEnd(Instant.now())
                    .build(), marker, List.of(""), BigDecimal.class, empty, empty, empty);
        }

        private Map<String, BigDecimal> createSpanFeedbackScores(
                String projectName, Instant marker, List<String> scoreNames) {
            return aggregateSpanFeedbackScores(createSpanFeedbackScoresInternal(projectName, marker, scoreNames, 5));
        }

        private List<FeedbackScoreBatchItem> createSpanFeedbackScoresInternal(
                String projectName, Instant marker, List<String> scoreNames, int spansCount) {
            List<Span> spans = IntStream.range(0, spansCount)
                    .mapToObj(i -> {
                        Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Span.class).toBuilder()
                                .id(idGenerator.generateId(spanStartTime))
                                .projectName(projectName)
                                .startTime(spanStartTime)
                                .build();
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

            // create feedback scores for all spans
            List<FeedbackScoreBatchItem> allScores = spans.stream()
                    .flatMap(span -> scoreNames.stream()
                            .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(projectName)
                                    .id(span.id())
                                    .build()))
                    .collect(Collectors.toList());

            spanResourceClient.feedbackScores(allScores, API_KEY, WORKSPACE_NAME);

            return allScores;
        }

        private Pair<List<Span>, List<FeedbackScoreBatchItem>> createSpanFeedbackScoresInternalWithSpans(
                String projectName, Instant marker, List<String> scoreNames, int spansCount) {
            List<Span> spans = IntStream.range(0, spansCount)
                    .mapToObj(i -> {
                        Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                        return factory.manufacturePojo(Span.class).toBuilder()
                                .projectName(projectName)
                                .startTime(spanStartTime)
                                .id(idGenerator.generateId(spanStartTime))
                                .build();
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

            // create feedback scores for all spans
            List<FeedbackScoreBatchItem> allScores = spans.stream()
                    .flatMap(span -> scoreNames.stream()
                            .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(projectName)
                                    .id(span.id())
                                    .build()))
                    .collect(Collectors.toList());

            spanResourceClient.feedbackScores(allScores, API_KEY, WORKSPACE_NAME);

            return Pair.of(spans, allScores);
        }

        private Map<String, BigDecimal> aggregateSpanFeedbackScores(List<FeedbackScoreBatchItem> scores) {
            return scores.stream()
                    .collect(Collectors.groupingBy(FeedbackScoreItem::name))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> calcAverage(e.getValue().stream().map(FeedbackScoreItem::value)
                                    .toList())));
        }
    }
}
