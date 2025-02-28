package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TraceAssertions;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.com.google.common.collect.Lists;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertTraceComment;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertUpdatedComment;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.StatsUtils.getProjectTraceStatItems;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.domain.TraceService.PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Traces Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracesResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/traces";
    private static final String URL_TEMPLATE_SPANS = "%s/v1/private/spans";
    private static final String[] IGNORED_FIELDS_TRACES = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy", "totalEstimatedCost", "duration",
            "comments", "threadId"};
    private static final String[] IGNORED_FIELDS_SPANS = SpanResourceClient.IGNORED_FIELDS;
    private static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension APP;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();
    private final FilterQueryBuilder filterQueryBuilder = new FilterQueryBuilder();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID getProjectId(String projectName, String workspaceName, String apiKey) {
        return projectResourceClient.getByName(projectName, apiKey, workspaceName).id();
    }

    private UUID createProject(String projectName, String workspaceName, String apiKey) {
        return projectResourceClient.createProject(projectName, apiKey, workspaceName);
    }

    @Nested
    @DisplayName("Api Key Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApiKey {

        private final String fakeApikey = UUID.randomUUID().toString();
        private final String okApikey = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(okApikey, true, null),
                    arguments(fakeApikey, false, UNAUTHORIZED_RESPONSE),
                    arguments("", false, NO_API_KEY_RESPONSE));
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
        @MethodSource("credentials")
        @DisplayName("create trace, when api key is present, then return proper response")
        void create__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(trace))) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update trace, when api key is present, then return proper response")
        void update__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            var id = create(trace, okApikey, workspaceName);

            var update = factory.manufacturePojo(TraceUpdate.class)
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(update))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, errorMessage, HttpStatus.SC_NO_CONTENT);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete trace, when api key is present, then return proper response")
        void delete__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            var id = create(trace, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                assertExpectedResponseWithoutABody(expected, actualResponse, errorMessage, HttpStatus.SC_NO_CONTENT);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get traces, when api key is present, then return proper response")
        void get__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            int tracesCount = setupTracesForWorkspace(workspaceName, workspaceId, okApikey);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", DEFAULT_PROJECT)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var response = actualResponse.readEntity(Trace.TracePage.class);
                    assertThat(response.content()).hasSize(tracesCount);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback, when api key is present, then return proper response")
        void feedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            var id = create(trace, okApikey, workspaceName);

            var feedback = factory.manufacturePojo(FeedbackScore.class)
                    .toBuilder()
                    .source(ScoreSource.SDK)
                    .value(BigDecimal.ONE)
                    .categoryName("category")
                    .reason("reason")
                    .name("name")
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .path("/feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedback))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, errorMessage, HttpStatus.SC_NO_CONTENT);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete feedback, when api key is present, then return proper response")
        void deleteFeedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var trace = createTrace();

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var id = create(trace, okApikey, workspaceName);

            var score = FeedbackScore.builder()
                    .name("name")
                    .value(BigDecimal.valueOf(1))
                    .source(ScoreSource.UI)
                    .build();

            create(id, score, workspaceName, okApikey);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(DeleteFeedbackScore.builder().name("name").build()))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, errorMessage, HttpStatus.SC_NO_CONTENT);
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback batch, when api key is present, then return proper response")
        void feedbackBatch__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var trace = createTrace();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var id = create(trace, okApikey, workspaceName);

            var scores = IntStream.range(0, 5)
                    .mapToObj(i -> FeedbackScoreBatchItem.builder()
                            .name("name" + i)
                            .id(id)
                            .value(BigDecimal.valueOf(i))
                            .source(ScoreSource.SDK)
                            .projectName(trace.projectName())
                            .build())
                    .toList();

            var batch = FeedbackScoreBatch.builder()
                    .scores(scores)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(batch))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, errorMessage, HttpStatus.SC_NO_CONTENT);
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get trace threads, when api key is present, then return proper response")
        void getThreads__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .threadId(UUID.randomUUID().toString())
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            create(trace, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("threads")
                    .queryParam("project_name", DEFAULT_PROJECT)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertExpectedResponseWithoutABody(expected, actualResponse, errorMessage, HttpStatus.SC_OK);
            }
        }

    }

    @Nested
    @DisplayName("Session Token Cookie Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SessionTokenCookie {

        private final String sessionToken = UUID.randomUUID().toString();
        private final String fakeSessionToken = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(sessionToken, true, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, false, UUID.randomUUID().toString()));
        }

        @BeforeEach
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
        @MethodSource("credentials")
        @DisplayName("create trace, when session token is present, then return proper response")
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(trace))) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update trace, when session token is present, then return proper response")
        void update__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            var id = create(trace, API_KEY, workspaceName);

            var update = factory.manufacturePojo(TraceUpdate.class)
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .projectId(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(update))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, UNAUTHORIZED_RESPONSE,
                        HttpStatus.SC_NO_CONTENT);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete trace, when session token is present, then return proper response")
        void delete__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            var id = create(trace, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                assertExpectedResponseWithoutABody(expected, actualResponse, UNAUTHORIZED_RESPONSE,
                        HttpStatus.SC_NO_CONTENT);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get traces, when session token is present, then return proper response")
        void get__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var projectName = UUID.randomUUID().toString();

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(t -> t.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .toList();

            traces.forEach(trace -> create(trace, API_KEY, workspaceName));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", projectName)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var response = actualResponse.readEntity(Trace.TracePage.class);
                    assertThat(response.content()).hasSize(traces.size());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback, when session token is present, then return proper response")
        void feedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            var id = create(trace, API_KEY, workspaceName);

            var feedback = factory.manufacturePojo(FeedbackScore.class)
                    .toBuilder()
                    .source(ScoreSource.SDK)
                    .value(BigDecimal.ONE)
                    .categoryName("category")
                    .reason("reason")
                    .name("name")
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .path("/feedback-scores")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedback))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, UNAUTHORIZED_RESPONSE,
                        HttpStatus.SC_NO_CONTENT);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete feedback, when session token is present, then return proper response")
        void deleteFeedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean expected, String workspaceName) {
            var trace = createTrace();

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var id = create(trace, API_KEY, workspaceName);

            var score = FeedbackScore.builder()
                    .name("name")
                    .value(BigDecimal.valueOf(1))
                    .source(ScoreSource.UI)
                    .build();

            create(id, score, workspaceName, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(DeleteFeedbackScore.builder().name("name").build()))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, UNAUTHORIZED_RESPONSE,
                        HttpStatus.SC_NO_CONTENT);
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback batch, when session token is present, then return proper response")
        void feedbackBatch__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var trace = createTrace();

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var scores = IntStream.range(0, 5)
                    .mapToObj(i -> FeedbackScoreBatchItem.builder()
                            .name("name" + i)
                            .id(id)
                            .value(BigDecimal.valueOf(i))
                            .source(ScoreSource.SDK)
                            .projectName(trace.projectName())
                            .build())
                    .toList();

            var batch = FeedbackScoreBatch.builder()
                    .scores(scores)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/feedback-scores")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(batch))) {

                assertExpectedResponseWithoutABody(expected, actualResponse, UNAUTHORIZED_RESPONSE,
                        HttpStatus.SC_NO_CONTENT);
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get trace threads, when session token is present, then return proper response")
        void getThreads__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .threadId(UUID.randomUUID().toString())
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();

            create(trace, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("threads")
                    .queryParam("project_name", DEFAULT_PROJECT)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertExpectedResponseWithoutABody(expected, actualResponse, UNAUTHORIZED_RESPONSE, HttpStatus.SC_OK);
            }
        }
    }

    private void assertExpectedResponseWithoutABody(boolean expected, Response actualResponse,
            io.dropwizard.jersey.errors.ErrorMessage errorMessage, int expectedStatus) {
        if (expected) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_NO_CONTENT) {
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        } else {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
            assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                    .isEqualTo(errorMessage);
        }
    }

    @Nested
    @DisplayName("Filters Test:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FilterTest {

        interface TestAssertion {
            void assertTest(String projectName, UUID projectId, String apiKey, String workspaceName, List<?> expected,
                    List<?> unexpected, List<?> traces, List<? extends TraceFilter> filters,
                    Map<String, String> queryParams);
        }

        interface TestAssertionArgs<T> {
            Tuple3<List<?>, List<?>, List<?>> get(List<T> traces, List<T> expected, List<T> unexpected);
        }

        private TestAssertion getStatsAssertionMethod() {
            return (projectName, projectId, apiKey, workspaceName, expected, unexpected, traces, filters,
                    queryParams) -> getStatsAndAssert(projectName, projectId, filters, apiKey, workspaceName,
                            (List<ProjectStatItem<?>>) expected, queryParams);
        }

        private TestAssertionArgs<Trace> getStatsAssertionMethodArgs() {
            return (traces, expected, unexpected) -> Tuples.of(List.of(), getProjectTraceStatItems(expected),
                    List.of());
        }

        private TestAssertion getTracesAssertionMethod() {
            return (projectName, projectId, apiKey, workspaceName, expected, unexpected, traces, filters,
                    queryParams) -> getAndAssertPage(Integer.valueOf(queryParams.getOrDefault("page", "1")),
                            Integer.valueOf(queryParams.getOrDefault("size",
                                    traces.size() + expected.size() + unexpected.size() + "")),
                            projectName, projectId, filters, (List<Trace>) expected, (List<Trace>) unexpected,
                            workspaceName, apiKey, List.of());
        }

        private TestAssertionArgs<Trace> getTracesAssertionMethodArgs() {
            return (traces, expected, unexpected) -> Tuples.of(traces, expected, unexpected);
        }

        private Stream<Arguments> getFilterTestArguments() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/traces",
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs()));
        }

        private Stream<Arguments> equalAndNotEqualFilters() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/traces",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs()),
                    Arguments.of(
                            "/traces/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/traces",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs()));
        }

        private Stream<Arguments> getUsageKeyArgs() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs(),
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs(),
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs(),
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/traces",
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs(),
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces",
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs(),
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces",
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs(),
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS));
        }

        private Stream<Arguments> getFeedbackScoresArgs() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/traces",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs()),
                    Arguments.of(
                            "/traces/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/traces",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            getTracesAssertionMethod(),
                            getTracesAssertionMethodArgs()));
        }

        private Stream<Arguments> getDurationArgs() {
            Stream<Arguments> arguments = Stream.of(
                    arguments(Operator.EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 1.0),
                    arguments(Operator.GREATER_THAN, Duration.ofMillis(8L).toNanos() / 1000, 7.0),
                    arguments(Operator.GREATER_THAN_EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 1.0),
                    arguments(Operator.GREATER_THAN_EQUAL, Duration.ofMillis(1L).plusNanos(1000).toNanos() / 1000, 1.0),
                    arguments(Operator.LESS_THAN, Duration.ofMillis(1L).plusNanos(1).toNanos() / 1000, 2.0),
                    arguments(Operator.LESS_THAN_EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 1.0),
                    arguments(Operator.LESS_THAN_EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 2.0));

            return arguments.flatMap(arg -> Stream.of(
                    arguments("/traces/stats", getStatsAssertionMethod(), getStatsAssertionMethodArgs(), arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/traces", getTracesAssertionMethod(), getTracesAssertionMethodArgs(), arg.get()[0],
                            arg.get()[1], arg.get()[2])));
        }

        private Stream<Arguments> getFilterInvalidOperatorForFieldTypeArgs() {
            return filterQueryBuilder.getUnSupportedOperators(TraceField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> Stream.of(
                                    Arguments.of("/stats", TraceFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("", TraceFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()))));
        }

        private Stream<Arguments> getFilterInvalidValueOrKeyForFieldTypeArgs() {

            Stream<TraceFilter> filters = filterQueryBuilder.getSupportedOperators(TraceField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> switch (filter.getKey().getType()) {
                                case DICTIONARY, FEEDBACK_SCORES_NUMBER -> Stream.of(
                                        TraceFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(null)
                                                .value(getValidValue(filter.getKey()))
                                                .build(),
                                        TraceFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(getKey(filter.getKey()))
                                                .value(getInvalidValue(filter.getKey()))
                                                .build());
                                default -> Stream.of(TraceFilter.builder()
                                        .field(filter.getKey())
                                        .operator(operator)
                                        .value(getInvalidValue(filter.getKey()))
                                        .build());
                            }));

            return filters.flatMap(filter -> Stream.of(
                    arguments("/stats", filter),
                    arguments("", filter)));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project name and project id are null, then return bad request")
        void whenProjectNameAndIdAreNull__thenReturnBadRequest(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            UUID projectId = generator.generate();

            testAssertion.assertTest(null, projectId, API_KEY, TEST_WORKSPACE, List.of(), List.of(), List.of(),
                    List.of(), Map.of());
        }

        private Instant generateStartTime() {
            return Instant.now().minusMillis(randomNumber(1, 1000));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void findWithUsage(String endpoint, TestAssertion testAssertion, TestAssertionArgs<Trace> testAssertionArgs) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .startTime(generateStartTime())
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var traceIdToSpansMap = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .totalEstimatedCost(null)
                                    .build()))
                    .collect(Collectors.groupingBy(Span::traceId));
            batchCreateSpansAndAssert(
                    traceIdToSpansMap.values().stream().flatMap(List::stream).toList(), API_KEY, TEST_WORKSPACE);

            traces = traces.stream().map(trace -> trace.toBuilder()
                    .usage(traceIdToSpansMap.get(trace.id()).stream()
                            .map(Span::usage)
                            .flatMap(usage -> usage.entrySet().stream())
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey, Collectors.summingLong(Map.Entry::getValue))))
                    .build()).toList();

            var traceIdToCommentsMap = traces.stream()
                    .map(trace -> Pair.of(trace.id(),
                            IntStream.range(0, 5)
                                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(trace.id(), API_KEY,
                                            TEST_WORKSPACE, 201))
                                    .toList()))
                    .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

            traces = traces.stream().map(trace -> trace.toBuilder()
                    .usage(traceIdToSpansMap.get(trace.id()).stream()
                            .map(Span::usage)
                            .flatMap(usage -> usage.entrySet().stream())
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey, Collectors.summingLong(Map.Entry::getValue))))
                    .comments(traceIdToCommentsMap.get(trace.id()))
                    .build()).toList();

            var values = testAssertionArgs.get(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, API_KEY, TEST_WORKSPACE, values.getT2(), values.getT3(),
                    values.getT1(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void findWithoutUsage(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .startTime(generateStartTime())
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .startTime(trace.startTime())
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var values = testAssertionArgs.get(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project name is not empty, then return traces by project name")
        void whenProjectNameIsNotEmpty__thenReturnTracesByProjectName(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var projectName = UUID.randomUUID().toString();

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Trace> traces = new ArrayList<>();

            for (int i = 0; i < 15; i++) {
                Trace trace = createTrace()
                        .toBuilder()
                        .projectName(projectName)
                        .endTime(null)
                        .output(null)
                        .tags(null)
                        .feedbackScores(null)
                        .build();

                traces.add(trace);
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var values = testAssertionArgs.get(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project id is not empty, then return traces by project id")
        void whenProjectIdIsNotEmpty__thenReturnTracesByProjectId(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Trace trace = createTrace()
                    .toBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .output(null)
                    .projectId(null)
                    .tags(null)
                    .feedbackScores(null)
                    .build();

            create(trace, apiKey, workspaceName);

            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            var values = testAssertionArgs.get(List.of(), List.of(trace), List.of());

            testAssertion.assertTest(null, projectId, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when filtering by workspace name, then return traces filtered")
        void whenFilterWorkspaceName__thenReturnTracesFiltered(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName1 = UUID.randomUUID().toString();
            var workspaceName2 = UUID.randomUUID().toString();

            var projectName1 = UUID.randomUUID().toString();

            var workspaceId1 = UUID.randomUUID().toString();
            var workspaceId2 = UUID.randomUUID().toString();

            var apiKey1 = UUID.randomUUID().toString();
            var apiKey2 = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey1, workspaceName1, workspaceId1);
            mockTargetWorkspace(apiKey2, workspaceName2, workspaceId2);

            var traces1 = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName1)
                            .usage(null)
                            .threadId(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .comments(null)
                            .build())
                    .toList();

            var traces2 = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName1)
                            .usage(null)
                            .threadId(null)
                            .feedbackScores(null)
                            .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .totalEstimatedCost(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces1, apiKey1, workspaceName1);
            traceResourceClient.batchCreateTraces(traces2, apiKey2, workspaceName2);

            var valueTraces1 = testAssertionArgs.get(traces1, traces1.reversed(), List.of());
            var valueTraces2 = testAssertionArgs.get(traces2, traces2.reversed(), List.of());

            testAssertion.assertTest(projectName1, null, apiKey1, workspaceName1, valueTraces1.getT2(),
                    valueTraces1.getT3(), valueTraces1.getT1(), List.of(), Map.of());
            testAssertion.assertTest(projectName1, null, apiKey2, workspaceName2, valueTraces2.getT2(),
                    valueTraces2.getT3(), valueTraces2.getT1(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when traces have cost estimation, then return total cost estimation")
        void whenTracesHaveCostEstimation__thenReturnTotalCostEstimation(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Trace> traces = new ArrayList<>();

            for (int i = 0; i < 5; i++) {

                Trace trace = createTrace()
                        .toBuilder()
                        .projectName(projectName)
                        .endTime(null)
                        .output(null)
                        .projectId(null)
                        .tags(null)
                        .feedbackScores(null)
                        .usage(null)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .build();

                List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                        .map(span -> span.toBuilder()
                                .usage(spanResourceClient.getTokenUsage())
                                .model(spanResourceClient.randomModel().toString())
                                .provider(spanResourceClient.provider())
                                .traceId(trace.id())
                                .projectName(projectName)
                                .feedbackScores(null)
                                .totalEstimatedCost(null)
                                .build())
                        .toList();

                batchCreateSpansAndAssert(spans, apiKey, workspaceName);

                Trace expectedTrace = trace.toBuilder()
                        .totalEstimatedCost(calculateEstimatedCost(spans))
                        .usage(aggregateSpansUsage(spans))
                        .build();

                traces.add(expectedTrace);
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            var values = testAssertionArgs.get(traces, traces.reversed(), List.of());

            testAssertion.assertTest(null, projectId, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterIdAndNameEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(operator)
                            .value(traces.getFirst().id().toString())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(operator)
                            .value(traces.getFirst().name())
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterByThreadEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(UUID.randomUUID().toString())
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(traces.size() - 1, traces.getLast().toBuilder()
                    .threadId(null)
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.THREAD_ID)
                            .operator(operator)
                            .value(traces.getFirst().threadId())
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEqual__thenReturnTracesFiltered(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            List<TraceFilter> filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().name().toUpperCase())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameStartsWith__thenReturnTracesFiltered(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.STARTS_WITH)
                    .value(traces.getFirst().name().substring(0, traces.getFirst().name().length() - 4).toUpperCase())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEndsWith__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.ENDS_WITH)
                    .value(traces.getFirst().name().substring(3).toUpperCase())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameContains__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.CONTAINS)
                    .value(traces.getFirst().name().substring(2, traces.getFirst().name().length() - 3).toUpperCase())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameNotContains__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traceName = generator.generate().toString();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .name(traceName)
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .name(generator.generate().toString())
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.NOT_CONTAINS)
                    .value(traceName.toUpperCase())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterStartTimeEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(operator)
                    .value(traces.getFirst().startTime().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThan__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThan__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterEndTimeEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.END_TIME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().endTime().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterInputEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.INPUT)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().input().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterOutputEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.OUTPUT)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().output().toString())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTotalEstimatedCostGreaterThen__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var unexpectedTraces = traces.subList(1, traces.size());

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(traces.getFirst().id())
                            .usage(Map.of("completion_tokens", Math.abs(factory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(factory.manufacturePojo(Integer.class))))
                            .model("gpt-3.5-turbo-1106")
                            .provider("openai")
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toList());

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var expectedTrace = traces.getFirst().toBuilder()
                    .usage(aggregateSpansUsage(spans))
                    .totalEstimatedCost(calculateEstimatedCost(spans))
                    .build();

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(Operator.GREATER_THAN)
                    .value("0")
                    .build());

            var values = testAssertionArgs.get(traces, List.of(expectedTrace), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterTotalEstimatedCostEqual_NotEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces, // Here we swap the expected and unexpected traces
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(traces.getFirst().id())
                            .usage(Map.of("completion_tokens", Math.abs(factory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(factory.manufacturePojo(Integer.class))))
                            .model("gpt-3.5-turbo-1106")
                            .provider("openai")
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toList());

            var otherSpans = traces.stream().skip(1)
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .model(null)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList();

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);
            batchCreateSpansAndAssert(otherSpans, apiKey, workspaceName);

            traces.set(0, traces.getFirst().toBuilder()
                    .usage(aggregateSpansUsage(spans))
                    .totalEstimatedCost(calculateEstimatedCost(spans))
                    .build());

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(operator)
                    .value("0.00")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterMetadataEqualString__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(operator)
                    .key("$.model[0].version")
                    .value("OPENAI, CHAT-GPT 4.0")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());

        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNumber__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualBoolean__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("TRUE")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNull__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("NULL")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsString__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].version")
                    .value("CHAT-GPT")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNumber__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .threadId(null)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":\"two thousand twenty " +
                                    "four\",\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("02")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsBoolean__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("TRU")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNull__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .threadId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("NUL")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNumber__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2020," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanString__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].version")
                    .value("a")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanBoolean__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNull__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNumber__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2026," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("2025")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanString__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].version")
                    .value("z")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanBoolean__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNull__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTagsContains__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(traces.getFirst().tags().stream()
                            .toList()
                            .get(2)
                            .substring(0, traces.getFirst().name().length() - 4)
                            .toUpperCase())
                    .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs,
                String usageKey,
                Field field) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var otherUsageValue = randomNumber(1, 8);
            var usageValue = randomNumber();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, (long) otherUsageValue))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toList());

            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, (long) usageValue))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, otherUsageValue))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, usageValue))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThan__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123L))
                            .feedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456L))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 123))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.GREATER_THAN)
                    .value("123")
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123L))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456L))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 123))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThan__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456L))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123L))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 456))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.LESS_THAN)
                    .value("456")
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456L))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .threadId(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123L))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 456))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoresArgs")
        void whenFilterFeedbackScoresEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(1, traces.get(1).toBuilder()
                    .feedbackScores(
                            updateFeedbackScore(traces.get(1).feedbackScores(), traces.getFirst().feedbackScores(), 2))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(traces.getFirst().feedbackScores().get(1).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(1).value().toString())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThan__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 1234.5678))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);
            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.EQUAL)
                            .value(traces.getFirst().name())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 1234.5678))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThan__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .comments(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 2345.6789))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 2345.6789))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());;

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectName(RandomStringUtils.secure().nextAlphanumeric(20))
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);
            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getDurationArgs")
        void whenFilterByDuration__thenReturnTracesFiltered(String endpoint,
                TestAssertion testAssertion,
                TestAssertionArgs<Trace> testAssertionArgs,
                Operator operator,
                long end,
                double duration) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> {
                        Instant now = Instant.now();
                        return trace.toBuilder()
                                .projectId(null)
                                .usage(null)
                                .projectName(projectName)
                                .feedbackScores(null)
                                .threadId(null)
                                .totalEstimatedCost(null)
                                .startTime(now)
                                .endTime(Set.of(Operator.LESS_THAN, Operator.LESS_THAN_EQUAL).contains(operator)
                                        ? Instant.now().plusSeconds(2)
                                        : now.plusNanos(1000))
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            var start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(start)
                    .endTime(start.plus(end, ChronoUnit.MICROS))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());

            var unexpectedTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.DURATION)
                            .operator(operator)
                            .value(String.valueOf(duration))
                            .build());

            var values = testAssertionArgs.get(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidOperatorForFieldTypeArgs")
        void whenFilterInvalidOperatorForFieldType__thenReturn400(String path, Filter filter) {

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_BAD_REQUEST,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(path)
                    .queryParam("project_name", projectName)
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(String path, Filter filter) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(path)
                    .queryParam("project_name", projectName)
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }
    }

    private BigDecimal calculateEstimatedCost(List<Span> spans) {
        return spans.stream()
                .map(span -> CostService.calculateCost(span.model(), span.provider(), span.usage()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertThreadPage(String projectName, UUID projectId, List<? extends TraceThread> expectedThreads,
            List<TraceThreadFilter> filters, Map<String, String> queryParams, String apiKey, String workspaceName) {
        WebTarget target = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("threads");

        target = queryParams.entrySet()
                .stream()
                .reduce(target, (acc, entry) -> acc.queryParam(entry.getKey(), entry.getValue()), (a, b) -> b);

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }

        if (projectName != null) {
            target = target.queryParam("project_name", projectName);
        }

        if (CollectionUtils.isNotEmpty(filters)) {
            target = target.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        var actualResponse = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

        var actualPage = actualResponse.readEntity(TraceThreadPage.class);
        var actualTraces = actualPage.content();

        assertThat(actualTraces).hasSize(expectedThreads.size());
        assertThat(actualPage.total()).isEqualTo(expectedThreads.size());

        assertThat(actualTraces)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_TRACES)
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                .isEqualTo(expectedThreads);

        for (int i = 0; i < expectedThreads.size(); i++) {
            var expectedThread = expectedThreads.get(i);
            var actualThread = actualTraces.get(i);

            assertThat(actualThread.createdAt()).isBetween(expectedThread.createdAt(), Instant.now());
            assertThat(actualThread.lastUpdatedAt()).isBetween(expectedThread.lastUpdatedAt(), Instant.now());
        }
    }

    private void getStatsAndAssert(String projectName, UUID projectId, List<? extends TraceFilter> filters,
            String apiKey, String workspaceName, List<ProjectStatItem<?>> expectedStats,
            Map<String, String> queryParams) {
        WebTarget webTarget = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("stats");

        if (projectName != null) {
            webTarget = webTarget.queryParam("project_name", projectName);
        }

        if (filters != null) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        if (projectId != null) {
            webTarget = webTarget.queryParam("project_id", projectId);
        }

        webTarget = queryParams.entrySet()
                .stream()
                .reduce(webTarget, (acc, entry) -> acc.queryParam(entry.getKey(), entry.getValue()), (a, b) -> b);

        var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
        ProjectStats actualStats = actualResponse.readEntity(ProjectStats.class);

        assertThat(actualStats.stats()).hasSize(expectedStats.size());

        assertThat(actualStats.stats())
                .usingRecursiveComparison(StatsUtils.getRecursiveComparisonConfiguration())
                .isEqualTo(expectedStats);
    }

    private String getValidValue(Field field) {
        return switch (field.getType()) {
            case STRING, LIST, DICTIONARY -> RandomStringUtils.secure().nextAlphanumeric(10);
            case NUMBER, FEEDBACK_SCORES_NUMBER -> String.valueOf(randomNumber(1, 10));
            case DATE_TIME -> Instant.now().toString();
        };
    }

    private String getKey(Field field) {
        return switch (field.getType()) {
            case STRING, NUMBER, DATE_TIME, LIST -> null;
            case FEEDBACK_SCORES_NUMBER, DICTIONARY -> RandomStringUtils.secure().nextAlphanumeric(10);
        };
    }

    private String getInvalidValue(Field field) {
        return switch (field.getType()) {
            case STRING, DICTIONARY, LIST -> " ";
            case NUMBER, DATE_TIME, FEEDBACK_SCORES_NUMBER -> RandomStringUtils.secure().nextAlphanumeric(10);
        };
    }

    @Nested
    @DisplayName("Find trace Threads:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraceThreads {

        private Stream<Arguments> getUnsupportedOperations() {
            return filterQueryBuilder.getUnSupportedOperators(TraceThreadField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .map(operator -> Arguments.of(filter.getKey(), operator, getValidValue(filter.getKey()))));
        }

        private Stream<TraceThreadFilter> getFilterInvalidValueOrKeyForFieldTypeArgs() {
            return filterQueryBuilder.getSupportedOperators(TraceThreadField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> switch (filter.getKey().getType()) {
                                case DICTIONARY, FEEDBACK_SCORES_NUMBER -> Stream.of(
                                        TraceThreadFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(null)
                                                .value(getValidValue(filter.getKey()))
                                                .build(),
                                        TraceThreadFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(getKey(filter.getKey()))
                                                .value(getInvalidValue(filter.getKey()))
                                                .build());
                                default -> Stream.of(TraceThreadFilter.builder()
                                        .field(filter.getKey())
                                        .operator(operator)
                                        .value(getInvalidValue(filter.getKey()))
                                        .build());
                            }));
        }

        private Stream<Arguments> getValidFilters() {
            return Stream.of(
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.ID)
                                    .operator(Operator.EQUAL)
                                    .value(traces.getFirst().threadId())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .map(trace -> trace.toBuilder()
                                            .threadId(UUID.randomUUID().toString())
                                            .build())
                                    .toList()),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.FIRST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().sorted(Comparator.comparing(Trace::startTime)).findFirst()
                                            .get().input().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.LAST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().sorted(Comparator.comparing(Trace::endTime).reversed())
                                            .findFirst().get().output().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.CREATED_AT)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(traces.stream().min(Comparator.comparing(Trace::createdAt)).get().createdAt()
                                            .toString())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.DURATION)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                            traces.stream().min(Comparator.comparing(Trace::startTime)).get()
                                                    .startTime(),
                                            traces.stream().max(Comparator.comparing(Trace::endTime)).get().endTime())
                                            .toString())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .map(trace -> trace.toBuilder()
                                            .endTime(trace.endTime().plusMillis(100))
                                            .build())
                                    .toList()),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.LAST_UPDATED_AT)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(traces.stream().max(Comparator.comparing(Trace::lastUpdatedAt)).get()
                                            .lastUpdatedAt().toString())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.NUMBER_OF_MESSAGES)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(String.valueOf(traces.size() * 2))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .map(trace -> trace.toBuilder()
                                            .threadId(UUID.randomUUID().toString())
                                            .build())
                                    .toList()));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            Trace trace = createTrace();

            var traces = Stream.of(trace)
                    .map(it -> it.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .input(original)
                            .output(original)
                            .threadId(threadId)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            var expectedThreads = List.of(TraceThread.builder()
                    .firstMessage(expected)
                    .lastMessage(expected)
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                            trace.endTime()))
                    .projectId(projectId)
                    .createdBy(USER)
                    .startTime(trace.startTime())
                    .endTime(trace.endTime())
                    .numberOfMessages(traces.size() * 2)
                    .id(threadId)
                    .createdAt(trace.createdAt())
                    .lastUpdatedAt(trace.lastUpdatedAt())
                    .build());

            Map<String, String> queryParams = Map.of("page", "1", "size", "5", "truncate", String.valueOf(truncate));

            assertThreadPage(projectName, null, expectedThreads, List.of(), queryParams, API_KEY,
                    TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource("getUnsupportedOperations")
        void whenFilterUnsupportedOperation__thenReturn400(TraceThreadField field, Operator operator, String value) {
            var filter = TraceThreadFilter.builder()
                    .field(field)
                    .operator(operator)
                    .key(getKey(field))
                    .value(value)
                    .build();

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_BAD_REQUEST,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("threads")
                    .queryParam("project_name", projectName)
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(TraceThreadFilter filter) {
            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("threads")
                    .queryParam("project_name", projectName)
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }

        @ParameterizedTest
        @MethodSource("getValidFilters")
        void whenFilterThreads__thenReturnThreadsFiltered(
                Function<List<Trace>, TraceThreadFilter> getFilter,
                Function<List<Trace>, List<Trace>> getExpectedThreads,
                Function<List<Trace>, List<Trace>> getUnexpectedThreads) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();
            var unexpectedThreadId = UUID.randomUUID().toString();

            var traces = IntStream.range(0, 5)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(threadId)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toList());

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            List<Trace> createTraces = traceResourceClient.getByProjectName(projectName, API_KEY, TEST_WORKSPACE);
            List<Trace> expectedTraces = getExpectedThreads.apply(createTraces);

            var otherTraces = IntStream.range(0, 5)
                    .mapToObj(it -> createTrace().toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .threadId(unexpectedThreadId)
                            .build())
                    .collect(Collectors.toList());

            List<Trace> unexpectedTraces = getUnexpectedThreads.apply(otherTraces);

            traceResourceClient.batchCreateTraces(unexpectedTraces, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            List<TraceThread> expectedThreads = getExpectedThreads(expectedTraces, projectId, threadId);

            var filter = getFilter.apply(expectedTraces);

            assertThreadPage(projectName, null, expectedThreads, List.of(filter), Map.of(), API_KEY, TEST_WORKSPACE);
        }

    }

    private List<TraceThread> getExpectedThreads(List<Trace> expectedTraces, UUID projectId, String threadId) {
        return expectedTraces.size() == 0
                ? List.of()
                : List.of(TraceThread.builder()
                        .firstMessage(expectedTraces.stream().sorted(Comparator.comparing(Trace::startTime))
                                .findFirst().get().input())
                        .lastMessage(expectedTraces.stream().sorted(Comparator.comparing(Trace::endTime).reversed())
                                .findFirst().get().output())
                        .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).get()
                                        .startTime(),
                                expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).get().endTime()))
                        .projectId(projectId)
                        .createdBy(USER)
                        .startTime(expectedTraces.stream().sorted(Comparator.comparing(Trace::startTime))
                                .findFirst().get().startTime())
                        .endTime(expectedTraces.stream().sorted(Comparator.comparing(Trace::endTime).reversed())
                                .findFirst().get().endTime())
                        .numberOfMessages(expectedTraces.size() * 2)
                        .id(threadId)
                        .createdAt(expectedTraces.stream().sorted(Comparator.comparing(Trace::createdAt))
                                .findFirst().get().createdAt())
                        .lastUpdatedAt(expectedTraces.stream()
                                .sorted(Comparator.comparing(Trace::lastUpdatedAt).reversed()).findFirst().get()
                                .lastUpdatedAt())
                        .build());
    }

    @Nested
    @DisplayName("Find traces:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraces {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = Stream.of(createTrace())
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("page", 1)
                    .queryParam("size", 5)
                    .queryParam("project_name", projectName)
                    .queryParam("truncate", truncate)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualPage = actualResponse.readEntity(Trace.TracePage.class);
            var actualTraces = actualPage.content();

            assertThat(actualTraces).hasSize(1);

            var expectedTraces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .input(expected)
                            .output(expected)
                            .metadata(expected)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                                    trace.endTime()))
                            .build())
                    .toList();

            assertThat(actualTraces)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                    .containsExactlyElementsOf(expectedTraces);
        }

        @ParameterizedTest
        @MethodSource
        void getTracesByProject__whenSortingByValidFields__thenReturnTracesSorted(Comparator<Trace> comparator,
                SortingField sorting) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .comments(null)
                            .threadId(null)
                            .build())
                    .map(trace -> trace.toBuilder()
                            .duration(trace.startTime().until(trace.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = traces.stream()
                    .sorted(comparator)
                    .toList();

            List<SortingField> sortingFields = List.of(sorting);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, expectedTraces, List.of(), apiKey,
                    sortingFields);
        }

        static Stream<Arguments> getTracesByProject__whenSortingByValidFields__thenReturnTracesSorted() {

            Comparator<Trace> inputComparator = Comparator.comparing(trace -> trace.input().toString());
            Comparator<Trace> outputComparator = Comparator.comparing(trace -> trace.output().toString());
            Comparator<Trace> metadataComparator = Comparator.comparing(trace -> trace.metadata().toString());
            Comparator<Trace> tagsComparator = Comparator.comparing(trace -> trace.tags().toString());
            Comparator<Trace> errorInfoComparator = Comparator.comparing(trace -> trace.errorInfo().toString());

            return Stream.of(
                    Arguments.of(Comparator.comparing(Trace::name),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::name).reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::startTime),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::startTime).reversed(),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::endTime),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::endTime).reversed(),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(Trace::duration)
                                    .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(Trace::duration).reversed()
                                    .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.DESC).build()),
                    Arguments.of(inputComparator,
                            SortingField.builder().field(SortableFields.INPUT).direction(Direction.ASC).build()),
                    Arguments.of(inputComparator.reversed(),
                            SortingField.builder().field(SortableFields.INPUT).direction(Direction.DESC).build()),
                    Arguments.of(outputComparator,
                            SortingField.builder().field(SortableFields.OUTPUT).direction(Direction.ASC).build()),
                    Arguments.of(outputComparator.reversed(),
                            SortingField.builder().field(SortableFields.OUTPUT).direction(Direction.DESC).build()),
                    Arguments.of(metadataComparator,
                            SortingField.builder().field(SortableFields.METADATA).direction(Direction.ASC).build()),
                    Arguments.of(metadataComparator.reversed(),
                            SortingField.builder().field(SortableFields.METADATA).direction(Direction.DESC).build()),
                    Arguments.of(tagsComparator,
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                    Arguments.of(tagsComparator.reversed(),
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::id),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::id).reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                    Arguments.of(errorInfoComparator,
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.ASC).build()),
                    Arguments.of(errorInfoComparator.reversed(),
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.DESC).build()));
        }

        @Test
        void getTracesByProject__whenSortingByInvalidField__thenReturn400() {
            var field = RandomStringUtils.secure().nextAlphanumeric(10);
            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid sorting fields '%s'".formatted(field));
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var sortingFields = List.of(SortingField.builder().field(field).direction(Direction.ASC).build());
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", projectName)
                    .queryParam("sorting",
                            URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }

    }

    private Integer randomNumber() {
        return randomNumber(10, 99);
    }

    private static int randomNumber(int minValue, int maxValue) {
        return PodamUtils.getIntegerInRange(minValue, maxValue);
    }

    private void getAndAssertPage(String workspaceName, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> traces,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String apiKey) {
        getAndAssertPage(workspaceName, projectName, projectId, filters, traces, expectedTraces, unexpectedTraces,
                apiKey, null);
    }

    private void getAndAssertPage(String workspaceName, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> traces,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String apiKey, List<SortingField> sortingFields) {
        int page = 1;

        int size = traces.size() + expectedTraces.size() + unexpectedTraces.size();
        getAndAssertPage(page, size, projectName, projectId, filters, expectedTraces, unexpectedTraces,
                workspaceName, apiKey, sortingFields);
    }

    private void getAndAssertPage(int page, int size, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String workspaceName, String apiKey,
            List<SortingField> sortingFields) {

        WebTarget target = client.target(URL_TEMPLATE.formatted(baseURI));

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        if (page > 0) {
            target = target.queryParam("page", page);
        }

        if (size > 0) {
            target = target.queryParam("size", size);
        }

        if (projectName != null) {
            target = target.queryParam("project_name", projectName);
        }

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }

        var actualResponse = target
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

        var actualPage = actualResponse.readEntity(Trace.TracePage.class);
        var actualTraces = actualPage.content();

        assertThat(actualPage.page()).isEqualTo(page);
        assertThat(actualPage.size()).isEqualTo(expectedTraces.size());
        assertThat(actualPage.total()).isEqualTo(expectedTraces.size());
        assertThat(actualTraces)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                .containsExactlyElementsOf(expectedTraces);
        assertIgnoredFields(actualTraces, expectedTraces);

        if (!unexpectedTraces.isEmpty()) {
            assertThat(actualTraces)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                    .doesNotContainAnyElementsOf(unexpectedTraces);
        }
    }

    private void getAndAssertPageSpans(
            String workspaceName,
            String projectName,
            List<? extends Filter> filters,
            List<Span> spans,
            List<Span> expectedSpans,
            List<Span> unexpectedSpans, String apiKey) {
        int page = 1;
        int size = spans.size() + expectedSpans.size() + unexpectedSpans.size();
        getAndAssertPageSpans(
                workspaceName,
                projectName,
                null,
                null,
                null,
                filters,
                page,
                size,
                expectedSpans,
                expectedSpans.size(),
                unexpectedSpans, apiKey);
    }

    private void getAndAssertPageSpans(
            String workspaceName,
            String projectName,
            UUID projectId,
            UUID traceId,
            SpanType type,
            List<? extends Filter> filters,
            int page,
            int size,
            List<Span> expectedSpans,
            int expectedTotal,
            List<Span> unexpectedSpans, String apiKey) {
        try (var actualResponse = client.target(URL_TEMPLATE_SPANS.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParam("project_name", projectName)
                .queryParam("project_id", projectId)
                .queryParam("trace_id", traceId)
                .queryParam("type", type)
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            var actualPage = actualResponse.readEntity(Span.SpanPage.class);
            var actualSpans = actualPage.content();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(actualPage.page()).isEqualTo(page);
            assertThat(actualPage.size()).isEqualTo(expectedSpans.size());
            assertThat(actualPage.total()).isEqualTo(expectedTotal);

            assertThat(actualSpans.size()).isEqualTo(expectedSpans.size());
            assertThat(actualSpans)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_SPANS)
                    .containsExactlyElementsOf(expectedSpans);
            assertIgnoredFieldsSpans(actualSpans, expectedSpans);

            if (!unexpectedSpans.isEmpty()) {
                assertThat(actualSpans)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_SPANS)
                        .doesNotContainAnyElementsOf(unexpectedSpans);
            }
        }
    }

    private String toURLEncodedQueryParam(List<? extends Filter> filters) {
        return URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
    }

    private void assertIgnoredFields(List<Trace> actualTraces, List<Trace> expectedTraces) {
        assertThat(actualTraces).size().isEqualTo(expectedTraces.size());
        for (int i = 0; i < actualTraces.size(); i++) {
            var actualTrace = actualTraces.get(i);
            var expectedTrace = expectedTraces.get(i);
            assertIgnoredFields(actualTrace, expectedTrace);
        }
    }

    private void assertIgnoredFields(Trace actualTrace, Trace expectedTrace) {
        assertThat(actualTrace.projectId()).isNotNull();
        assertThat(actualTrace.projectName()).isNull();
        assertThat(actualTrace.createdAt()).isAfter(expectedTrace.createdAt());
        assertThat(actualTrace.lastUpdatedAt()).isAfter(expectedTrace.lastUpdatedAt());
        assertThat(actualTrace.createdBy()).isEqualTo(USER);
        assertThat(actualTrace.lastUpdatedBy()).isEqualTo(USER);
        assertThat(actualTrace.threadId()).isEqualTo(expectedTrace.threadId());

        var expected = DurationUtils.getDurationInMillisWithSubMilliPrecision(
                expectedTrace.startTime(), expectedTrace.endTime());

        if (actualTrace.duration() == null || expected == null) {
            assertThat(actualTrace.duration()).isEqualTo(expected);
        } else {
            assertThat(actualTrace.duration()).isEqualTo(expected, within(0.001));
        }

        assertThat(actualTrace.feedbackScores())
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .ignoringFields(IGNORED_FIELDS_SCORES)
                .ignoringCollectionOrder()
                .isEqualTo(expectedTrace.feedbackScores());

        if (expectedTrace.feedbackScores() != null) {
            actualTrace.feedbackScores().forEach(feedbackScore -> {
                assertThat(feedbackScore.createdAt()).isAfter(expectedTrace.createdAt());
                assertThat(feedbackScore.lastUpdatedAt()).isAfter(expectedTrace.lastUpdatedAt());
                assertThat(feedbackScore.createdBy()).isEqualTo(USER);
                assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(USER);
            });
        }

        if (actualTrace.comments() != null) {
            assertComments(expectedTrace.comments(), actualTrace.comments());

            actualTrace.comments().forEach(comment -> {
                assertThat(comment.createdAt()).isAfter(actualTrace.createdAt());
                assertThat(comment.lastUpdatedAt()).isAfter(actualTrace.lastUpdatedAt());
                assertThat(comment.createdBy()).isEqualTo(USER);
                assertThat(comment.lastUpdatedBy()).isEqualTo(USER);
            });
        }

    }

    private void assertIgnoredFieldsSpans(List<Span> actualSpans, List<Span> expectedSpans) {
        for (int i = 0; i < actualSpans.size(); i++) {
            var actualSpan = actualSpans.get(i);
            var expectedSpan = expectedSpans.get(i);
            assertThat(actualSpan.projectId()).isNotNull();
            assertThat(actualSpan.projectName()).isNull();
            assertThat(actualSpan.createdAt()).isAfter(expectedSpan.createdAt());
            assertThat(actualSpan.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
            var expected = DurationUtils.getDurationInMillisWithSubMilliPrecision(
                    expectedSpan.startTime(), expectedSpan.endTime());

            if (actualSpan.duration() == null || expected == null) {
                assertThat(actualSpan.duration()).isEqualTo(expected);
            } else {
                assertThat(actualSpan.duration()).isEqualTo(expected, within(0.001));
            }

            assertThat(actualSpan.feedbackScores())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .ignoringFields(IGNORED_FIELDS_SCORES)
                    .ignoringCollectionOrder()
                    .isEqualTo(expectedSpan.feedbackScores());

            if (actualSpan.feedbackScores() != null) {
                actualSpan.feedbackScores().forEach(feedbackScore -> {
                    assertThat(feedbackScore.createdAt()).isAfter(expectedSpan.createdAt());
                    assertThat(feedbackScore.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
                    assertThat(feedbackScore.createdBy()).isEqualTo(USER);
                    assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(USER);
                });
            }
        }
    }

    private List<FeedbackScore> updateFeedbackScore(List<FeedbackScore> feedbackScores, int index, double val) {
        feedbackScores.set(index, feedbackScores.get(index).toBuilder()
                .value(BigDecimal.valueOf(val))
                .build());
        return feedbackScores;
    }

    private List<FeedbackScore> updateFeedbackScore(
            List<FeedbackScore> destination, List<FeedbackScore> source, int index) {
        destination.set(index, source.get(index).toBuilder().build());
        return destination;
    }

    @Nested
    @DisplayName("Get trace:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetTrace {

        @Test
        void getTraceWithUsage() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var span = factory.manufacturePojo(Span.class);
            var usage = Stream.concat(
                    Map.of("completion_tokens", 2 * 5L, "prompt_tokens", 3 * 5L + 3, "total_tokens", 4 * 5L)
                            .entrySet().stream(),
                    span.usage().entrySet().stream())
                    .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().longValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            var trace = createTrace()
                    .toBuilder()
                    .id(null)
                    .projectName(projectName)
                    .endTime(null)
                    .input(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(usage)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(id)
                            .usage(Map.of("completion_tokens", 2, "prompt_tokens", 3, "total_tokens", 4))
                            .build())
                    .collect(Collectors.toList());
            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(id)
                    .usage(null)
                    .build());
            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(id)
                    .usage(Map.of("prompt_tokens", 3))
                    .build());
            spans.add(span.toBuilder()
                    .projectName(projectName)
                    .traceId(id)
                    .build());
            batchCreateSpansAndAssert(spans, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder().id(id).build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"gpt-3.5-turbo-1106", "unknown-model"})
        void getTraceWithCost(String model) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var trace = createTrace()
                    .toBuilder()
                    .id(null)
                    .projectName(projectName)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(id)
                            .usage(Map.of("completion_tokens", Math.abs(factory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(factory.manufacturePojo(Integer.class))))
                            .model(model)
                            .provider("openai")
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toList());

            var usage = aggregateSpansUsage(spans);
            BigDecimal traceExpectedCost = calculateEstimatedCost(spans);

            batchCreateSpansAndAssert(spans, API_KEY, TEST_WORKSPACE);

            // Create some comments, might affect cost/usage query
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(id, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder().id(id).usage(usage).comments(expectedComments).build();
            Trace createdTrace = getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);

            assertThat(createdTrace.totalEstimatedCost())
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(traceExpectedCost.compareTo(BigDecimal.ZERO) == 0 ? null : traceExpectedCost);
        }

        @Test
        void getTraceWithoutUsage() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var trace = createTrace()
                    .toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            create(trace, apiKey, workspaceName);

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(null)
                            .build())
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var projectId = getProjectId(projectName, workspaceName, apiKey);
            getAndAssert(trace, projectId, apiKey, workspaceName);
        }

        @Test
        @DisplayName("when trace does not exist, then return not found")
        void getTrace__whenTraceDoesNotExist__thenReturnNotFound() {
            var id = generator.generate();
            getAndAssertTraceNotFound(id, API_KEY, TEST_WORKSPACE);
        }
    }

    private Trace createTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .threadId(null)
                .comments(null)
                .totalEstimatedCost(null)
                .duration(null)
                .usage(null)
                .build();
    }

    private UUID create(Trace trace, String apiKey, String workspaceName) {
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private void createAndAssertErrorMessage(Trace trace, String apiKey, String workspaceName, int status,
            String errorMessage) {
        try (var response = traceResourceClient.createTrace(trace, apiKey, workspaceName, status)) {
            assertThat(response.readEntity(ErrorMessage.class).errors().getFirst()).isEqualTo(errorMessage);
        }
    }

    private void create(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        traceResourceClient.feedbackScore(entityId, score, workspaceName, apiKey);
    }

    private Trace getAndAssert(Trace expectedTrace, UUID projectId, String apiKey, String workspaceName) {
        var actualTrace = traceResourceClient.getById(expectedTrace.id(), workspaceName, apiKey);

        assertThat(actualTrace)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_TRACES)
                .isEqualTo(expectedTrace);

        assertThat(actualTrace.projectId()).isEqualTo(projectId);
        assertIgnoredFields(actualTrace, expectedTrace);

        return actualTrace;
    }

    private void getAndAssertTraceNotFound(UUID id, String apiKey, String testWorkspace) {
        var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, testWorkspace)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                .isEqualTo("Trace id: %s not found".formatted(id));
    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateTrace {

        @Test
        @DisplayName("Success")
        void testCreateTrace() {
            var id = generator.generate();
            var trace = createTrace().toBuilder()
                    .id(id)
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when creating traces with different project names, then return created traces")
        void create__whenCreatingTracesWithDifferentProjectNames__thenReturnCreatedTraces() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var trace1 = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var trace2 = createTrace()
                    .toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            create(trace1, API_KEY, TEST_WORKSPACE);
            create(trace2, API_KEY, TEST_WORKSPACE);

            var projectId1 = getProjectId(DEFAULT_PROJECT, TEST_WORKSPACE, API_KEY);
            var projectId2 = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            getAndAssert(trace1, projectId1, API_KEY, TEST_WORKSPACE);
            getAndAssert(trace2, projectId2, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when creating traces with same Id for different workspaces, then return conflict")
        void create__whenCreatingTracesWithSameIdForDifferentWorkspaces__thenReturnConflict() {

            var trace1 = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            create(trace1, API_KEY, TEST_WORKSPACE);

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var trace2 = createTrace()
                    .toBuilder()
                    .id(trace1.id())
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            createAndAssertErrorMessage(trace2, apiKey, workspaceName, HttpStatus.SC_CONFLICT,
                    PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
        }

        @Test
        void createWithMissingId() {
            var trace = createTrace().toBuilder()
                    .id(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            trace = trace.toBuilder().id(id).build();
            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when project doesn't exist, then accept and create project")
        void create__whenProjectDoesNotExist__thenAcceptAndCreateProject() {

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var trace = createTrace().toBuilder()
                    .projectName(projectName)
                    .build();

            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            Project project = projectResourceClient.getByName(projectName, API_KEY, TEST_WORKSPACE);

            assertThat(project).isNotNull();
        }

        @Test
        @DisplayName("when project name is null, then accept and use default project")
        void create__whenProjectNameIsNull__thenAcceptAndUseDefaultProject() {

            var id = generator.generate();

            var trace = createTrace().toBuilder()
                    .id(id)
                    .projectName(null)
                    .build();

            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var actualEntity = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);

            UUID projectId = getProjectId(DEFAULT_PROJECT, TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.projectId()).isEqualTo(projectId);
        }

        @Test
        @DisplayName("when trace input is big, then accept and create trace")
        void createAndGet__whenTraceInputIsBig__thenReturnSpan() {

            int size = 1000;

            Map<String, String> jsonMap = IntStream.range(0, size)
                    .mapToObj(
                            i -> Map.entry(RandomStringUtils.randomAlphabetic(10), RandomStringUtils.randomAscii(size)))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            var expectedTrace = createTrace().toBuilder()
                    .projectId(null)
                    .input(JsonUtils.readTree(jsonMap))
                    .output(JsonUtils.readTree(jsonMap))
                    .feedbackScores(null)
                    .usage(null)
                    .build();

            create(expectedTrace, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(expectedTrace.projectName(), TEST_WORKSPACE, API_KEY);
            getAndAssert(expectedTrace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace has threadId, then accept and create trace")
        void createAndGet__whenTraceHasThreadId__thenReturnTrace() {

            var threadId = UUID.randomUUID().toString();

            var expectedTrace = createTrace().toBuilder()
                    .projectId(null)
                    .threadId(threadId)
                    .feedbackScores(null)
                    .usage(null)
                    .build();

            create(expectedTrace, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(expectedTrace.projectName(), TEST_WORKSPACE, API_KEY);
            getAndAssert(expectedTrace, projectId, API_KEY, TEST_WORKSPACE);
        }

    }

    @Nested
    @DisplayName("Batch:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchInsert {

        @ParameterizedTest
        @MethodSource
        void batch__whenCreateTraces__thenReturnNoContent(Function<String, String> projectNameModifier) {

            var projectName = UUID.randomUUID().toString();

            createProject(projectName, TEST_WORKSPACE, API_KEY);

            var expectedTraces = IntStream.range(0, 1000)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectName(projectNameModifier.apply(projectName))
                            .endTime(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(expectedTraces, API_KEY, TEST_WORKSPACE);

            getAndAssertPage(TEST_WORKSPACE, projectName, null, List.of(), List.of(), expectedTraces.reversed(),
                    List.of(),
                    API_KEY);
        }

        Stream<Arguments> batch__whenCreateTraces__thenReturnNoContent() {
            return getProjectNameModifierArgs();
        }

        @Test
        void batch__whenTraceProjectNameIsNull__thenUserDefaultProjectAndReturnNoContent() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(null)
                            .endTime(null)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(expectedTraces, apiKey, workspaceName);

            getAndAssertPage(workspaceName, DEFAULT_PROJECT, null, List.of(), List.of(), expectedTraces.reversed(),
                    List.of(),
                    apiKey);
        }

        @Test
        void batch__whenSendingMultipleTracesWithSameId__thenReturn422() {
            var trace = createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(null)
                    .build();

            var expectedTrace = trace.toBuilder()
                    .tags(Set.of())
                    .endTime(Instant.now())
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .build();

            List<Trace> traces = List.of(trace, expectedTrace);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(TraceBatch.builder().traces(traces).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();

                var errorMessage = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(errorMessage.getMessage()).isEqualTo("Duplicate trace id '%s'".formatted(trace.id()));
            }
        }

        @ParameterizedTest
        @MethodSource
        void batch__whenBatchIsInvalid__thenReturn422(List<Trace> traces, String errorMessage) {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(TraceBatch.builder().traces(traces).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();

                var responseBody = actualResponse.readEntity(ErrorMessage.class);
                assertThat(responseBody.errors()).contains(errorMessage);
            }
        }

        Stream<Arguments> batch__whenBatchIsInvalid__thenReturn422() {
            return Stream.of(
                    Arguments.of(List.of(), "traces size must be between 1 and 1000"),
                    Arguments.of(IntStream.range(0, 1001)
                            .mapToObj(i -> createTrace().toBuilder()
                                    .projectId(null)
                                    .feedbackScores(null)
                                    .build())
                            .toList(), "traces size must be between 1 and 1000"));
        }

        @Test
        void batch__whenSendingMultipleTracesWithNoId__thenReturnNoContent() {
            var newTrace = createTrace().toBuilder()
                    .projectId(null)
                    .id(null)
                    .feedbackScores(null)
                    .build();

            var expectedTrace = newTrace.toBuilder()
                    .tags(Set.of())
                    .endTime(Instant.now())
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .build();

            List<Trace> expectedTraces = List.of(newTrace, expectedTrace);

            traceResourceClient.batchCreateTraces(expectedTraces, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void batch__whenTraceHasThreadId__thenReturnNoContent() {
            var threadId = UUID.randomUUID().toString();
            var projectName = UUID.randomUUID().toString();

            var expectedTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .threadId(threadId)
                            .projectName(projectName)
                            .endTime(trace.startTime().plusMillis(randomNumber()))
                            .feedbackScores(null)
                            .usage(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(expectedTraces, API_KEY, TEST_WORKSPACE);

            getAndAssertPage(TEST_WORKSPACE, projectName, null, List.of(), List.of(), expectedTraces.reversed(),
                    List.of(),
                    API_KEY);
        }
    }

    private Stream<Arguments> getProjectNameModifierArgs() {
        return Stream.of(
                arguments(Function.identity()),
                arguments((Function<String, String>) String::toUpperCase));
    }

    private void batchCreateSpansAndAssert(List<Span> expectedSpans, String apiKey, String workspaceName) {
        spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);
    }

    @Nested
    @DisplayName("Delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteTrace {

        @Test
        @DisplayName("Success")
        void delete() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = List.of(createTrace().toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var traceScores = traces.stream()
                    .flatMap(trace -> trace.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    trace.id(), projectName, item)))
                    .toList();

            traceResourceClient.feedbackScores(traceScores, apiKey, workspaceName);

            var spanScores = spans.stream()
                    .flatMap(span -> span.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    span.id(), projectName, item)))
                    .toList();
            createAndAssertForSpan(FeedbackScoreBatch.builder().scores(spanScores).build(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            traceResourceClient.deleteTrace(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteWithoutSpansScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = List.of(createTrace().toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .feedbackScores(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var traceScores = traces.stream()
                    .flatMap(trace -> trace.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    trace.id(), projectName, item)))
                    .toList();

            traceResourceClient.feedbackScores(traceScores, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            traceResourceClient.deleteTrace(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteWithoutScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = List.of(createTrace().toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(null)
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .feedbackScores(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            traceResourceClient.deleteTrace(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteWithoutSpans() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = List.of(createTrace().toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(null)
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);

            traceResourceClient.deleteTrace(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
        }

        @Test
        @DisplayName("when trace does not exist, then return no content")
        void delete__whenTraceDoesNotExist__thenNoContent() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var id = generator.generate();

            traceResourceClient.deleteTrace(id, workspaceName, apiKey);

            getAndAssertTraceNotFound(id, apiKey, workspaceName);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteTraces {

        @Test
        void deleteTraces() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var traceScores = traces.stream()
                    .flatMap(trace -> trace.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    trace.id(), projectName, item)))
                    .toList();

            traceResourceClient.feedbackScores(traceScores, apiKey, workspaceName);

            var spanScores = spans.stream()
                    .flatMap(span -> span.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    span.id(), projectName, item)))
                    .toList();
            createAndAssertForSpan(FeedbackScoreBatch.builder().scores(spanScores).build(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = BatchDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutSpansScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .threadId(null)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .feedbackScores(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var traceScores = traces.stream()
                    .flatMap(trace -> trace.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    trace.id(), projectName, item)))
                    .toList();

            traceResourceClient.feedbackScores(traceScores, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = BatchDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .feedbackScores(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = BatchDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutSpans() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);

            var request = BatchDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutTraces() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var request = factory.manufacturePojo(BatchDelete.class);
            traceResourceClient.deleteTraces(request, workspaceName, apiKey);
        }
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateTrace {

        private Trace trace;
        private UUID id;

        @BeforeEach
        void setUp() {
            trace = createTrace()
                    .toBuilder()
                    .endTime(null)
                    .output(null)
                    .startTime(Instant.now().minusSeconds(10))
                    .metadata(null)
                    .tags(null)
                    .projectId(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();

            id = create(trace, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace does not exist and id is invalid, then return 400")
        void when__traceDoesNotExistAndIdIsInvalid__thenReturn400() {
            var id = UUID.randomUUID().toString();

            var traceUpdate = TraceUpdate.builder()
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id)
                    .request()
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .method(HttpMethod.PATCH, Entity.json(traceUpdate))) {

                assertErrorResponse(actualResponse, "Trace id must be a version 7 UUID", HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("when trace does not exist, then return create it")
        void when__traceDoesNotExist__thenReturnCreateIt() {
            var id = factory.manufacturePojo(UUID.class);
            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualEntity = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.id()).isEqualTo(id);

            assertThat(actualEntity.input()).isEqualTo(traceUpdate.input());
            assertThat(actualEntity.output()).isEqualTo(traceUpdate.output());
            assertThat(actualEntity.endTime()).isEqualTo(traceUpdate.endTime());
            assertThat(actualEntity.metadata()).isEqualTo(traceUpdate.metadata());
            assertThat(actualEntity.tags()).isEqualTo(traceUpdate.tags());

            UUID projectId = getProjectId(traceUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.name()).isEmpty();
            assertThat(actualEntity.startTime()).isEqualTo(Instant.EPOCH);
            assertThat(actualEntity.projectId()).isEqualTo(projectId);
        }

        @Test
        @DisplayName("when trace update and insert are processed out of other, then return trace")
        void when__traceUpdateAndInsertAreProcessedOutOfOther__thenReturnTrace() {
            var id = factory.manufacturePojo(UUID.class);

            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var newTrace = createTrace().toBuilder()
                    .projectName(traceUpdate.projectName())
                    .id(id)
                    .build();

            create(newTrace, API_KEY, TEST_WORKSPACE);

            var actualEntity = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.id()).isEqualTo(id);

            assertThat(actualEntity.input()).isEqualTo(traceUpdate.input());
            assertThat(actualEntity.output()).isEqualTo(traceUpdate.output());
            assertThat(actualEntity.endTime()).isEqualTo(traceUpdate.endTime());
            assertThat(actualEntity.metadata()).isEqualTo(traceUpdate.metadata());
            assertThat(actualEntity.tags()).isEqualTo(traceUpdate.tags());

            assertThat(actualEntity.name()).isEqualTo(newTrace.name());
            assertThat(actualEntity.startTime()).isEqualTo(newTrace.startTime());
            assertThat(actualEntity.createdAt()).isBefore(newTrace.createdAt());
        }

        @Test
        @DisplayName("when multiple trace update and insert are processed out of other and concurrent, then return trace")
        void when__multipleTraceUpdateAndInsertAreProcessedOutOfOtherAndConcurrent__thenReturnTrace() {
            var id = factory.manufacturePojo(UUID.class);

            var projectName = UUID.randomUUID().toString();

            var traceUpdate1 = TraceUpdate.builder()
                    .metadata(JsonUtils.getJsonNodeFromString("{ \"metadata\": \"data\" }"))
                    .projectName(projectName)
                    .build();

            var startCreation = Instant.now();

            var traceUpdate2 = TraceUpdate.builder()
                    .input(JsonUtils.getJsonNodeFromString("{ \"input\": \"data2\"}"))
                    .tags(Set.of("tag1", "tag2"))
                    .projectName(projectName)
                    .build();

            var traceUpdate3 = TraceUpdate.builder()
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .endTime(Instant.now())
                    .projectName(projectName)
                    .build();

            var newTrace = createTrace().toBuilder()
                    .projectName(traceUpdate1.projectName())
                    .endTime(null)
                    .id(id)
                    .build();

            var create = Mono.fromRunnable(() -> create(newTrace, API_KEY, TEST_WORKSPACE));
            var update1 = Mono.fromRunnable(() -> runPatchAndAssertStatus(id, traceUpdate1, API_KEY, TEST_WORKSPACE));
            var update3 = Mono.fromRunnable(() -> runPatchAndAssertStatus(id, traceUpdate2, API_KEY, TEST_WORKSPACE));
            var update2 = Mono.fromRunnable(() -> runPatchAndAssertStatus(id, traceUpdate3, API_KEY, TEST_WORKSPACE));

            Flux.merge(update1, update2, create, update3).blockLast();

            var created = Instant.now();

            var actualEntity = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.id()).isEqualTo(id);

            assertThat(actualEntity.endTime()).isEqualTo(traceUpdate3.endTime());
            assertThat(actualEntity.input()).isEqualTo(traceUpdate2.input());
            assertThat(actualEntity.output()).isEqualTo(traceUpdate3.output());
            assertThat(actualEntity.metadata()).isEqualTo(traceUpdate1.metadata());
            assertThat(actualEntity.tags()).isEqualTo(traceUpdate2.tags());

            assertThat(actualEntity.name()).isEqualTo(newTrace.name());
            assertThat(actualEntity.startTime()).isEqualTo(newTrace.startTime());
            assertThat(actualEntity.createdAt()).isBetween(startCreation, created);
        }

        private void runPatchAndAssertStatus(UUID id, TraceUpdate traceUpdate3, String apiKey, String workspaceName) {
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(traceUpdate3))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }

        @Test
        @DisplayName("Success")
        void update() {
            var traceUpdate = TraceUpdate.builder()
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{ \"input\": \"data\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .metadata(JsonUtils.getJsonNodeFromString("{ \"metadata\": \"data\" }"))
                    .tags(Set.of("tag1", "tag2"))
                    .projectName(trace.projectName())
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualEntity = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.id()).isEqualTo(id);
            assertThat(actualEntity.input()).isEqualTo(traceUpdate.input());
            assertThat(actualEntity.output()).isEqualTo(traceUpdate.output());
            assertThat(actualEntity.metadata()).isEqualTo(traceUpdate.metadata());
            assertThat(actualEntity.tags()).isEqualTo(traceUpdate.tags());

            assertThat(actualEntity.projectId()).isNotNull();
            assertThat(actualEntity.name()).isEqualTo(trace.name());

            assertThat(actualEntity.endTime()).isEqualTo(traceUpdate.endTime());
            assertThat(actualEntity.startTime()).isEqualTo(trace.startTime());

            assertThat(actualEntity.createdAt()).isAfter(trace.createdAt());
            assertThat(actualEntity.lastUpdatedAt()).isAfter(traceUpdate.endTime());
        }

        @Test
        @DisplayName("when only output is not null, then accept update")
        void update__whenOutputIsNotNull__thenAcceptUpdate() {

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when end time is not null, then accept update")
        void update__whenEndTimeIsNotNull__thenAcceptUpdate() {

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .endTime(Instant.now())
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when input is not null, then accept update")
        void update__whenInputIsNotNull__thenAcceptUpdate() {

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .input(JsonUtils.getJsonNodeFromString("{ \"input\": \"data\"}"))
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when metadata is not null, then accept update")
        void update__whenMetadataIsNotNull__thenAcceptUpdate() {

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .metadata(JsonUtils.getJsonNodeFromString("{ \"metadata\": \"data\"}"))
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when tags is not null, then accept update")
        void update__whenTagsIsNotNull__thenAcceptUpdate() {

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .tags(Set.of("tag1", "tag2"))
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when tags is empty, then accept update")
        void update__whenTagsIsEmpty__thenAcceptUpdate() {

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .tags(Set.of())
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            var actualTrace = getAndAssert(trace, projectId, API_KEY,
                    TEST_WORKSPACE);

            assertThat(actualTrace.tags()).isNull();
        }

        @Test
        @DisplayName("when metadata is empty, then accept update")
        void update__whenMetadataIsEmpty__thenAcceptUpdate() {

            JsonNode metadata = JsonUtils.getJsonNodeFromString("{}");

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .metadata(metadata)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            var actualTrace = getAndAssert(trace.toBuilder().metadata(metadata).build(), projectId,
                    API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.metadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("when input is empty, then accept update")
        void update__whenInputIsEmpty__thenAcceptUpdate() {

            JsonNode input = JsonUtils.getJsonNodeFromString("{}");

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .input(input)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            var actualTrace = getAndAssert(trace.toBuilder().input(input).build(), projectId,
                    API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.input()).isEqualTo(input);
        }

        @Test
        @DisplayName("when output is empty, then accept update")
        void update__whenOutputIsEmpty__thenAcceptUpdate() {

            JsonNode output = JsonUtils.getJsonNodeFromString("{}");

            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .output(output)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            var actualTrace = getAndAssert(trace.toBuilder().output(output).build(), projectId,
                    API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.output()).isEqualTo(output);
        }

        @Test
        @DisplayName("when updating using projectId, then accept update")
        void update__whenUpdatingUsingProjectId__thenAcceptUpdate() {

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(projectId)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var updatedTrace = trace.toBuilder()
                    .projectId(projectId)
                    .metadata(traceUpdate.metadata())
                    .input(traceUpdate.input())
                    .output(traceUpdate.output())
                    .endTime(traceUpdate.endTime())
                    .tags(traceUpdate.tags())
                    .errorInfo(traceUpdate.errorInfo())
                    .threadId(traceUpdate.threadId())
                    .build();

            getAndAssert(updatedTrace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when updating trace to add thread id, then accept update")
        void update__whenUpdatingTraceToAddThreadId__thenAcceptUpdate() {

            var threadId = UUID.randomUUID().toString();

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            var traceUpdate = TraceUpdate.builder()
                    .threadId(threadId)
                    .projectId(projectId)
                    .build();

            runPatchAndAssertStatus(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualTrace.threadId()).isEqualTo(threadId);
        }

    }

    @Nested
    @DisplayName("Comment:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TraceComment {

        @Test
        void createCommentForNonExistingTraceFail() {
            traceResourceClient.generateAndCreateComment(generator.generate(), API_KEY, TEST_WORKSPACE, 404);
        }

        @Test
        void createAndGetComment() {

            // Create comment for existing trace
            UUID traceId = traceResourceClient.createTrace(createTrace(), API_KEY,
                    TEST_WORKSPACE);
            Comment expectedComment = traceResourceClient.generateAndCreateComment(traceId, API_KEY, TEST_WORKSPACE,
                    201);

            // Get created comment by id and assert
            Comment actualComment = traceResourceClient.getCommentById(expectedComment.id(), traceId, API_KEY,
                    TEST_WORKSPACE, 200);
            assertTraceComment(expectedComment, actualComment);
        }

        @Test
        void createAndUpdateComment() {
            // Create comment for existing trace
            UUID traceId = traceResourceClient.createTrace(createTrace(), API_KEY,
                    TEST_WORKSPACE);
            Comment expectedComment = traceResourceClient.generateAndCreateComment(traceId, API_KEY, TEST_WORKSPACE,
                    201);

            // Get created comment by id and assert
            Comment actualComment = traceResourceClient.getCommentById(expectedComment.id(), traceId, API_KEY,
                    TEST_WORKSPACE, 200);

            // Update existing comment
            String updatedText = factory.manufacturePojo(String.class);
            traceResourceClient.updateComment(updatedText, expectedComment.id(), API_KEY,
                    TEST_WORKSPACE, 204);

            // Get comment by id and assert it was updated
            Comment updatedComment = traceResourceClient.getCommentById(expectedComment.id(), traceId, API_KEY,
                    TEST_WORKSPACE, 200);
            assertUpdatedComment(actualComment, updatedComment, updatedText);
        }

        @Test
        void deleteComments() {
            // Create comment for existing trace
            UUID traceId = traceResourceClient.createTrace(createTrace(), API_KEY,
                    TEST_WORKSPACE);

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(traceId, API_KEY, TEST_WORKSPACE, 201))
                    .toList().reversed();

            // Check it was created
            expectedComments.forEach(
                    comment -> traceResourceClient.getCommentById(comment.id(), traceId, API_KEY, TEST_WORKSPACE, 200));

            // Delete comment
            BatchDelete request = BatchDelete.builder()
                    .ids(expectedComments.stream().map(Comment::id).collect(Collectors.toSet())).build();
            traceResourceClient.deleteComments(request, API_KEY, TEST_WORKSPACE);

            // Verify comments were actually deleted via get and update endpoints
            expectedComments.forEach(
                    comment -> traceResourceClient.getCommentById(comment.id(), traceId, API_KEY, TEST_WORKSPACE, 404));
            expectedComments.forEach(comment -> traceResourceClient.updateComment(factory.manufacturePojo(String.class),
                    comment.id(), API_KEY, TEST_WORKSPACE, 404));
        }

        @Test
        void getTraceWithComments() {
            UUID traceId = traceResourceClient.createTrace(createTrace(), API_KEY,
                    TEST_WORKSPACE);
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(traceId, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            Trace expectedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
            assertComments(expectedComments, expectedTrace.comments());
        }

        @Test
        void getTracePageWithComments() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .threadId(null)
                            .build())
                    .toList();
            var traceId = traces.getFirst().id();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(traceId, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            traces = traces.stream()
                    .map(trace -> trace.id() != traceId ? trace : trace.toBuilder().comments(expectedComments).build())
                    .toList();

            getAndAssertPage(1, traces.size(), projectName, null, List.of(), traces.reversed(), List.of(),
                    TEST_WORKSPACE,
                    API_KEY, null);
        }

        @Test
        void deleteTraceDeletesTraceAndSpanComments() {
            UUID traceId = traceResourceClient.createTrace(createTrace(), API_KEY,
                    TEST_WORKSPACE);
            List<Comment> expectedTraceComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(traceId, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            // Check that comments were created
            Trace expectedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
            assertComments(expectedTraceComments, expectedTrace.comments());

            // Create span for the trace and span comments
            var spanWithComments = createSpanWithCommentsAndAssert(traceId);

            traceResourceClient.deleteTrace(traceId, TEST_WORKSPACE, API_KEY);

            // Verify trace comments were actually deleted via get endpoint
            expectedTraceComments.forEach(
                    comment -> traceResourceClient.getCommentById(comment.id(), traceId, API_KEY, TEST_WORKSPACE, 404));

            // Verify span comments were actually deleted via get endpoint
            spanWithComments.getRight().forEach(
                    comment -> spanResourceClient.getCommentById(comment.id(), spanWithComments.getLeft(), API_KEY,
                            TEST_WORKSPACE, 404));
        }

        @Test
        void batchDeleteTracesDeletesTraceAndSpanComments() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .comments(null)
                            .threadId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(TEST_WORKSPACE, projectName, null, List.of(), traces, traces.reversed(), List.of(),
                    API_KEY);

            List<Comment> expectedTraceComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(traces.getFirst().id(), API_KEY,
                            TEST_WORKSPACE, 201))
                    .toList();

            // Check that comments were created
            Trace expectedTrace = traceResourceClient.getById(traces.getFirst().id(), TEST_WORKSPACE, API_KEY);
            assertComments(expectedTraceComments, expectedTrace.comments());

            // Create span for the trace and span comments
            var spanWithComments = createSpanWithCommentsAndAssert(traces.getFirst().id());

            var request = BatchDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, TEST_WORKSPACE, API_KEY);

            // Verify comments were actually deleted via get endpoint
            expectedTraceComments
                    .forEach(comment -> traceResourceClient.getCommentById(comment.id(), traces.getFirst().id(),
                            API_KEY, TEST_WORKSPACE, 404));

            // Verify span comments were actually deleted via get endpoint
            spanWithComments.getRight().forEach(
                    comment -> spanResourceClient.getCommentById(comment.id(), spanWithComments.getLeft(), API_KEY,
                            TEST_WORKSPACE, 404));
        }

        private Pair<UUID, List<Comment>> createSpanWithCommentsAndAssert(UUID traceId) {
            // Create span for the trace and span comments
            UUID spanId = spanResourceClient.createSpan(
                    factory.manufacturePojo(Span.class).toBuilder().traceId(traceId).build(), API_KEY,
                    TEST_WORKSPACE);
            List<Comment> expectedSpanComments = IntStream.range(0, 5)
                    .mapToObj(i -> spanResourceClient.generateAndCreateComment(spanId, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            // Check that span comments were created
            Span actualSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY);
            assertComments(expectedSpanComments, actualSpan.comments());

            return Pair.of(spanId, expectedSpanComments);
        }
    }

    @Nested
    @DisplayName("Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TraceFeedback {

        Stream<Arguments> invalidRequestBodyParams() {
            return Stream.of(
                    arguments(factory.manufacturePojo(FeedbackScore.class).toBuilder().name(null).build(),
                            "name must not be blank"),
                    arguments(factory.manufacturePojo(FeedbackScore.class).toBuilder().name("").build(),
                            "name must not be blank"),
                    arguments(factory.manufacturePojo(FeedbackScore.class).toBuilder().value(null).build(),
                            "value must not be null"),
                    arguments(
                            factory.manufacturePojo(FeedbackScore.class).toBuilder()
                                    .value(BigDecimal.valueOf(-999999999.9999999991)).build(),
                            "value must be greater than or equal to -999999999.999999999"),
                    arguments(
                            factory.manufacturePojo(FeedbackScore.class).toBuilder()
                                    .value(BigDecimal.valueOf(999999999.9999999991)).build(),
                            "value must be less than or equal to 999999999.999999999"));
        }

        @Test
        @DisplayName("when trace does not exist, then return not found")
        void feedback__whenTraceDoesNotExist__thenReturnNotFound() {
            var id = generator.generate();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(factory.manufacturePojo(FeedbackScore.class)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                        .isEqualTo("Trace id: %s not found".formatted(id));
            }
        }

        @ParameterizedTest
        @MethodSource("invalidRequestBodyParams")
        @DisplayName("when feedback request body is invalid, then return bad request")
        void feedback__whenFeedbackRequestBodyIsInvalid__thenReturnBadRequest(
                FeedbackScore feedbackScore, String errorMessage) {
            var id = generator.generate();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id.toString())
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(feedbackScore))) {

                assertErrorResponse(actualResponse, errorMessage, HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        @Test
        @DisplayName("when feedback without category name or reason, then return no content")
        void feedback__whenFeedbackWithoutCategoryNameOrReason__thenReturnNoContent() {
            var trace = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .categoryName(null)
                    .reason(null)
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            create(id, score, TEST_WORKSPACE, API_KEY);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder().feedbackScores(List.of(score)).build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {
            var trace = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            create(id, score, TEST_WORKSPACE, API_KEY);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            trace = trace.toBuilder().feedbackScores(List.of(score)).build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when overriding feedback value, then return no content")
        void feedback__whenOverridingFeedbackValue__thenReturnNoContent() {
            var trace = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScore.class);
            create(id, score, TEST_WORKSPACE, API_KEY);

            var newScore = score.toBuilder().value(BigDecimal.valueOf(2)).build();
            create(id, newScore, TEST_WORKSPACE, API_KEY);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder().feedbackScores(List.of(newScore)).build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }
    }

    @Nested
    @DisplayName("Delete Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteTraceFeedback {

        @Test
        @DisplayName("when trace does not exist, then return no content")
        void deleteFeedback__whenTraceDoesNotExist__thenReturnNoContent() {

            var id = generator.generate();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .post(Entity.json(DeleteFeedbackScore.builder().name("name").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }

        @Test
        @DisplayName("Success")
        void deleteFeedback() {

            var trace = createTrace();
            var id = create(trace, API_KEY, TEST_WORKSPACE);
            var score = FeedbackScore.builder()
                    .name("name")
                    .value(BigDecimal.valueOf(1))
                    .source(ScoreSource.UI)
                    .build();
            create(id, score, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(DeleteFeedbackScore.builder().name("name").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualEntity = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);
            assertThat(actualEntity.feedbackScores()).isNull();
        }

    }

    @Nested
    @DisplayName("Batch Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchTracesFeedback {

        Stream<Arguments> invalidRequestBodyParams() {
            return Stream.of(
                    arguments(FeedbackScoreBatch.builder().build(), "scores must not be null"),
                    arguments(FeedbackScoreBatch.builder().scores(List.of()).build(),
                            "scores size must be between 1 and 1000"),
                    arguments(FeedbackScoreBatch.builder().scores(
                            IntStream.range(0, 1001)
                                    .mapToObj(__ -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                            .projectName(DEFAULT_PROJECT).build())
                                    .toList())
                            .build(), "scores size must be between 1 and 1000"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                            .projectName(DEFAULT_PROJECT).name(null).build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                            .projectName(DEFAULT_PROJECT).name("").build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                            .projectName(DEFAULT_PROJECT).value(null).build()))
                                    .build(),
                            "scores[0].value must not be null"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                            .projectName(DEFAULT_PROJECT)
                                            .value(BigDecimal.valueOf(-999999999.9999999991))
                                            .build()))
                                    .build(),
                            "scores[0].value must be greater than or equal to -999999999.999999999"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                            .projectName(DEFAULT_PROJECT)
                                            .value(BigDecimal.valueOf(999999999.9999999991))
                                            .build()))
                                    .build(),
                            "scores[0].value must be less than or equal to 999999999.999999999"));
        }

        @Test
        @DisplayName("Success")
        void feedback() {
            var trace1 = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .build();
            var id1 = create(trace1, API_KEY, TEST_WORKSPACE);
            var trace2 = createTrace()
                    .toBuilder()
                    .projectName(UUID.randomUUID().toString())
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id2 = create(trace2, API_KEY, TEST_WORKSPACE);

            var score1 = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id1)
                    .projectName(trace1.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score2 = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id2)
                    .name("hallucination")
                    .projectName(trace2.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score3 = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id1)
                    .name("hallucination")
                    .projectName(trace1.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            traceResourceClient.feedbackScores(List.of(score1, score2, score3), API_KEY, TEST_WORKSPACE);

            var projectId1 = getProjectId(trace1.projectName(), TEST_WORKSPACE, API_KEY);
            var projectId2 = getProjectId(trace2.projectName(), TEST_WORKSPACE, API_KEY);
            trace1 = trace1.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score1, score3)))
                    .build();
            trace2 = trace2.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score2)))
                    .build();
            getAndAssert(trace1, projectId1, API_KEY, TEST_WORKSPACE);
            getAndAssert(trace2, projectId2, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when workspace is specified, then return no content")
        void feedback__whenWorkspaceIsSpecified__thenReturnNoContent() {
            var projectName = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedTrace1 = createTrace().toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();

            var id1 = create(expectedTrace1, apiKey, workspaceName);

            var expectedTrace2 = createTrace().toBuilder()
                    .projectName(projectName)
                    .build();
            var id2 = create(expectedTrace2, apiKey, workspaceName);

            var score1 = factory.manufacturePojo(FeedbackScoreBatchItem.class)
                    .toBuilder()
                    .id(id1)
                    .projectName(expectedTrace1.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score2 = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id2)
                    .name("hallucination")
                    .projectName(expectedTrace2.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score3 = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id1)
                    .name("hallucination")
                    .projectName(expectedTrace1.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            traceResourceClient.feedbackScores(List.of(score1, score2, score3), apiKey, workspaceName);

            var projectId1 = getProjectId(DEFAULT_PROJECT, workspaceName, apiKey);
            var projectId2 = getProjectId(projectName, workspaceName, apiKey);
            expectedTrace1 = expectedTrace1.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score1, score3)))
                    .build();
            expectedTrace2 = expectedTrace2.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score2)))
                    .build();
            getAndAssert(expectedTrace1, projectId1, apiKey, workspaceName);
            getAndAssert(expectedTrace2, projectId2, apiKey, workspaceName);
        }

        @ParameterizedTest
        @MethodSource("invalidRequestBodyParams")
        @DisplayName("when batch request is invalid, then return bad request")
        void feedback__whenBatchRequestIsInvalid__thenReturnBadRequest(FeedbackScoreBatch batch, String errorMessage) {
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(batch))) {

                assertErrorResponse(actualResponse, errorMessage, HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        @Test
        @DisplayName("when feedback without category name or reason, then return no content")
        void feedback__whenFeedbackWithoutCategoryNameOrReason__thenReturnNoContent() {
            var trace = createTrace()
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(trace.projectName())
                    .categoryName(null)
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .reason(null)
                    .build();
            traceResourceClient.feedbackScores(List.of(score), API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score)))
                    .build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var expectedTrace = createTrace()
                    .toBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .build();
            var id = create(expectedTrace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedTrace.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            traceResourceClient.feedbackScores(List.of(score), API_KEY, TEST_WORKSPACE);

            expectedTrace = expectedTrace.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score)))
                    .build();
            getAndAssert(
                    expectedTrace,
                    getProjectId(expectedTrace.projectName(), TEST_WORKSPACE, API_KEY),
                    API_KEY,
                    TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when overriding feedback value, then return no content")
        void feedback__whenOverridingFeedbackValue__thenReturnNoContent() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var trace = createTrace()
                    .toBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(trace.projectName())
                    .build();
            traceResourceClient.feedbackScores(List.of(score), API_KEY, TEST_WORKSPACE);

            var newScore = score.toBuilder().value(factory.manufacturePojo(BigDecimal.class)).build();
            traceResourceClient.feedbackScores(List.of(newScore), API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(newScore)))
                    .build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace does not exist, then return no content and create score")
        void feedback__whenTraceDoesNotExist__thenReturnNoContentAndCreateScore() {
            var id = generator.generate();
            var score = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(DEFAULT_PROJECT)
                    .build();

            traceResourceClient.feedbackScores(List.of(score), API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback trace batch has max size, then return no content and create scores")
        void feedback__whenFeedbackSpanBatchHasMaxSize__thenReturnNoContentAndCreateScores() {
            var expectedTrace = createTrace().toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();
            var id = create(expectedTrace, API_KEY, TEST_WORKSPACE);

            var scores = IntStream.range(0, 1000)
                    .mapToObj(__ -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .id(id)
                            .build())
                    .toList();
            traceResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback trace id is not valid, then return 400")
        void feedback__whenFeedbackTraceIdIsNotValid__thenReturn400() {
            var score = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(UUID.randomUUID())
                    .projectName(DEFAULT_PROJECT)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score))))) {

                assertErrorResponse(actualResponse, "trace id must be a version 7 UUID", 400);
            }
        }

        @ParameterizedTest
        @MethodSource
        void feedback__whenLinkingByProjectName__thenReturnNoContent(Function<String, String> projectNameModifier) {

            String projectName = UUID.randomUUID().toString();

            createProject(projectName.toUpperCase(), TEST_WORKSPACE, API_KEY);

            var traces = IntStream.range(0, 10)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectId(null)
                            .projectName(projectNameModifier.apply(projectName))
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var scores = traces.stream()
                    .map(trace -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(trace.id())
                            .projectName(projectNameModifier.apply(projectName))
                            .build())
                    .toList();

            traceResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);

            var expectedTracesWithScores = traces.stream()
                    .map(trace -> {
                        FeedbackScoreBatchItem feedbackScoreBatchItem = scores.stream()
                                .filter(score -> score.id().equals(trace.id()))
                                .findFirst()
                                .orElseThrow();

                        return trace.toBuilder()
                                .feedbackScores(List.of(mapFeedbackScore(feedbackScoreBatchItem)))
                                .build();
                    })
                    .toList();

            getAndAssertPage(TEST_WORKSPACE, projectName, null, List.of(), expectedTracesWithScores.reversed(),
                    expectedTracesWithScores.reversed(), List.of(), API_KEY);
        }

        Stream<Arguments> feedback__whenLinkingByProjectName__thenReturnNoContent() {
            return getProjectNameModifierArgs();
        }
    }

    private FeedbackScore mapFeedbackScore(FeedbackScoreBatchItem feedbackScoreBatchItem) {
        return FeedbackScore.builder()
                .name(feedbackScoreBatchItem.name())
                .value(feedbackScoreBatchItem.value())
                .source(feedbackScoreBatchItem.source())
                .categoryName(feedbackScoreBatchItem.categoryName())
                .reason(feedbackScoreBatchItem.reason())
                .build();
    }

    @Nested
    @DisplayName("Get Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackScoreNames {

        @Test
        @DisplayName("when get feedback score names, then return feedback score names")
        void getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames() {

            // given
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // when
            String projectName = UUID.randomUUID().toString();

            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            Project project = projectResourceClient.getProject(projectId, apiKey, workspaceName);

            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);
            List<String> otherNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            // Create multiple values feedback scores
            List<String> multipleValuesFeedbackScores = names.subList(0, names.size() - 1);

            traceResourceClient.createMultiValueScores(multipleValuesFeedbackScores, project, apiKey, workspaceName);

            traceResourceClient.createMultiValueScores(List.of(names.getLast()), project, apiKey, workspaceName);

            // Create unexpected feedback scores
            var unexpectedProject = factory.manufacturePojo(Project.class);

            traceResourceClient.createMultiValueScores(otherNames, unexpectedProject, apiKey, workspaceName);

            fetchAndAssertResponse(names, projectId, apiKey, workspaceName);
        }
    }

    @Nested
    @DisplayName("Trace threads Delete")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TraceThreadsDelete {

        @Test
        @DisplayName("when trace thread is deleted, then return no content")
        void deleteTraceThread() {
            var trace = createTrace().toBuilder()
                    .threadId(UUID.randomUUID().toString())
                    .build();

            var id = create(trace, API_KEY, TEST_WORKSPACE);

            traceResourceClient.deleteTraceThreads(List.of(trace.threadId()), trace.projectName(), null, API_KEY,
                    TEST_WORKSPACE);

            getAndAssertTraceNotFound(id, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace thread id is present in multiple projects and workspaces, then return no content and only delete the trace thread in the specified project and workspace")
        void deleteTraceThread__whenTraceThreadIdIsPresentInMultipleProjectsAndWorkspaces__thenReturnNoContentAndOnlyDeleteTheTraceThreadInTheSpecifiedProjectAndWorkspace() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String threadId = UUID.randomUUID().toString();

            var trace = createTrace().toBuilder()
                    .threadId(threadId)
                    .build();

            var traceFromOtherProject = createTrace().toBuilder()
                    .threadId(threadId)
                    .build();

            var traceFromOtherWorkspace = createTrace().toBuilder()
                    .threadId(threadId)
                    .build();

            traceResourceClient.batchCreateTraces(List.of(trace, traceFromOtherProject), apiKey, workspaceName);
            traceResourceClient.batchCreateTraces(List.of(traceFromOtherWorkspace), API_KEY, TEST_WORKSPACE);

            UUID otherProjectId = getProjectId(traceFromOtherProject.projectName(), workspaceName, apiKey);
            UUID otherWorkspaceProjectId = getProjectId(traceFromOtherWorkspace.projectName(), TEST_WORKSPACE, API_KEY);

            traceResourceClient.deleteTraceThreads(List.of(trace.threadId()), trace.projectName(), null, apiKey,
                    workspaceName);

            getAndAssertTraceNotFound(trace.id(), apiKey, workspaceName);
            getAndAssert(traceFromOtherProject, otherProjectId, apiKey, workspaceName);
            getAndAssert(traceFromOtherWorkspace, otherWorkspaceProjectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace thread does not exist, then return no content")
        void deleteTraceThread__whenTraceDoesNotExist__thenReturnNotFound() {
            var trace = createTrace();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            traceResourceClient.deleteTraceThreads(List.of(UUID.randomUUID().toString()), trace.projectName(), null,
                    API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when project neither project name or project id are specified, then return bad request")
        void deleteTraceThread__whenProjectNeitherProjectNameOrProjectIdAreSpecified__thenReturnBadRequest() {
            var trace = createTrace().toBuilder()
                    .threadId(UUID.randomUUID().toString())
                    .build();

            var id = create(trace, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("threads")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(DeleteTraceThreads.builder().threadIds(List.of(trace.threadId())).build()))) {

                assertErrorResponse(actualResponse,
                        "The request body must provide either a project_name or a project_id",
                        HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        Stream<Arguments> deleteTraceThread__whenThreadIdsIsEmpty__thenReturnBadRequest() {
            return Stream.of(
                    arguments(List.of(), "threadIds size must be between 1 and 1000"),
                    arguments(null, "threadIds must not be empty"),
                    arguments(List.of(""), "threadIds[0].<list element> must not be blank"),
                    arguments(Lists.newArrayList((String) null), "threadIds[0].<list element> must not be blank"),
                    arguments(List.of(" "), "threadIds[0].<list element> must not be blank"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when thread ids is empty, then return bad request")
        void deleteTraceThread__whenThreadIdsIsEmpty__thenReturnBadRequest(List<String> threadIds,
                String errorMessage) {
            Trace trace = createTrace().toBuilder()
                    .threadId(UUID.randomUUID().toString())
                    .build();

            create(trace, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("threads")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity
                            .json(DeleteTraceThreads.builder().projectId(projectId).threadIds(threadIds).build()))) {

                assertErrorResponse(actualResponse, errorMessage, HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }
    }

    @Nested
    @DisplayName("Get Trace Threads")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetTraceThread {

        @Test
        @DisplayName("when trace thread is retrieved, then return trace thread")
        void getTraceThread() {

            var threadId = UUID.randomUUID().toString();
            var projectName = UUID.randomUUID().toString();

            var traces = IntStream.range(0, 5)
                    .mapToObj(i -> createTrace().toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var expectedTraces = traceResourceClient.getByProjectName(projectName, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            var actualThread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, TEST_WORKSPACE);

            var expectedThread = getExpectedThreads(expectedTraces, projectId, threadId);

            assertThat(List.of(actualThread))
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS_TRACES)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                    .isEqualTo(expectedThread);
        }

        @Test
        @DisplayName("when trace thread does not exist, then return not found")
        void getTraceThread__whenTraceThreadDoesNotExist__thenReturnNotFound() {
            var threadId = UUID.randomUUID().toString();
            var projectName = UUID.randomUUID().toString();

            var traces = IntStream.range(0, 5)
                    .mapToObj(i -> createTrace().toBuilder()
                            .threadId(UUID.randomUUID().toString())
                            .projectName(projectName)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = traceResourceClient.getTraceThreadResponse(threadId, projectId, API_KEY,
                    TEST_WORKSPACE)) {

                String message = "Trace Thread id: %s not found".formatted(threadId);

                TraceAssertions.assertErrorResponse(actualResponse, message, HttpStatus.SC_NOT_FOUND);
            }
        }

    }

    private void assertErrorResponse(Response actualResponse, String message, int expected) {
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expected);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(message);
    }

    private void fetchAndAssertResponse(List<String> expectedNames, UUID projectId, String apiKey,
            String workspaceName) {

        WebTarget webTarget = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("feedback-scores")
                .path("names");

        webTarget = webTarget.queryParam("project_id", projectId);

        try (var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // then
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            var actualEntity = actualResponse.readEntity(FeedbackScoreNames.class);

            assertFeedbackScoreNames(actualEntity, expectedNames);
        }
    }

    private void createAndAssertForSpan(FeedbackScoreBatch request, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(URL_TEMPLATE_SPANS.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private int setupTracesForWorkspace(String workspaceName, String workspaceId, String okApikey) {
        mockTargetWorkspace(okApikey, workspaceName, workspaceId);

        var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                .stream()
                .map(t -> t.toBuilder()
                        .projectId(null)
                        .projectName(DEFAULT_PROJECT)
                        .feedbackScores(null)
                        .build())
                .toList();

        traces.forEach(trace -> create(trace, okApikey, workspaceName));

        return traces.size();
    }

    private Map<String, Long> aggregateSpansUsage(List<Span> spans) {
        return spans.stream()
                .flatMap(span -> span.usage().entrySet().stream())
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), Long.valueOf(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }
}
