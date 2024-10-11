package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.SpanMapper;
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
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jdbi.v3.core.Jdbi;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.auth.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.utils.ValidationUtils.MAX_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.MIN_FEEDBACK_SCORE_VALUE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpansResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/spans";
    public static final String[] IGNORED_FIELDS = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy"};
    public static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    public static final String API_KEY = UUID.randomUUID().toString();
    public static final String USER = UUID.randomUUID().toString();
    public static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final Random RANDOM = new Random();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;
    public static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    static {
        MY_SQL_CONTAINER.start();
        CLICK_HOUSE_CONTAINER.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
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
        void create__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(span))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 201);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void update__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, okApikey, workspaceName);

            var update = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .parentSpanId(span.parentSpanId())
                    .traceId(span.traceId())
                    .projectName(span.projectName())
                    .projectId(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/%s".formatted(spanId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(update))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void delete__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 501);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                    var expectedSpan = actualResponse.readEntity(Span.class);
                    assertThat(expectedSpan.id()).isEqualTo(spanId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void get__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var span = podamFactory.manufacturePojo(Span.class);

            createAndAssert(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", span.projectName())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                    var expectedSpans = actualResponse.readEntity(Span.SpanPage.class);
                    assertThat(expectedSpans.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, okApikey, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScore))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteFeedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, okApikey, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(spanId, feedbackScore, workspaceName, okApikey);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new DeleteFeedbackScore(feedbackScore.name())))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedbackBatch__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, okApikey, workspaceName);

            var items = PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .projectName(span.projectName())
                            .id(spanId)
                            .build())
                    .toList();

            var feedbackScoreBatch = FeedbackScoreBatch.builder()
                    .scores(items)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScoreBatch))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

    }

    private void assertExpectedResponseWithoutBody(boolean expected, Response actualResponse, int expectedStatus) {
        if (expected) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            assertThat(actualResponse.hasEntity()).isFalse();
        } else {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
            assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                    .isEqualTo(UNAUTHORIZED_RESPONSE);
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
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(span))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 201);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void update__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = createAndAssert(span, API_KEY, workspaceName);

            var update = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .parentSpanId(span.parentSpanId())
                    .traceId(span.traceId())
                    .projectName(span.projectName())
                    .projectId(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/%s".formatted(spanId))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(update))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void delete__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 501);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = createAndAssert(span, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                    var expectedSpan = actualResponse.readEntity(Span.class);
                    assertThat(expectedSpan.id()).isEqualTo(spanId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void get__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            createAndAssert(span, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", span.projectName())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                    var expectedSpans = actualResponse.readEntity(Span.SpanPage.class);
                    assertThat(expectedSpans.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = createAndAssert(span, API_KEY, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScore))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteFeedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean expected, String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = createAndAssert(span, API_KEY, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(spanId, feedbackScore, workspaceName, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new DeleteFeedbackScore(feedbackScore.name())))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedbackBatch__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = createAndAssert(span, API_KEY, workspaceName);

            var items = PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .projectName(span.projectName())
                            .id(spanId)
                            .build())
                    .toList();

            var feedbackScoreBatch = FeedbackScoreBatch.builder()
                    .scores(items)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScoreBatch))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, 204);
            }
        }

    }

    private static void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindSpans {

        @Test
        void createAndGetByProjectName() {
            String projectName = RandomStringUtils.randomAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .parentSpanId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans,
                    apiKey);
            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans,
                    apiKey);
        }

        @Test
        void createAndGetByWorkspace() {
            var projectName = RandomStringUtils.randomAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .parentSpanId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans, apiKey);

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans, apiKey);
        }

        @Test
        void createAndGetByProjectNameAndTraceId() {
            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traceId = generator.generate();

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .traceId(traceId)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .parentSpanId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    traceId,
                    null,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans, apiKey);
            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    traceId,
                    null,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans, apiKey);
        }

        @Test
        void createAndGetByProjectIdAndTraceIdAndType() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.randomAlphanumeric(10);
            var traceId = generator.generate();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .traceId(traceId)
                            .type(SpanType.llm)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var projectId = getAndAssert(spans.getLast(), apiKey, workspaceName).projectId();

            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .type(SpanType.general)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    null,
                    projectId,
                    traceId,
                    SpanType.llm,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans, apiKey);
            getAndAssertPage(
                    workspaceName,
                    null,
                    projectId,
                    traceId,
                    SpanType.llm,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterIdAndNameEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().id().toString())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().name())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().name().toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameStartsWith__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.STARTS_WITH)
                    .value(spans.getFirst().name().substring(0, spans.getFirst().name().length() - 4).toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameEndsWith__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.ENDS_WITH)
                    .value(spans.getFirst().name().substring(3).toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameContains__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.CONTAINS)
                    .value(spans.getFirst().name().substring(2, spans.getFirst().name().length() - 3).toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterNameNotContains__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spanName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .name(spanName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .name(generator.generate().toString())
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.NOT_CONTAINS)
                    .value(spanName.toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeGreaterThan__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value(Instant.now().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeGreaterThanEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeLessThan__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterStartTimeLessThanEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterEndTimeEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.END_TIME)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().endTime().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterInputEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.INPUT)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().input().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterOutputEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.OUTPUT)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().output().toString())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualString__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("$.model[0].version")
                    .value("OPENAI, CHAT-GPT 4.0")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualNumber__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("2023")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualBoolean__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("TRUE")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataEqualNull__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("NULL")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsString__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].version")
                    .value("CHAT-GPT")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsNumber__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":\"two thousand twenty " +
                                    "four\",\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("02")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsBoolean__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("TRU")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataContainsNull__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("NUL")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanNumber__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2020," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("2023")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanString__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].version")
                    .value("a")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanBoolean__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataGreaterThanNull__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanNumber__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2026," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("2025")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanString__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].version")
                    .value("z")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanBoolean__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterMetadataLessThanNull__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterTagsContains__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(spans.getFirst().tags().stream()
                            .toList()
                            .get(2)
                            .substring(0, spans.getFirst().name().length() - 4)
                            .toUpperCase())
                    .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        static Stream<Arguments> getByProjectName__whenFilterUsage__thenReturnSpansFiltered() {
            return Stream.of(
                    arguments("completion_tokens", SpanField.USAGE_COMPLETION_TOKENS),
                    arguments("prompt_tokens", SpanField.USAGE_PROMPT_TOKENS),
                    arguments("total_tokens", SpanField.USAGE_TOTAL_TOKENS));
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnSpansFiltered")
        void getByProjectName__whenFilterUsageEqual__thenReturnSpansFiltered(String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, RANDOM.nextInt()))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnSpansFiltered")
        void getByProjectName__whenFilterUsageGreaterThan__thenReturnSpansFiltered(String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.GREATER_THAN)
                            .value("123")
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnSpansFiltered")
        void getByProjectName__whenFilterUsageGreaterThanEqual__thenReturnSpansFiltered(String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnSpansFiltered")
        void getByProjectName__whenFilterUsageLessThan__thenReturnSpansFiltered(String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.LESS_THAN)
                            .value("456")
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @ParameterizedTest
        @MethodSource("getByProjectName__whenFilterUsage__thenReturnSpansFiltered")
        void getByProjectName__whenFilterUsageLessThanEqual__thenReturnSpansFiltered(String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456))
                            .feedbackScores(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresEqual__thenReturnSpansFiltered() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(
                                    PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScore.class).stream()
                                            .map(feedbackScore -> feedbackScore.toBuilder()
                                                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                                                    .build())
                                            .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            spans.set(1, spans.get(1).toBuilder()
                    .feedbackScores(
                            updateFeedbackScore(spans.get(1).feedbackScores(), spans.getFirst().feedbackScores(), 2))
                    .build());

            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .key(spans.getFirst().feedbackScores().get(1).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(1).value().toString())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresGreaterThan__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(
                                    span.feedbackScores().stream()
                                            .map(feedbackScore -> feedbackScore.toBuilder()
                                                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                                                    .build())
                                            .collect(Collectors.toList()),
                                    2, 1234.5678))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().name())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresGreaterThanEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 1234.5678))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresLessThan__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 2345.6789))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        @Test
        void getByProjectName__whenFilterFeedbackScoresLessThanEqual__thenReturnSpansFiltered() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 2345.6789))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());
            spans.forEach(expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> SpansResourceTest.this.createAndAssert(expectedSpan, apiKey, workspaceName));
            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());
            getAndAssertPage(workspaceName, projectName, filters, spans, expectedSpans, unexpectedSpans, apiKey);
        }

        static Stream<Filter> getByProjectName__whenFilterInvalidOperatorForFieldType__thenReturn400() {
            return Stream.of(
                    SpanFilter.builder()
                            .field(SpanField.START_TIME)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.END_TIME)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_COMPLETION_TOKENS)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_PROMPT_TOKENS)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.START_TIME)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.END_TIME)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_COMPLETION_TOKENS)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_PROMPT_TOKENS)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.START_TIME)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.END_TIME)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_COMPLETION_TOKENS)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_PROMPT_TOKENS)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.START_TIME)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.END_TIME)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_COMPLETION_TOKENS)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_PROMPT_TOKENS)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.INPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.OUTPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.INPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.OUTPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.INPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.OUTPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.INPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.OUTPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
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
            var projectName = generator.generate().toString();
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
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.EQUAL)
                            .value(" ")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.EQUAL)
                            .value("")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.INPUT)
                            .operator(Operator.EQUAL)
                            .value("")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.OUTPUT)
                            .operator(Operator.EQUAL)
                            .value("")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.START_TIME)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.END_TIME)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_COMPLETION_TOKENS)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_PROMPT_TOKENS)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.EQUAL)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .key(null)
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.METADATA)
                            .operator(Operator.EQUAL)
                            .value("")
                            .key(RandomStringUtils.randomAlphanumeric(10))
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("")
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .value("123.456")
                            .key(null)
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.EQUAL)
                            .value("")
                            .key("hallucination")
                            .build());
        }

        @ParameterizedTest
        @MethodSource
        void getByProjectName__whenFilterInvalidValueOrKeyForFieldType__thenReturn400(Filter filter) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = generator.generate().toString();
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
    }

    private void getAndAssertPage(
            String workspaceName,
            String projectName,
            List<? extends Filter> filters,
            List<Span> spans,
            List<Span> expectedSpans,
            List<Span> unexpectedSpans, String apiKey) {
        int page = 1;
        int size = spans.size() + expectedSpans.size() + unexpectedSpans.size();
        getAndAssertPage(
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

    private void getAndAssertPage(
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
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
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
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedSpans);
            assertIgnoredFields(actualSpans, expectedSpans);

            if (!unexpectedSpans.isEmpty()) {
                assertThat(actualSpans)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedSpans);
            }
        }
    }

    private String toURLEncodedQueryParam(List<? extends Filter> filters) {
        return CollectionUtils.isEmpty(filters)
                ? null
                : URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
    }

    private void assertIgnoredFields(List<Span> actualSpans, List<Span> expectedSpans) {
        for (int i = 0; i < actualSpans.size(); i++) {
            var actualSpan = actualSpans.get(i);
            var expectedSpan = expectedSpans.get(i);
            var expectedFeedbackScores = expectedSpan.feedbackScores() == null
                    ? null
                    : expectedSpan.feedbackScores().reversed();
            assertThat(actualSpan.projectId()).isNotNull();
            assertThat(actualSpan.projectName()).isNull();
            assertThat(actualSpan.createdAt()).isAfter(expectedSpan.createdAt());
            assertThat(actualSpan.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
            assertThat(actualSpan.feedbackScores())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                    .build())
                    .isEqualTo(expectedFeedbackScores);

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

    private UUID createAndAssert(Span expectedSpan, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(expectedSpan))) {

            var actualHeaderString = actualResponse.getHeaderString("Location");
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            UUID expectedSpanId;
            if (expectedSpan.id() != null) {
                expectedSpanId = expectedSpan.id();
            } else {
                expectedSpanId = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertThat(actualHeaderString).isEqualTo(URL_TEMPLATE.formatted(baseURI)
                    .concat("/")
                    .concat(expectedSpanId.toString()));

            return expectedSpanId;
        }
    }

    private void createAndAssert(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(entityId.toString())
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(score))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    @Test
    void createAndGetById() {
        var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectId(null)
                .parentSpanId(null)
                .build();

        createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

        getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
    }

    @Test
    void createAndGet__whenSpanInputIsBig__thenReturnSpan() {

        int size = 1000;

        Map<String, String> jsonMap = IntStream.range(0, size)
                .mapToObj(i -> Map.entry(RandomStringUtils.randomAlphabetic(10), RandomStringUtils.randomAscii(size)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectId(null)
                .parentSpanId(null)
                .input(JsonUtils.readTree(jsonMap))
                .output(JsonUtils.readTree(jsonMap))
                .build();

        createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

        getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
    }

    @Test
    void createOnlyRequiredFieldsAndGetById() {
        var expectedSpan = podamFactory.manufacturePojo(Span.class)
                .toBuilder()
                .projectName(null)
                .id(null)
                .parentSpanId(null)
                .endTime(null)
                .input(null)
                .output(null)
                .metadata(null)
                .tags(null)
                .usage(null)
                .build();
        var expectedSpanId = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        getAndAssert(expectedSpan.toBuilder().id(expectedSpanId).build(), API_KEY, TEST_WORKSPACE);
    }

    @Test
    void createSpansWithDifferentWorkspaces() {

        var expectedSpan1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectId(null)
                .parentSpanId(null)
                .build();

        var expectedSpan2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectId(null)
                .parentSpanId(null)
                .projectName(UUID.randomUUID().toString())
                .build();

        createAndAssert(expectedSpan1, API_KEY, TEST_WORKSPACE);
        createAndAssert(expectedSpan2, API_KEY, TEST_WORKSPACE);

        getAndAssert(expectedSpan1, API_KEY, TEST_WORKSPACE);
        getAndAssert(expectedSpan2, API_KEY, TEST_WORKSPACE);
    }

    @Test
    void createWhenTryingToCreateSpanTwice() {
        var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectId(null)
                .parentSpanId(null)
                .build();

        createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.json(expectedSpan))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
            assertThat(actualResponse.hasEntity()).isTrue();

            var errorMessage = actualResponse.readEntity(ErrorMessage.class);
            assertThat(errorMessage.errors()).contains("Span already exists");
        }
    }

    @Nested
    @DisplayName("Batch:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchInsert {

        @Test
        void batch__whenCreateSpans__thenReturnNoContent() {

            String projectName = UUID.randomUUID().toString();
            UUID projectId = createProject(projectName, TEST_WORKSPACE, API_KEY);

            var expectedSpans = IntStream.range(0, 1000)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectId(projectId)
                            .projectName(projectName)
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateAndAssert(expectedSpans, API_KEY, TEST_WORKSPACE);

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), List.of(), expectedSpans.reversed(), List.of(),
                    API_KEY);
        }

        @Test
        void batch__whenSpansProjectNameIsNull__thenUserDefaultProjectAndReturnNoContent() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(null)
                            .endTime(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateAndAssert(expectedSpans, apiKey, workspaceName);

            getAndAssertPage(workspaceName, DEFAULT_PROJECT, List.of(), List.of(), expectedSpans.reversed(), List.of(),
                    apiKey);
        }

        @Test
        void batch__whenSendingMultipleSpansWithSameId__thenReturn422() {
            var expectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .parentSpanId(null)
                    .feedbackScores(null)
                    .build());

            var expectedSpan = expectedSpans.getFirst().toBuilder()
                    .tags(Set.of())
                    .endTime(Instant.now())
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .build();

            List<Span> expectedSpans1 = List.of(expectedSpans.getFirst(), expectedSpan);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(SpanBatch.builder().spans(expectedSpans1).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();

                var errorMessage = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(errorMessage.getMessage()).isEqualTo("Duplicate span id '%s'".formatted(expectedSpan.id()));
            }
        }

        @ParameterizedTest
        @MethodSource
        void batch__whenBatchIsInvalid__thenReturn422(List<Span> spans, String errorMessage) {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new SpanBatch(spans)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();

                var responseBody = actualResponse.readEntity(ErrorMessage.class);
                assertThat(responseBody.errors()).contains(errorMessage);
            }
        }

        Stream<Arguments> batch__whenBatchIsInvalid__thenReturn422() {
            return Stream.of(
                    Arguments.of(List.of(), "spans size must be between 1 and 1000"),
                    Arguments.of(IntStream.range(0, 1001)
                            .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                                    .projectId(null)
                                    .parentSpanId(null)
                                    .feedbackScores(null)
                                    .build())
                            .toList(), "spans size must be between 1 and 1000"));
        }

        @Test
        void batch__whenSendingMultipleSpansWithNoId__thenReturnNoContent() {
            var newSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .id(null)
                    .parentSpanId(null)
                    .feedbackScores(null)
                    .build();

            var expectedSpan = newSpan.toBuilder()
                    .tags(Set.of())
                    .endTime(Instant.now())
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .build();

            List<Span> expectedSpans = List.of(newSpan, expectedSpan);

            batchCreateAndAssert(expectedSpans, API_KEY, TEST_WORKSPACE);
        }

    }

    private void batchCreateAndAssert(List<Span> expectedSpans, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new SpanBatch(expectedSpans)))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private Span getAndAssert(Span expectedSpan, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(expectedSpan.id().toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            var actualSpan = actualResponse.readEntity(Span.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualSpan)
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS)
                    .ignoringCollectionOrderInFields("tags")
                    .isEqualTo(expectedSpan);
            assertThat(actualSpan.projectId()).isNotNull();
            assertThat(actualSpan.projectName()).isNull();
            assertThat(actualSpan.createdAt()).isAfter(expectedSpan.createdAt());
            assertThat(actualSpan.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
            assertThat(actualSpan.createdBy()).isEqualTo(USER);
            assertThat(actualSpan.lastUpdatedBy()).isEqualTo(USER);
            return actualSpan;
        }
    }

    @Test
    void delete() {
        UUID id = generator.generate();

        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .delete()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(501);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetSpan {

        @Test
        void getNotFound() {
            UUID id = generator.generate();

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(404,
                    "Not found span with id '%s'".formatted(id));
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateSpan {

        @Test
        @DisplayName("Success")
        void createAndUpdateAndGet() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(null)
                    .parentSpanId(null)
                    .build();
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class);
            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdateBuilder = expectedSpanUpdate.toBuilder()
                    .projectId(null)
                    .projectName(expectedSpan.projectName())
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId());

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(expectedSpan.id().toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(spanUpdateBuilder.build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var expectedSpanBuilder = expectedSpan
                    .toBuilder();
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdateBuilder.build());
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        static Stream<SpanUpdate> update__whenFieldIsNotNull__thenAcceptUpdate() {
            return Stream.of(
                    SpanUpdate.builder().endTime(Instant.now()).build(),
                    SpanUpdate.builder().input(JsonUtils.getJsonNodeFromString("{ \"input\": \"data\"}")).build(),
                    SpanUpdate.builder().output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}")).build(),
                    SpanUpdate.builder().metadata(JsonUtils.getJsonNodeFromString("{ \"metadata\": \"data\"}")).build(),
                    SpanUpdate.builder().tags(Set.of(
                            RandomStringUtils.randomAlphanumeric(10),
                            RandomStringUtils.randomAlphanumeric(10),
                            RandomStringUtils.randomAlphanumeric(10),
                            RandomStringUtils.randomAlphanumeric(10),
                            RandomStringUtils.randomAlphanumeric(10))).build(),
                    SpanUpdate.builder().usage(Map.of(
                            RandomStringUtils.randomAlphanumeric(10), RANDOM.nextInt(),
                            RandomStringUtils.randomAlphanumeric(10), RANDOM.nextInt(),
                            RandomStringUtils.randomAlphanumeric(10), RANDOM.nextInt(),
                            RandomStringUtils.randomAlphanumeric(10), RANDOM.nextInt(),
                            RandomStringUtils.randomAlphanumeric(10), RANDOM.nextInt())).build());
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when only some field is not null, then accept update")
        void update__whenFieldIsNotNull__thenAcceptUpdate(SpanUpdate expectedSpanUpdate) {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(null)
                    .parentSpanId(null)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            SpanUpdate spanUpdate = expectedSpanUpdate.toBuilder()
                    .parentSpanId(expectedSpan.parentSpanId())
                    .traceId(expectedSpan.traceId())
                    .projectName(expectedSpan.projectName())
                    .build();

            runPatchAndAssertStatus(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder();
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, expectedSpanUpdate);
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        @Test
        void updateWhenSpanDoesNotExistButSpanIdIsInvalid__thenRejectUpdate() {
            var id = UUID.randomUUID();
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(expectedSpanUpdate))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                        .contains("Span id must be a version 7 UUID");
            }
        }

        @Test
        void updateWhenSpanDoesNotExist__thenAcceptUpdate() {
            var id = generator.generate();
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(expectedSpanUpdate))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            }
        }

        @Test
        @DisplayName("when span does not exist, then return create it")
        void when__spanDoesNotExist__thenReturnCreateIt() {
            var id = generator.generate();

            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            runPatchAndAssertStatus(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            var projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Span.class);
            assertThat(actualEntity.id()).isEqualTo(id);

            assertThat(actualEntity.projectId()).isEqualTo(projectId);
            assertThat(actualEntity.traceId()).isEqualTo(spanUpdate.traceId());
            assertThat(actualEntity.parentSpanId()).isEqualTo(spanUpdate.parentSpanId());

            assertThat(actualEntity.input()).isEqualTo(spanUpdate.input());
            assertThat(actualEntity.output()).isEqualTo(spanUpdate.output());
            assertThat(actualEntity.endTime()).isEqualTo(spanUpdate.endTime());
            assertThat(actualEntity.metadata()).isEqualTo(spanUpdate.metadata());
            assertThat(actualEntity.tags()).isEqualTo(spanUpdate.tags());

            assertThat(actualEntity.name()).isEmpty();
            assertThat(actualEntity.startTime()).isEqualTo(Instant.EPOCH);
            assertThat(actualEntity.type()).isNull();
        }

        @Test
        @DisplayName("when span update and insert are processed out of other, then return span")
        void when__spanUpdateAndInsertAreProcessedOutOfOther__thenReturnSpan() {
            var id = generator.generate();

            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            var startCreation = Instant.now();
            runPatchAndAssertStatus(id, spanUpdate, API_KEY, TEST_WORKSPACE);
            var created = Instant.now();

            var newSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(spanUpdate.projectName())
                    .traceId(spanUpdate.traceId())
                    .parentSpanId(spanUpdate.parentSpanId())
                    .id(id)
                    .build();

            createAndAssert(newSpan, API_KEY, TEST_WORKSPACE);

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            var projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Span.class);
            assertThat(actualEntity.id()).isEqualTo(id);

            assertThat(actualEntity.projectId()).isEqualTo(projectId);
            assertThat(actualEntity.traceId()).isEqualTo(spanUpdate.traceId());
            assertThat(actualEntity.parentSpanId()).isEqualTo(spanUpdate.parentSpanId());

            assertThat(actualEntity.input()).isEqualTo(spanUpdate.input());
            assertThat(actualEntity.output()).isEqualTo(spanUpdate.output());
            assertThat(actualEntity.endTime()).isEqualTo(spanUpdate.endTime());
            assertThat(actualEntity.metadata()).isEqualTo(spanUpdate.metadata());
            assertThat(actualEntity.tags()).isEqualTo(spanUpdate.tags());

            assertThat(actualEntity.name()).isEqualTo(newSpan.name());
            assertThat(actualEntity.startTime()).isEqualTo(newSpan.startTime());
            assertThat(actualEntity.type()).isEqualTo(newSpan.type());

            assertThat(actualEntity.createdAt()).isBetween(startCreation, created);
            assertThat(actualEntity.lastUpdatedAt()).isBetween(created, Instant.now());
            assertThat(actualEntity.createdBy()).isEqualTo(USER);
            assertThat(actualEntity.lastUpdatedBy()).isEqualTo(USER);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when span update and insert conflict, then return 409")
        void when__spanUpdateAndInsertConflict__thenReturn409(BiFunction<SpanUpdate, UUID, Span> mapper,
                String errorMessage) {
            var id = generator.generate();

            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            runPatchAndAssertStatus(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            var newSpan = mapper.apply(spanUpdate, id);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(newSpan))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains(errorMessage);
            }
        }

        Stream<Arguments> when__spanUpdateAndInsertConflict__thenReturn409() {
            return Stream.of(
                    arguments(
                            (BiFunction<SpanUpdate, UUID, Span>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(Span.class).toBuilder()
                                    .traceId(spanUpdate.traceId())
                                    .parentSpanId(spanUpdate.parentSpanId())
                                    .id(id)
                                    .build(),
                            "Project name and workspace name do not match the existing span"),
                    arguments(
                            (BiFunction<SpanUpdate, UUID, Span>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(Span.class).toBuilder()
                                    .traceId(spanUpdate.traceId())
                                    .parentSpanId(spanUpdate.parentSpanId())
                                    .id(id)
                                    .build(),
                            "Project name and workspace name do not match the existing span"),
                    arguments(
                            (BiFunction<SpanUpdate, UUID, Span>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(Span.class).toBuilder()
                                    .projectName(spanUpdate.projectName())
                                    .parentSpanId(spanUpdate.parentSpanId())
                                    .id(id)
                                    .build(),
                            "trace_id does not match the existing span"),
                    arguments(
                            (BiFunction<SpanUpdate, UUID, Span>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(Span.class).toBuilder()
                                    .projectName(spanUpdate.projectName())
                                    .traceId(spanUpdate.traceId())
                                    .id(id)
                                    .build(),
                            "parent_span_id does not match the existing span"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when multiple span update conflict, then return 409")
        void when__multipleSpanUpdateConflict__thenReturn409(BiFunction<SpanUpdate, UUID, SpanUpdate> mapper,
                String errorMessage) {
            var id = generator.generate();

            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            runPatchAndAssertStatus(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            SpanUpdate newSpan = mapper.apply(spanUpdate, id);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(newSpan))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains(errorMessage);
            }
        }

        Stream<Arguments> when__multipleSpanUpdateConflict__thenReturn409() {
            return Stream.of(
                    arguments(
                            (BiFunction<SpanUpdate, UUID, SpanUpdate>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(SpanUpdate.class).toBuilder()
                                    .traceId(spanUpdate.traceId())
                                    .parentSpanId(spanUpdate.parentSpanId())
                                    .projectId(spanUpdate.projectId())
                                    .build(),
                            "Project name and workspace name do not match the existing span"),
                    arguments(
                            (BiFunction<SpanUpdate, UUID, SpanUpdate>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(SpanUpdate.class).toBuilder()
                                    .traceId(spanUpdate.traceId())
                                    .parentSpanId(spanUpdate.parentSpanId())
                                    .projectId(spanUpdate.projectId())
                                    .build(),
                            "Project name and workspace name do not match the existing span"),
                    arguments(
                            (BiFunction<SpanUpdate, UUID, SpanUpdate>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(SpanUpdate.class).toBuilder()
                                    .projectName(spanUpdate.projectName())
                                    .parentSpanId(spanUpdate.parentSpanId())
                                    .projectId(spanUpdate.projectId())
                                    .build(),
                            "trace_id does not match the existing span"),
                    arguments(
                            (BiFunction<SpanUpdate, UUID, SpanUpdate>) (spanUpdate, id) -> podamFactory
                                    .manufacturePojo(SpanUpdate.class).toBuilder()
                                    .projectName(spanUpdate.projectName())
                                    .traceId(spanUpdate.traceId())
                                    .projectId(spanUpdate.projectId())
                                    .build(),
                            "parent_span_id does not match the existing span"));
        }

        @Test
        @DisplayName("when multiple span update and insert are processed out of other and concurrent, then return span")
        void when__multipleSpanUpdateAndInsertAreProcessedOutOfOtherAndConcurrent__thenReturnSpan() {
            var id = generator.generate();

            var projectName = UUID.randomUUID().toString();

            var spanUpdate1 = SpanUpdate.builder()
                    .metadata(JsonUtils.getJsonNodeFromString("{ \"metadata\": \"data\" }"))
                    .projectName(projectName)
                    .traceId(generator.generate())
                    .parentSpanId(null)
                    .build();

            var startCreation = Instant.now();

            var spanUpdate2 = SpanUpdate.builder()
                    .input(JsonUtils.getJsonNodeFromString("{ \"input\": \"data2\"}"))
                    .tags(Set.of("tag1", "tag2"))
                    .projectName(projectName)
                    .traceId(spanUpdate1.traceId())
                    .parentSpanId(spanUpdate1.parentSpanId())
                    .build();

            var spanUpdate3 = SpanUpdate.builder()
                    .output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}"))
                    .endTime(Instant.now())
                    .projectName(projectName)
                    .traceId(spanUpdate1.traceId())
                    .parentSpanId(spanUpdate1.parentSpanId())
                    .build();

            var newSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(spanUpdate1.projectName())
                    .traceId(spanUpdate1.traceId())
                    .parentSpanId(spanUpdate1.parentSpanId())
                    .endTime(null)
                    .id(id)
                    .build();

            var update1 = Mono.fromRunnable(() -> runPatchAndAssertStatus(id, spanUpdate3, API_KEY, TEST_WORKSPACE));
            var create = Mono.fromRunnable(() -> createAndAssert(newSpan, API_KEY, TEST_WORKSPACE));
            var update2 = Mono.fromRunnable(() -> runPatchAndAssertStatus(id, spanUpdate2, API_KEY, TEST_WORKSPACE));
            var update3 = Mono.fromRunnable(() -> runPatchAndAssertStatus(id, spanUpdate1, API_KEY, TEST_WORKSPACE));

            Flux.merge(update1, update2, update3, create).blockLast();

            var created = Instant.now();

            var actualResponse = getById(id, TEST_WORKSPACE, API_KEY);

            var actualEntity = actualResponse.readEntity(Span.class);
            assertThat(actualEntity.id()).isEqualTo(id);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            assertThat(actualEntity.projectId()).isEqualTo(projectId);
            assertThat(actualEntity.traceId()).isEqualTo(spanUpdate1.traceId());
            assertThat(actualEntity.parentSpanId()).isEqualTo(spanUpdate1.parentSpanId());

            assertThat(actualEntity.endTime()).isEqualTo(spanUpdate3.endTime());
            assertThat(actualEntity.input()).isEqualTo(spanUpdate2.input());
            assertThat(actualEntity.output()).isEqualTo(spanUpdate3.output());
            assertThat(actualEntity.metadata()).isEqualTo(spanUpdate1.metadata());
            assertThat(actualEntity.tags()).isEqualTo(spanUpdate2.tags());

            assertThat(actualEntity.name()).isEqualTo(newSpan.name());
            assertThat(actualEntity.startTime()).isEqualTo(newSpan.startTime());
            assertThat(actualEntity.createdAt()).isBetween(startCreation, created);
            assertThat(actualEntity.lastUpdatedAt()).isBetween(startCreation, created);
            assertThat(actualEntity.createdBy()).isEqualTo(USER);
            assertThat(actualEntity.lastUpdatedBy()).isEqualTo(USER);
        }

        @Test
        @DisplayName("when tags is empty, then accept update")
        void update__whenTagsIsEmpty__thenAcceptUpdate() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .parentSpanId(null)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .tags(Set.of())
                    .build();

            runPatchAndAssertStatus(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .tags(spanUpdate.tags())
                    .projectId(projectId)
                    .build();

            Span actualTrace = getAndAssert(updatedSpan.toBuilder().tags(null).build(), API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.tags()).isNull();
        }

        @Test
        @DisplayName("when metadata is empty, then accept update")
        void update__whenMetadataIsEmpty__thenAcceptUpdate() {

            JsonNode metadata = JsonUtils.getJsonNodeFromString("{}");

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .parentSpanId(null)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .metadata(metadata)
                    .build();

            runPatchAndAssertStatus(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .metadata(metadata)
                    .projectId(projectId)
                    .build();

            Span actualTrace = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.metadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("when input is empty, then accept update")
        void update__whenInputIsEmpty__thenAcceptUpdate() {

            JsonNode input = JsonUtils.getJsonNodeFromString("{}");

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .parentSpanId(null)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .input(input)
                    .build();

            runPatchAndAssertStatus(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .input(input)
                    .projectId(projectId)
                    .build();

            Span actualTrace = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.input()).isEqualTo(input);
        }

        @Test
        @DisplayName("when output is empty, then accept update")
        void update__whenOutputIsEmpty__thenAcceptUpdate() {
            JsonNode output = JsonUtils.getJsonNodeFromString("{}");

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .parentSpanId(null)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .output(output)
                    .build();

            runPatchAndAssertStatus(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .output(output)
                    .projectId(projectId)
                    .build();

            Span actualTrace = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actualTrace.output()).isEqualTo(output);
        }

        @Test
        @DisplayName("when updating using projectId, then accept update")
        void update__whenUpdatingUsingProjectId__thenAcceptUpdate() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .parentSpanId(null)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(expectedSpan.projectName(), TEST_WORKSPACE, API_KEY);

            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectId(projectId)
                    .build();

            runPatchAndAssertStatus(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            Span updatedSpan = expectedSpan.toBuilder()
                    .projectId(projectId)
                    .metadata(spanUpdate.metadata())
                    .input(spanUpdate.input())
                    .output(spanUpdate.output())
                    .endTime(spanUpdate.endTime())
                    .tags(spanUpdate.tags())
                    .usage(spanUpdate.usage())
                    .build();

            getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
        }

        private void runPatchAndAssertStatus(UUID id, SpanUpdate spanUpdate, String apiKey, String workspaceName) {
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(spanUpdate))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }
    }

    private Response getById(UUID id, String workspaceName, String apiKey) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    @Nested
    @DisplayName("Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanFeedback {

        public Stream<Arguments> invalidRequestBodyParams() {
            return Stream.of(
                    arguments(
                            podamFactory.manufacturePojo(FeedbackScore.class).toBuilder().name(null).build(),
                            "name must not be blank"),
                    arguments(podamFactory.manufacturePojo(FeedbackScore.class).toBuilder().name("").build(),
                            "name must not be blank"),
                    arguments(
                            podamFactory.manufacturePojo(FeedbackScore.class).toBuilder().value(null).build(),
                            "value must not be null"),
                    arguments(
                            podamFactory.manufacturePojo(FeedbackScore.class).toBuilder()
                                    .value(BigDecimal.valueOf(-999999999.9999999991)).build(),
                            "value must be greater than or equal to -999999999.999999999"),
                    arguments(
                            podamFactory.manufacturePojo(FeedbackScore.class).toBuilder()
                                    .value(BigDecimal.valueOf(999999999.9999999991)).build(),
                            "value must be less than or equal to 999999999.999999999"));
        }

        @ParameterizedTest
        @MethodSource("invalidRequestBodyParams")
        @DisplayName("when feedback request body is invalid, then return bad request")
        void feedback__whenFeedbackRequestBodyIsInvalid__thenReturnBadRequest(FeedbackScore feedbackScore,
                String errorMessage) {

            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id.toString())
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(feedbackScore, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }
        }

        @Test
        @DisplayName("when feedback without category name or reason, then return no content")
        void feedback__whenFeedbackWithoutCategoryNameOrReason__thenReturnNoContent() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .categoryName(null)
                    .reason(null)
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            var actual = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            var actualScore = actual.feedbackScores().getFirst();

            assertThat(actualScore)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                    .build())
                    .isEqualTo(score);

            assertThat(actualScore.createdAt()).isAfter(expectedSpan.createdAt());
            assertThat(actualScore.lastUpdatedAt()).isAfter(expectedSpan.lastUpdatedAt());
            assertThat(actualScore.createdBy()).isEqualTo(USER);
            assertThat(actualScore.lastUpdatedBy()).isEqualTo(USER);

        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {

            var instant = Instant.now();
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            var actual = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            var actualScore = actual.feedbackScores().getFirst();

            assertThat(actualScore)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                    .build())
                    .isEqualTo(score);

            assertThat(actualScore.createdAt()).isAfter(instant);
            assertThat(actualScore.lastUpdatedAt()).isAfter(instant);
            assertThat(actualScore.createdBy()).isEqualTo(USER);
            assertThat(actualScore.lastUpdatedBy()).isEqualTo(USER);
        }

        @Test
        @DisplayName("when overriding feedback value, then return no content")
        void feedback__whenOverridingFeedbackValue__thenReturnNoContent() {

            Instant now = Instant.now();
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            var newScore = score.toBuilder().value(BigDecimal.valueOf(2)).build();
            createAndAssert(id, newScore, TEST_WORKSPACE, API_KEY);

            var actual = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            var actualScore = actual.feedbackScores().getFirst();

            assertThat(actualScore)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                    .build())
                    .isEqualTo(newScore);

            assertThat(actualScore.createdAt()).isAfter(now);
            assertThat(actualScore.lastUpdatedAt()).isAfter(now);
            assertThat(actualScore.createdBy()).isEqualTo(USER);
            assertThat(actualScore.lastUpdatedBy()).isEqualTo(USER);
        }
    }

    @Nested
    @DisplayName("Delete Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteSpanFeedbackDefinition {

        @Test
        @DisplayName("when span does not exist, then return no content")
        void deleteFeedback__whenSpanDoesNotExist__thenReturnNoContent() {

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

            Span expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = FeedbackScore.builder()
                    .name("name")
                    .value(BigDecimal.valueOf(1))
                    .source(ScoreSource.SDK)
                    .build();
            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id.toString())
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

            var actualEntity = actualResponse.readEntity(Span.class);
            assertThat(actualEntity.feedbackScores()).isNull();
        }

    }

    @Nested
    @DisplayName("Batch Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchSpansFeedback {

        public Stream<Arguments> invalidRequestBodyParams() {
            return Stream.of(
                    arguments(FeedbackScoreBatch.builder().build(), "scores must not be null"),
                    arguments(FeedbackScoreBatch.builder().scores(List.of()).build(),
                            "scores size must be between 1 and 1000"),
                    arguments(FeedbackScoreBatch.builder().scores(
                            IntStream.range(0, 1001)
                                    .mapToObj(
                                            __ -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                                    .projectName(DEFAULT_PROJECT).build())
                                    .toList())
                            .build(), "scores size must be between 1 and 1000"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                                    .projectName(DEFAULT_PROJECT).name(null).build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                                    .projectName(DEFAULT_PROJECT).name("").build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                                    .projectName(DEFAULT_PROJECT).value(null).build()))
                                    .build(),
                            "scores[0].value must not be null"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                                            .toBuilder()
                                            .projectName(DEFAULT_PROJECT)
                                            .value(new BigDecimal(MIN_FEEDBACK_SCORE_VALUE).subtract(BigDecimal.ONE))
                                            .build()))
                                    .build(),
                            "scores[0].value must be greater than or equal to -999999999.999999999"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                                    .projectName(DEFAULT_PROJECT)
                                                    .value(new BigDecimal(MAX_FEEDBACK_SCORE_VALUE).add(BigDecimal.ONE))
                                                    .build()))
                                    .build(),
                            "scores[0].value must be less than or equal to 999999999.999999999"));
        }

        @Test
        @DisplayName("Success")
        void feedback() {

            var expectedSpan1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();
            var id = createAndAssert(expectedSpan1, API_KEY, TEST_WORKSPACE);

            Span expectedSpan2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(UUID.randomUUID().toString())
                    .build();

            var id2 = createAndAssert(expectedSpan2, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                    .toBuilder()
                    .id(id)
                    .projectName(expectedSpan1.projectName())
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            var score2 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id2)
                    .name("hallucination")
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .projectName(expectedSpan2.projectName())
                    .build();

            var score3 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .name("hallucination")
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .projectName(expectedSpan1.projectName())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score, score2, score3))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualSpan1 = getAndAssert(expectedSpan1, API_KEY, TEST_WORKSPACE);
            var actualSpan2 = getAndAssert(expectedSpan2, API_KEY, TEST_WORKSPACE);

            assertThat(actualSpan2.feedbackScores()).hasSize(1);
            assertThat(actualSpan1.feedbackScores()).hasSize(2);

            assertEqualsForScores(actualSpan1, List.of(score, score3));
            assertEqualsForScores(actualSpan2, List.of(score2));
        }

        @Test
        @DisplayName("when workspace is specified, then return no content")
        void feedback__whenWorkspaceIsSpecified__thenReturnNoContent() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String projectName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Span expectedSpan1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();
            var id = createAndAssert(expectedSpan1, apiKey, workspaceName);

            Span expectedSpan2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .build();

            var id2 = createAndAssert(expectedSpan2, apiKey, workspaceName);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                    .toBuilder()
                    .id(id)
                    .projectName(expectedSpan1.projectName())
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            var score2 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id2)
                    .name("hallucination")
                    .projectName(expectedSpan2.projectName())
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            var score3 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .name("hallucination")
                    .projectName(expectedSpan1.projectName())
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score, score2, score3))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualSpan1 = getAndAssert(expectedSpan1, apiKey, workspaceName);
            var actualSpan2 = getAndAssert(expectedSpan2, apiKey, workspaceName);

            assertThat(actualSpan2.feedbackScores()).hasSize(1);
            assertThat(actualSpan1.feedbackScores()).hasSize(2);

            assertEqualsForScores(actualSpan1, List.of(score, score3));
            assertEqualsForScores(actualSpan2, List.of(score2));
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

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();

            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedSpan.projectName())
                    .categoryName(null)
                    .reason(null)
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actual = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actual.feedbackScores()).hasSize(1);
            FeedbackScore actualScore = actual.feedbackScores().getFirst();

            assertEqualsForScores(actualScore, score);
        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .parentSpanId(null)
                    .build();

            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedSpan.projectName())
                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actual = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            FeedbackScore actualScore = actual.feedbackScores().getFirst();

            assertEqualsForScores(actualScore, score);

        }

        @Test
        @DisplayName("when overriding feedback value, then return no content")
        void feedback__whenOverridingFeedbackValue__thenReturnNoContent() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .parentSpanId(null)
                    .build();

            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedSpan.projectName())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            FeedbackScoreBatchItem newScore = score.toBuilder().value(podamFactory.manufacturePojo(BigDecimal.class))
                    .build();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(newScore))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actual = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            assertEqualsForScores(actual.feedbackScores().getFirst(), newScore);
        }

        @Test
        @DisplayName("when span does not exist, then return no content and create score")
        void feedback__whenSpanDoesNotExist__thenReturnNoContentAndCreateScore() {

            UUID id = generator.generate();

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(List.of(score))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

        }

        @Test
        @DisplayName("when feedback span project and score project do not match, then return conflict")
        void feedback__whenFeedbackSpanProjectAndScoreProjectDoNotMatch__thenReturnConflict() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .parentSpanId(null)
                    .build();

            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
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
                        .contains("project_name from score and project_id from span does not match");
            }

        }

        @Test
        @DisplayName("when feedback span id is not valid, then return 400")
        void feedback__whenFeedbackSpanIdIsNotValid__thenReturn400() {

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
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
                        .contains("span id must be a version 7 UUID");
            }
        }

        @Test
        @DisplayName("when feedback span batch has max size, then return no content and create scores")
        void feedback__whenFeedbackSpanBatchHasMaxSize__thenReturnNoContentAndCreateScores() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .build();

            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var scores = IntStream.range(0, 1000)
                    .mapToObj(__ -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .id(id)
                            .build())
                    .toList();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(new FeedbackScoreBatch(scores)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }
    }

    private void assertEqualsForScores(FeedbackScore actualScore, FeedbackScoreBatchItem score) {
        assertThat(actualScore)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                .build())
                .isEqualTo(score);

        assertThat(actualScore.createdAt()).isNotNull();
        assertThat(actualScore.lastUpdatedAt()).isNotNull();
        assertThat(actualScore.createdBy()).isEqualTo(USER);
        assertThat(actualScore.lastUpdatedBy()).isEqualTo(USER);
    }

    private void assertEqualsForScores(Span actualSpan1, List<FeedbackScoreBatchItem> score) {
        assertThat(actualSpan1.feedbackScores())
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                .withIgnoredFields(IGNORED_FIELDS_SCORES)
                                .build())
                .ignoringCollectionOrder()
                .isEqualTo(score);
    }
}
