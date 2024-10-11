package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.TracesDelete;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
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
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
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
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Testcontainers(parallel = true)
@DisplayName("Traces Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracesResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/traces";
    private static final String URL_TEMPLATE_SPANS = "%s/v1/private/spans";
    private static final String[] IGNORED_FIELDS_TRACES = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy"};
    private static final String[] IGNORED_FIELDS_SPANS = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy"};
    private static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();
    private static final Random RANDOM = new Random();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        MYSQL_CONTAINER.start();
        CLICK_HOUSE_CONTAINER.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;

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
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID getProjectId(String projectName, String workspaceName, String apiKey) {
        return client.target("%s/v1/private/projects".formatted(baseURI))
                .queryParam("name", projectName)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()
                .readEntity(Project.ProjectPage.class)
                .content()
                .stream()
                .findFirst()
                .orElseThrow()
                .id();
    }

    private UUID createProject(String projectName, String workspaceName, String apiKey) {
        try (Response response = client.target("%s/v1/private/projects".formatted(baseURI))
                .queryParam("name", projectName)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(Project.builder().name(projectName).build()))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(201);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    @Nested
    @DisplayName("Api Key Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApiKey {

        private final String fakeApikey = UUID.randomUUID().toString();
        private final String okApikey = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(okApikey, true),
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
        @DisplayName("create trace, when api key is present, then return proper response")
        void create__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var trace = factory.manufacturePojo(Trace.class)
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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update trace, when api key is present, then return proper response")
        void update__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var trace = factory.manufacturePojo(Trace.class)
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete trace, when api key is present, then return proper response")
        void delete__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var trace = factory.manufacturePojo(Trace.class)
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get traces, when api key is present, then return proper response")
        void get__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback, when api key is present, then return proper response")
        void feedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var trace = factory.manufacturePojo(Trace.class)
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete feedback, when api key is present, then return proper response")
        void deleteFeedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            var trace = factory.manufacturePojo(Trace.class);

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback batch, when api key is present, then return proper response")
        void feedbackBatch__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {

            var trace = factory.manufacturePojo(Trace.class);
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);

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

                assertExpectedResponseWithoutABody(expected, actualResponse);
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
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create trace, when session token is present, then return proper response")
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = factory.manufacturePojo(Trace.class)
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

            var trace = factory.manufacturePojo(Trace.class)
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete trace, when session token is present, then return proper response")
        void delete__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var trace = factory.manufacturePojo(Trace.class)
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
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

            var trace = factory.manufacturePojo(Trace.class)
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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete feedback, when session token is present, then return proper response")
        void deleteFeedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean expected, String workspaceName) {
            var trace = factory.manufacturePojo(Trace.class);

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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Trace feedback batch, when session token is present, then return proper response")
        void feedbackBatch__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var trace = factory.manufacturePojo(Trace.class);

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

                assertExpectedResponseWithoutABody(expected, actualResponse);
            }

        }
    }

    private void assertExpectedResponseWithoutABody(boolean expected, Response actualResponse) {
        if (expected) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        } else {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
            assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                    .isEqualTo(UNAUTHORIZED_RESPONSE);
        }
    }

    @Nested
    @DisplayName("Find traces:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraces {

        @Test
        @DisplayName("when project name and project id are null, then return bad request")
        void getByProjectName__whenProjectNameAndIdAreNull__thenReturnBadRequest() {

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);

            var actualEntity = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualEntity.getMessage())
                    .isEqualTo("Either 'project_name' or 'project_id' query params must be provided");
        }

        @Test
        void findWithUsage() {
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            batchCreateTracesAndAssert(traces, API_KEY, TEST_WORKSPACE);

            var traceIdToSpansMap = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
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
            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), traces, traces.reversed(), List.of(), API_KEY);
        }

        @Test
        void findWithoutUsage() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
        }

        @Test
        @DisplayName("when project name is not empty, then return traces by project name")
        void getByProjectName__whenProjectNameIsNotEmpty__thenReturnTracesByProjectName() {

            var projectName = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            for (int i = 0; i < 15; i++) {
                create(factory.manufacturePojo(Trace.class)
                        .toBuilder()
                        .id(null)
                        .projectName(projectName)
                        .endTime(null)
                        .output(null)
                        .createdAt(null)
                        .lastUpdatedAt(null)
                        .projectId(null)
                        .tags(null)
                        .feedbackScores(null)
                        .build(), apiKey, workspaceName);
            }

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", projectName)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualEntities = actualResponse.readEntity(Trace.TracePage.class);
            assertThat(actualEntities.page()).isEqualTo(1);
            assertThat(actualEntities.total()).isEqualTo(15);
            assertThat(actualEntities.size()).isEqualTo(10);
            assertThat(actualEntities.content()).hasSize(10);
        }

        @Test
        @DisplayName("when project id is not empty, then return traces by project id")
        void getByProjectName__whenProjectIdIsNotEmpty__thenReturnTracesByProjectId() {

            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var projectName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            create(factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .projectName(projectName)
                    .id(null)
                    .endTime(null)
                    .output(null)
                    .createdAt(null)
                    .lastUpdatedAt(null)
                    .projectId(null)
                    .tags(null)
                    .feedbackScores(null)
                    .build(), apiKey, workspaceName);

            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("workspace_name", workspaceName)
                    .queryParam("project_id", projectId)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualEntities = actualResponse.readEntity(Trace.TracePage.class);
            assertThat(actualEntities.page()).isEqualTo(1);
            assertThat(actualEntities.total()).isEqualTo(1);
            assertThat(actualEntities.size()).isEqualTo(1);
            assertThat(actualEntities.content()).hasSize(1);
        }

        @Test
        @DisplayName("when filtering by workspace name, then return traces filtered")
        void getByProjectName__whenFilterWorkspaceName__thenReturnTracesFiltered() {

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
                            .feedbackScores(null)
                            .build())
                    .toList();

            var traces2 = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName1)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            traces1.forEach(trace -> create(trace, apiKey1, workspaceName1));
            traces2.forEach(trace -> create(trace, apiKey2, workspaceName2));

            getAndAssertPage(1, traces2.size() + traces1.size(), projectName1, List.of(), traces1.reversed(),
                    traces2.reversed(), workspaceName1, apiKey1);
            getAndAssertPage(1, traces2.size() + traces1.size(), projectName1, List.of(), traces2.reversed(),
                    traces1.reversed(), workspaceName2, apiKey2);

        }

        @Test
        void getByProjectName__whenFilterIdAndNameEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(Operator.EQUAL)
                            .value(traces.getFirst().id().toString())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.EQUAL)
                            .value(traces.getFirst().name())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().name().toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameStartsWith__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.STARTS_WITH)
                    .value(traces.getFirst().name().substring(0, traces.getFirst().name().length() - 4).toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameEndsWith__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.ENDS_WITH)
                    .value(traces.getFirst().name().substring(3).toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameContains__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.CONTAINS)
                    .value(traces.getFirst().name().substring(2, traces.getFirst().name().length() - 3).toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameNotContains__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traceName = generator.generate().toString();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .name(traceName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .name(generator.generate().toString())
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.NOT_CONTAINS)
                    .value(traceName.toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeGreaterThan__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value(Instant.now().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeGreaterThanEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeLessThan__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeLessThanEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterEndTimeEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.END_TIME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().endTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterInputEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.INPUT)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().input().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterOutputEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.OUTPUT)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().output().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualString__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("$.model[0].version")
                    .value("OPENAI, CHAT-GPT 4.0")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualNumber__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("2023")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualBoolean__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
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
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("TRUE")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualNull__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("NULL")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsString__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].version")
                    .value("CHAT-GPT")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsNumber__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":\"two thousand twenty " +
                                    "four\",\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("02")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsBoolean__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
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
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("TRU")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsNull__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("NUL")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanNumber__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2020," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("2023")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanString__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].version")
                    .value("a")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanBoolean__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanNull__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanNumber__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2026," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("2025")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanString__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].version")
                    .value("z")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanBoolean__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanNull__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterTagsContains__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(traces.getFirst().tags().stream()
                            .toList()
                            .get(2)
                            .substring(0, traces.getFirst().name().length() - 4)
                            .toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        static Stream<Arguments> getByProjectName__whenFilterUsage__thenReturnTracesFiltered() {
            return Stream.of(
                    arguments("completion_tokens", TraceField.USAGE_COMPLETION_TOKENS),
                    arguments("prompt_tokens", TraceField.USAGE_PROMPT_TOKENS),
                    arguments("total_tokens", TraceField.USAGE_TOTAL_TOKENS));
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnTracesFiltered")
        void getByProjectName__whenFilterUsageEqual__thenReturnTracesFiltered(String usageKey, Field field) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var otherUsageValue = RANDOM.nextInt();
            var usageValue = RANDOM.nextInt();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, (long) otherUsageValue))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, (long) usageValue))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, otherUsageValue))
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, usageValue))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(factory.manufacturePojo(Trace.class));
            unrelatedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());
            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnTracesFiltered")
        void getByProjectName__whenFilterUsageGreaterThan__thenReturnTracesFiltered(String usageKey, Field field) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123L))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456L))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 123))
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(factory.manufacturePojo(Trace.class));
            unrelatedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.GREATER_THAN)
                    .value("123")
                    .build());
            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnTracesFiltered")
        void getByProjectName__whenFilterUsageGreaterThanEqual__thenReturnTracesFiltered(String usageKey, Field field) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123L))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456L))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 123))
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(factory.manufacturePojo(Trace.class));
            unrelatedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());
            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnTracesFiltered")
        void getByProjectName__whenFilterUsageLessThan__thenReturnTracesFiltered(String usageKey, Field field) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456L))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123L))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 456))
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(factory.manufacturePojo(Trace.class));
            unrelatedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.LESS_THAN)
                    .value("456")
                    .build());
            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnTracesFiltered")
        void getByProjectName__whenFilterUsageLessThanEqual__thenReturnTracesFiltered(String usageKey, Field field) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456L))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123L))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 456))
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(factory.manufacturePojo(Trace.class));
            unrelatedTraces.forEach(trace -> create(trace, apiKey, workspaceName));

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());
            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
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
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .key(traces.getFirst().feedbackScores().get(1).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(1).value().toString())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresGreaterThan__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
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
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
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
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresGreaterThanEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
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
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
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
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresLessThan__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
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
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
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

            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresLessThanEqual__thenReturnTracesFiltered() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
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
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedTraces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
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
            getAndAssertPage(workspaceName, projectName, filters, traces, expectedTraces, unexpectedTraces, apiKey);
        }

        static Stream<Filter> getByProjectName__whenFilterInvalidOperatorForFieldType__thenReturn400() {
            return Stream.of(
                    TraceFilter.builder()
                            .field(TraceField.START_TIME)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.END_TIME)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.START_TIME)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.END_TIME)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.START_TIME)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.END_TIME)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.START_TIME)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.END_TIME)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.OUTPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.OUTPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.OUTPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.OUTPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build());
        }

        @ParameterizedTest
        @MethodSource
        void getByProjectName__whenFilterInvalidOperatorForFieldType__thenReturn400(Filter filter) {

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var filters = List.of(filter);
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", projectName)
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }

        static Stream<Filter> getByProjectName__whenFilterInvalidValueOrKeyForFieldType__thenReturn400() {
            return Stream.of(
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(Operator.EQUAL)
                            .value(" ")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.EQUAL)
                            .value("")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .operator(Operator.EQUAL)
                            .value("")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.OUTPUT)
                            .operator(Operator.EQUAL)
                            .value("")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.START_TIME)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.END_TIME)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .key(null)
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .operator(Operator.EQUAL)
                            .value("")
                            .key(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .value("123.456")
                            .key(null)
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .value("")
                            .key("hallucination")
                            .build());
        }

        @ParameterizedTest
        @MethodSource
        void getByProjectName__whenFilterInvalidValueOrKeyForFieldType__thenReturn400(Filter filter) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
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
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var filters = List.of(filter);
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("workspace_name", workspaceName)
                    .queryParam("project_name", projectName)
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);

            var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualError).isEqualTo(expectedError);
        }
    }

    private void getAndAssertPage(String workspaceName, String projectName, List<? extends Filter> filters,
            List<Trace> traces,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String apiKey) {
        int page = 1;
        int size = traces.size() + expectedTraces.size() + unexpectedTraces.size();
        getAndAssertPage(page, size, projectName, filters, expectedTraces, unexpectedTraces,
                workspaceName, apiKey);
    }

    private void getAndAssertPage(int page, int size, String projectName, List<? extends Filter> filters,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String workspaceName, String apiKey) {
        var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParam("project_name", projectName)
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

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

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

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

    private static void assertIgnoredFields(Trace actualTrace, Trace expectedTrace) {
        assertThat(actualTrace.projectId()).isNotNull();
        assertThat(actualTrace.projectName()).isNull();
        assertThat(actualTrace.createdAt()).isAfter(expectedTrace.createdAt());
        assertThat(actualTrace.lastUpdatedAt()).isAfter(expectedTrace.lastUpdatedAt());
        assertThat(actualTrace.createdBy()).isEqualTo(USER);
        assertThat(actualTrace.lastUpdatedBy()).isEqualTo(USER);
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
    }

    private void assertIgnoredFieldsSpans(List<Span> actualSpans, List<Span> expectedSpans) {
        for (int i = 0; i < actualSpans.size(); i++) {
            var actualSpan = actualSpans.get(i);
            var expectedSpan = expectedSpans.get(i);
            assertThat(actualSpan.projectId()).isNotNull();
            assertThat(actualSpan.projectName()).isNull();
            assertThat(actualSpan.createdAt()).isAfter(expectedSpan.createdAt());
            assertThat(actualSpan.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
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
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var span = factory.manufacturePojo(Span.class);
            var usage = Stream.concat(
                    Map.of("completion_tokens", 2 * 5L, "prompt_tokens", 3 * 5L + 3, "total_tokens", 4 * 5L)
                            .entrySet().stream(),
                    span.usage().entrySet().stream())
                    .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().longValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            var trace = factory.manufacturePojo(Trace.class)
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

        @Test
        void getTraceWithoutUsage() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var trace = factory.manufacturePojo(Trace.class)
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

    private UUID create(Trace trace, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            var actualId = TestUtils.getIdFromLocation(actualResponse.getLocation());

            if (trace.id() != null) {
                assertThat(actualId).isEqualTo(trace.id());
            }
            return actualId;
        }
    }

    private void create(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(entityId.toString())
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(score))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private Trace getAndAssert(Trace expectedTrace, UUID projectId, String apiKey, String workspaceName) {
        var actualResponse = getById(expectedTrace.id(), workspaceName, apiKey);
        var actualTrace = actualResponse.readEntity(Trace.class);

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

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                .allMatch(error -> Pattern.matches("Trace not found", error));
    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateTrace {

        @Test
        @DisplayName("Success")
        void createTrace() {
            var id = generator.generate();
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(id)
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(trace))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                var actualId = TestUtils.getIdFromLocation(actualResponse.getLocation());
                assertThat(actualId).isEqualTo(id);
            }

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when creating traces with different workspaces names, then return created traces")
        void create__whenCreatingTracesWithDifferentWorkspacesNames__thenReturnCreatedTraces() {
            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var trace1 = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var trace2 = factory.manufacturePojo(Trace.class)
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
        void createWithMissingId() {
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
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

            var workspaceName = generator.generate().toString();
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(trace))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            }

            var actualResponse = client.target("%s/v1/private/projects".formatted(baseURI))
                    .queryParam("workspace_name", workspaceName)
                    .queryParam("name", projectName)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.readEntity(Project.ProjectPage.class).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("when project name is null, then accept and use default project")
        void create__whenProjectNameIsNull__thenAcceptAndUseDefaultProject() {

            var id = generator.generate();

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(id)
                    .projectName(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(trace))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            }

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            UUID projectId = getProjectId(DEFAULT_PROJECT, TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Trace.class);
            assertThat(actualEntity.projectId()).isEqualTo(projectId);
        }

        @Test
        @DisplayName("when trace input is big, then accept and create trace")
        void createAndGet__whenTraceInputIsBig__thenReturnSpan() {

            int size = 1000;

            Map<String, String> jsonMap = IntStream.range(0, size)
                    .mapToObj(i -> Map.entry(RandomStringUtils.randomAlphabetic(10), RandomStringUtils.randomAscii(size)))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            var expectedTrace = factory.manufacturePojo(Trace.class).toBuilder()
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

    }

    @Nested
    @DisplayName("Batch:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchInsert {

        @Test
        void batch__whenCreateTraces__thenReturnNoContent() {

            var projectName = UUID.randomUUID().toString();

            createProject(projectName, TEST_WORKSPACE, API_KEY);

            var expectedTraces = IntStream.range(0, 1000)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .endTime(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateTracesAndAssert(expectedTraces, API_KEY, TEST_WORKSPACE);

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), List.of(), expectedTraces.reversed(), List.of(),
                    API_KEY);
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
                            .build())
                    .toList();

            batchCreateTracesAndAssert(expectedTraces, apiKey, workspaceName);

            getAndAssertPage(workspaceName, DEFAULT_PROJECT, List.of(), List.of(), expectedTraces.reversed(), List.of(),
                    apiKey);
        }

        @Test
        void batch__whenSendingMultipleTracesWithSameId__thenReturn422() {
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
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
                            .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                                    .projectId(null)
                                    .feedbackScores(null)
                                    .build())
                            .toList(), "traces size must be between 1 and 1000"));
        }

        @Test
        void batch__whenSendingMultipleTracesWithNoId__thenReturnNoContent() {
            var newTrace = factory.manufacturePojo(Trace.class).toBuilder()
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

            batchCreateTracesAndAssert(expectedTraces, API_KEY, TEST_WORKSPACE);
        }
    }

    private void batchCreateTracesAndAssert(List<Trace> traces, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(TraceBatch.builder().traces(traces).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private void batchCreateSpansAndAssert(List<Span> expectedSpans, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE_SPANS.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(SpanBatch.builder().spans(expectedSpans).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    @Nested
    @DisplayName("Delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteTrace {

        @Test
        @DisplayName("Success")
        void delete() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .build());
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

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
            createAndAssertForTrace(FeedbackScoreBatch.builder().scores(traceScores).build(), workspaceName, apiKey);

            var spanScores = spans.stream()
                    .flatMap(span -> span.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    span.id(), projectName, item)))
                    .toList();
            createAndAssertForSpan(FeedbackScoreBatch.builder().scores(spanScores).build(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            deleteAndAssert(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteWithoutSpansScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .build());
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

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
            createAndAssertForTrace(FeedbackScoreBatch.builder().scores(traceScores).build(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            deleteAndAssert(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteWithoutScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(null)
                    .build());
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

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

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            deleteAndAssert(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteWithoutSpans() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = List.of(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .feedbackScores(null)
                    .build());
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);

            deleteAndAssert(traces.getFirst().id(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
        }

        @Test
        @DisplayName("when trace does not exist, then return no content")
        void delete__whenTraceDoesNotExist__thenNoContent() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var id = generator.generate();

            deleteAndAssert(id, workspaceName, apiKey);

            getAndAssertTraceNotFound(id, apiKey, workspaceName);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteTraces {

        @Test
        void deleteTraces() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .build())
                    .toList();
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

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
            createAndAssertForTrace(FeedbackScoreBatch.builder().scores(traceScores).build(), workspaceName, apiKey);

            var spanScores = spans.stream()
                    .flatMap(span -> span.feedbackScores().stream()
                            .map(item -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(
                                    span.id(), projectName, item)))
                    .toList();
            createAndAssertForSpan(FeedbackScoreBatch.builder().scores(spanScores).build(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = TracesDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();
            deleteAndAssert(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutSpansScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .build())
                    .toList();
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

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
            createAndAssertForTrace(FeedbackScoreBatch.builder().scores(traceScores).build(), workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = TracesDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();
            deleteAndAssert(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutScores() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

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

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = TracesDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();
            deleteAndAssert(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutSpans() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            batchCreateTracesAndAssert(traces, apiKey, workspaceName);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, traces.reversed(), List.of(), apiKey);

            var request = TracesDelete.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();
            deleteAndAssert(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, List.of(), traces, List.of(), List.of(), apiKey);
        }

        @Test
        void deleteTracesWithoutTraces() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var request = factory.manufacturePojo(TracesDelete.class);
            deleteAndAssert(request, workspaceName, apiKey);
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
            trace = factory.manufacturePojo(Trace.class)
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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                        .contains("Trace id must be a version 7 UUID");
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

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Trace.class);
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

            var newTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(traceUpdate.projectName())
                    .id(id)
                    .build();

            create(newTrace, API_KEY, TEST_WORKSPACE);

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Trace.class);
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

            var newTrace = factory.manufacturePojo(Trace.class).toBuilder()
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

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Trace.class);
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

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);
            var actualEntity = actualResponse.readEntity(Trace.class);

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
                    .build();

            getAndAssert(updatedTrace, projectId, API_KEY, TEST_WORKSPACE);
        }

    }

    private Response getById(UUID id, String workspaceName, String apiKey) {
        var response = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(200);
        return response;
    }

    private void deleteAndAssert(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private void deleteAndAssert(TracesDelete request, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
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
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .allMatch(error -> Pattern.matches("Trace id: .+ not found", error));
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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }
        }

        @Test
        @DisplayName("when feedback without category name or reason, then return no content")
        void feedback__whenFeedbackWithoutCategoryNameOrReason__thenReturnNoContent() {
            var trace = factory.manufacturePojo(Trace.class)
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
            var trace = factory.manufacturePojo(Trace.class)
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
            var trace = factory.manufacturePojo(Trace.class)
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

            var trace = factory.manufacturePojo(Trace.class);
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

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualEntity = actualResponse.readEntity(Trace.class);
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
            var trace1 = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .metadata(null)
                    .tags(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var id1 = create(trace1, API_KEY, TEST_WORKSPACE);
            var trace2 = factory.manufacturePojo(Trace.class)
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
            var feedbackScoreBatch = FeedbackScoreBatch.builder().scores(List.of(score1, score2, score3)).build();
            createAndAssertForTrace(feedbackScoreBatch, TEST_WORKSPACE, API_KEY);

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
            var workspaceName = RandomStringUtils.randomAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedTrace1 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .projectId(null)
                    .usage(null)
                    .build();
            var id1 = create(expectedTrace1, apiKey, workspaceName);

            var expectedTrace2 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .projectId(null)
                    .usage(null)
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
            var feedbackScoreBatch = FeedbackScoreBatch.builder().scores(List.of(score1, score2, score3)).build();
            createAndAssertForTrace(feedbackScoreBatch, workspaceName, apiKey);

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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }
        }

        @Test
        @DisplayName("when feedback without category name or reason, then return no content")
        void feedback__whenFeedbackWithoutCategoryNameOrReason__thenReturnNoContent() {
            var trace = factory.manufacturePojo(Trace.class)
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
            createAndAssertForTrace(
                    FeedbackScoreBatch.builder().scores(List.of(score)).build(), TEST_WORKSPACE, API_KEY);

            var projectId = getProjectId(trace.projectName(), TEST_WORKSPACE, API_KEY);
            trace = trace.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score)))
                    .build();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {
            var projectName = RandomStringUtils.randomAlphanumeric(10);

            var expectedTrace = factory.manufacturePojo(Trace.class)
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
            createAndAssertForTrace(
                    FeedbackScoreBatch.builder().scores(List.of(score)).build(), TEST_WORKSPACE, API_KEY);

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
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var trace = factory.manufacturePojo(Trace.class)
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
            createAndAssertForTrace(
                    FeedbackScoreBatch.builder().scores(List.of(score)).build(), TEST_WORKSPACE, API_KEY);

            var newScore = score.toBuilder().value(factory.manufacturePojo(BigDecimal.class)).build();
            createAndAssertForTrace(
                    FeedbackScoreBatch.builder().scores(List.of(newScore)).build(), TEST_WORKSPACE, API_KEY);

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

            createAndAssertForTrace(
                    FeedbackScoreBatch.builder().scores(List.of(score)).build(), TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when feedback trace project and score project do not match, then return conflict")
        void feedback__whenFeedbackTraceProjectAndScoreProjectDoNotMatch__thenReturnConflict() {
            var trace = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .id(null)
                    .projectName(DEFAULT_PROJECT)
                    .endTime(null)
                    .output(null)
                    .createdAt(null)
                    .lastUpdatedAt(null)
                    .metadata(null)
                    .tags(null)
                    .feedbackScores(null)
                    .build();
            var id = create(trace, API_KEY, TEST_WORKSPACE);

            var score = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(UUID.randomUUID().toString())
                    .build();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains("project_name from score and project_id from trace does not match");
            }

        }

        @Test
        @DisplayName("when feedback trace batch has max size, then return no content and create scores")
        void feedback__whenFeedbackSpanBatchHasMaxSize__thenReturnNoContentAndCreateScores() {
            var expectedTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();
            var id = create(expectedTrace, API_KEY, TEST_WORKSPACE);

            var scores = IntStream.range(0, 1000)
                    .mapToObj(__ -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .id(id)
                            .build())
                    .toList();
            createAndAssertForTrace(FeedbackScoreBatch.builder().scores(scores).build(), TEST_WORKSPACE, API_KEY);
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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains("trace id must be a version 7 UUID");
            }
        }
    }

    private void createAndAssertForTrace(FeedbackScoreBatch request, String workspaceName, String apiKey) {
        createAndAssert(URL_TEMPLATE.formatted(baseURI), request, workspaceName, apiKey);
    }

    private void createAndAssertForSpan(FeedbackScoreBatch request, String workspaceName, String apiKey) {
        createAndAssert(URL_TEMPLATE_SPANS.formatted(baseURI), request, workspaceName, apiKey);
    }

    private void createAndAssert(String path, FeedbackScoreBatch request, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(path)
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
}
