package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.ProjectMetricsService;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.auth.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project Metrics Resource Test")
class ProjectMetricsResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/private/projects/%s/metrics";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphabetic(10);

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync clickHouseTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, TransactionTemplateAsync clickHouseTemplate) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.clickHouseTemplate = clickHouseTemplate;

        ClientSupportUtils.config(client);

        mockTargetWorkspace();
    }

    private static void mockTargetWorkspace() {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
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

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(API_KEY, true),
                    arguments(fakeApikey, false),
                    arguments("", false));
        }

        @BeforeEach
        void setUp() {

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(fakeApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized()));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(""))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get project metrics: when api key is present, then return proper response")
        void getProjectMetrics__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean isAuthorized) {
            mockTargetWorkspace();

            var projectId = UUID.randomUUID();

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

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse.readEntity(ProjectMetricResponse.class);
                    assertThat(actualEntity.projectId()).isEqualTo(projectId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
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

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(sessionToken, true, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, false, UUID.randomUUID().toString()));
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
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get project metrics: when session token is present, then return proper response")
        void getProjectMetrics__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {
            mockTargetWorkspace();

            var projectId = UUID.randomUUID();

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

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse.readEntity(ProjectMetricResponse.class);
                    assertThat(actualEntity.projectId()).isEqualTo(projectId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
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

            Instant marker = Instant.now().truncatedTo(interval == TimeInterval.HOURLY ? ChronoUnit.HOURS :
                    ChronoUnit.DAYS);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // create traces in several buckets
            createTraces(projectName, subtract(marker, 3, interval), 3);
            createTraces(projectName, subtract(marker, 1, interval), 2); // allow one empty hour
            createTraces(projectName, marker, 1);

            // SUT
            var response = getProjectMetrics(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 4, interval))
                    .intervalEnd(Instant.now())
                    .build());

            // assertions
            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.TRACE_COUNT);
            assertThat(response.interval()).isEqualTo(interval);
            assertThat(response.results()).hasSize(1);

            assertThat(response.results().getFirst().data()).hasSize(5);
            var expectedTraceCounts = List.of(0, 3, 0, 2, 1);
            assertThat(response.results().getLast().data()).isEqualTo(IntStream.range(0, 5)
                    .mapToObj(i -> DataPoint.builder()
                            .time(subtract(marker, 4 - i, interval))
                            .value(expectedTraceCounts.get(i)).build())
                    .toList());
        }

        @ParameterizedTest
        @MethodSource
        void invalidParameters(ProjectMetricRequest request, String expectedErr) {
            // setup
            mockTargetWorkspace();

            // SUT
            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, UUID.randomUUID()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

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
                    arguments(named("start later than end", validReq.toBuilder()
                            .intervalEnd(now.minus(2, ChronoUnit.HOURS))
                            .build()), ProjectMetricsService.ERR_START_BEFORE_END),
                    arguments(named("start equal to end", validReq.toBuilder()
                            .intervalStart(now)
                            .intervalEnd(now)
                            .build()), ProjectMetricsService.ERR_START_BEFORE_END),
                    arguments(named("not supported metric", validReq.toBuilder()
                            .metricType(MetricType.TOKEN_USAGE)
                            .build()), ProjectMetricsService.ERR_PROJECT_METRIC_NOT_SUPPORTED.formatted(
                                    MetricType.TOKEN_USAGE)));
        }

        private void createTraces(String projectName, Instant marker, int count) {
            List<Trace> traces = IntStream.range(0, count)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(marker.plus(i, ChronoUnit.SECONDS))
                            .build()).toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        }
    }

    @Nested
    @DisplayName("Feedback scores")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Disabled
    class FeedbackScoresTest {
        @ParameterizedTest
        @EnumSource(TimeInterval.class)
        void happyPath(TimeInterval interval) {
            // setup
            mockTargetWorkspace();

            Instant marker = Instant.now().truncatedTo(ChronoUnit.HOURS);
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var scoresMinus3 = createFeedbackScores(projectName, subtract(marker, 3, interval), names);
            var scoresMinus1 = createFeedbackScores(projectName, subtract(marker, 1, interval), names);
            var scores = createFeedbackScores(projectName, marker, names);

            // SUT
            var response = getProjectMetrics(projectId, ProjectMetricRequest.builder()
                    .metricType(MetricType.FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 4, interval))
                    .intervalEnd(Instant.now())
                    .build());

            // assertions
            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.FEEDBACK_SCORES);
            assertThat(response.interval()).isEqualTo(interval);
            assertThat(response.results()).hasSize(names.size());

            List<ProjectMetricResponse.Results> expected = names.stream()
                    .map(name -> {
                        var expectedFeedbackScores = List.of(
                                BigDecimal.ZERO,
                                scoresMinus3.get(name),
                                BigDecimal.ZERO,
                                scoresMinus1.get(name),
                                scores.get(name));

                        return ProjectMetricResponse.Results.builder()
                                .name(name)
                                .data(IntStream.range(0, expectedFeedbackScores.size())
                                        .mapToObj(i -> DataPoint.builder()
                                                .time(subtract(marker, 4 - i, interval))
                                                .value(expectedFeedbackScores.get(i)).build())
                                        .toList()).build();
                    }).toList();

            assertThat(response.results()).hasSize(expected.size());

            for (ProjectMetricResponse.Results expectedRes : expected) {
                var actual = response.results().stream()
                        .filter(actualRes -> actualRes.name().equals(expectedRes.name())).findFirst();
                assertThat(actual).isPresent();

                for (int i = 0; i < expectedRes.data().size(); i++) {
                    assertThat(actual.get().data().get(i).time()).isEqualTo(expectedRes.data().get(i).time());
                    if (Objects.equals(expectedRes.data().get(i).value(), BigDecimal.ZERO)) {
                        assertThat(actual.get().data().get(i).value()).isEqualTo(0);
                    } else {
                        assertThat((BigDecimal) actual.get().data().get(i).value())
                                .isEqualByComparingTo(new BigDecimal(expectedRes.data().get(i).value()));
                    }
                }
            }
        }

        private Map<String, BigDecimal> createFeedbackScores(String projectName, Instant marker, List<String> scoreNames) {
            return IntStream.range(0, 5)
                    .mapToObj(i -> {
                        // create a trace
                        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                                .name(projectName)
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

                        traceResourceClient.feedbackScore(scores, API_KEY, WORKSPACE_NAME);
                        setCreatedAt(trace.id(), marker.plus(i, ChronoUnit.SECONDS));

                        return scores;
                    }).flatMap(List::stream)
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::name))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> calcAverage(e.getValue().stream().map(FeedbackScoreBatchItem::value)
                                    .toList())));
        }

        private void setCreatedAt(UUID traceId, Instant lastUpdated) {
            String updateLastUpdated =
                    """
                            ALTER TABLE feedback_scores
                            UPDATE created_at = parseDateTime64BestEffort(:last_updated, 9)
                            WHERE entity_id=:trace_id;
                            """;
            clickHouseTemplate.nonTransaction(connection -> {
                var statement = connection.createStatement(updateLastUpdated)
                        .bind("trace_id", traceId)
                        .bind("last_updated", lastUpdated.toString());
                return Mono.from(statement.execute());
            }).block();
        }

        private static BigDecimal calcAverage(List<BigDecimal> scores) {
            BigDecimal sum = scores.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(new BigDecimal(scores.size()), RoundingMode.UP);
        }
    }

    private ProjectMetricResponse getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();

            return response.readEntity(ProjectMetricResponse.class);
        }
    }

    private static Instant subtract(Instant instant, int count, TimeInterval interval) {
        if (interval == TimeInterval.WEEKLY) {
            count *= 7;
        }

        return instant.minus(count, interval == TimeInterval.HOURLY ? ChronoUnit.HOURS : ChronoUnit.DAYS);
    }
}
