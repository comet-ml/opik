package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.TestComparators;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.GuardrailsValidation;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectRetrieve;
import com.comet.opik.api.ProjectStatsSummary;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.BigDecimalCollectors;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.GuardrailsMapper;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.ValidationUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.ProjectStatsSummary.ProjectStatsSummaryItem;
import static com.comet.opik.api.Visibility.PRIVATE;
import static com.comet.opik.api.Visibility.PUBLIC;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.PROJECT_NOT_FOUND_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.averagingDouble;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ProjectsResourceTest {

    public static final String URL_PATTERN = "http://.*/v1/private/projects/.{8}-.{4}-.{4}-.{4}-.{12}";
    public static final String URL_TEMPLATE = "%s/v1/private/projects";
    public static final String URL_TEMPLATE_TRACE = "%s/v1/private/traces";
    public static final String[] IGNORED_FIELDS = {"createdBy", "lastUpdatedBy", "createdAt", "lastUpdatedAt",
            "lastUpdatedTraceAt", "feedbackScores", "duration", "totalEstimatedCost", "usage", "traceCount",
            "guardrailsFailedCount"};
    public static final String[] IGNORED_FIELD_MIN = {"createdBy", "lastUpdatedBy", "createdAt", "lastUpdatedAt",
            "lastUpdatedTraceAt"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectService projectService;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;
    private GuardrailsGenerator guardrailsGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, ProjectService projectService) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.projectService = projectService;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        this.guardrailsResourceClient = new GuardrailsResourceClient(this.client, baseURI);
        this.guardrailsGenerator = new GuardrailsGenerator();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
    }

    private void mockGetWorkspaceIdByName(String workspaceName, String workspaceId) {
        AuthTestUtils.mockGetWorkspaceIdByName(wireMock.server(), workspaceName, workspaceId);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createProject(Project project) {
        return createProject(project, API_KEY, TEST_WORKSPACE);
    }

    private UUID createProject(Project project, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(project))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            return TestUtils.getIdFromLocation(actualResponse.getLocation());
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
                    arguments(okApikey, true, null),
                    arguments(fakeApikey, false, UNAUTHORIZED_RESPONSE),
                    arguments("", false, NO_API_KEY_RESPONSE));
        }

        Stream<Arguments> publicCredentials() {
            return Stream.of(
                    arguments(okApikey, PRIVATE),
                    arguments(fakeApikey, PUBLIC),
                    arguments("", PUBLIC));
        }

        Stream<Arguments> getProjectPublicCredentials() {
            return Stream.of(
                    arguments(okApikey, PRIVATE, 200),
                    arguments(okApikey, PUBLIC, 200),
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
        @MethodSource("credentials")
        @DisplayName("create project: when api key is present, then return proper response")
        void createProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var project = factory.manufacturePojo(Project.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.entity(project, MediaType.APPLICATION_JSON_TYPE))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("getProjectPublicCredentials")
        @DisplayName("get project by id: when api key is present, then return proper response")
        void getProjectById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, Visibility visibility,
                int expectedCode) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            var id = createProject(factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build());

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(id));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update project: when api key is present, then return proper response")
        void updateProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var id = createProject(factory.manufacturePojo(Project.class), okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(factory.manufacturePojo(ProjectUpdate.class)))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete project: when api key is present, then return proper response")
        void deleteProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var id = createProject(factory.manufacturePojo(Project.class), okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        @DisplayName("get projects: when api key is present, then return proper response")
        void getProjects__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, Visibility visibility) {

            var workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            var projects = prepareProjectsListWithOnePublic();
            projects.forEach(project -> createProject(project, okApikey, workspaceName));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualResponse.hasEntity()).isTrue();
                var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

                if (visibility == PRIVATE) {
                    assertThat(actualEntity.content()).hasSize(projects.size());
                } else {
                    assertThat(actualEntity.content()).hasSize(1);
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

        Stream<Arguments> getProjectPublicCredentials() {
            return Stream.of(
                    arguments(sessionToken, PRIVATE, "OK_" + UUID.randomUUID(), 200),
                    arguments(sessionToken, PUBLIC, "OK_" + UUID.randomUUID(), 200),
                    arguments(fakeSessionToken, PRIVATE, UUID.randomUUID().toString(), 404),
                    arguments(fakeSessionToken, PUBLIC, UUID.randomUUID().toString(), 200));
        }

        Stream<Arguments> publicCredentials() {
            return Stream.of(
                    arguments(sessionToken, PRIVATE, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, PUBLIC, UUID.randomUUID().toString()));
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
        @MethodSource("credentials")
        @DisplayName("create project: when session token is present, then return proper response")
        void createProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var project = factory.manufacturePojo(Project.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.entity(project, MediaType.APPLICATION_JSON_TYPE))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("getProjectPublicCredentials")
        @DisplayName("get project by id: when session token is present, then return proper response")
        void getProjectById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            var id = createProject(factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build());
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(id));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update project: when session token is present, then return proper response")
        void updateProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var id = createProject(factory.manufacturePojo(Project.class));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(factory.manufacturePojo(ProjectUpdate.class)))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete project: when session token is present, then return proper response")
        void deleteProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var id = createProject(factory.manufacturePojo(Project.class));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        @DisplayName("get projects: when session token is present, then return proper response")
        void getProjects__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName) {

            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            var projects = prepareProjectsListWithOnePublic();
            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualResponse.hasEntity()).isTrue();
                var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

                if (visibility == PRIVATE) {
                    assertThat(actualEntity.content()).hasSize(projects.size());
                } else {
                    assertThat(actualEntity.content()).hasSize(1);
                }
            }
        }

    }

    @Nested
    @DisplayName("Retrieve Project:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetrieveProjectTest {

        @Test
        @DisplayName("when project exists, then return project")
        void getProjectById__whenProjectExists__thenReturnProject() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = factory.manufacturePojo(Project.class);

            var id = createProject(project, apiKey, workspaceName);

            project = buildProjectStats(project.toBuilder().id(id).build(), apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(ProjectRetrieve.builder().name(project.name()).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualEntity = actualResponse.readEntity(Project.class);
                assertThat(actualEntity)
                        .usingRecursiveComparison()
                        .ignoringFields(IGNORED_FIELD_MIN)
                        .ignoringCollectionOrder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "totalEstimatedCost")
                        .isEqualTo(project);
            }
        }

        @ParameterizedTest
        @DisplayName("when retrieve request is invalid, then return error")
        @MethodSource
        void getProjectById__whenRetrieveRequestIsInvalid__thenReturnError(ProjectRetrieve retrieve, String error,
                int expectedStatus) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(retrieve))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains(error);
            }
        }

        Stream<Arguments> getProjectById__whenRetrieveRequestIsInvalid__thenReturnError() {
            return Stream.of(
                    arguments(ProjectRetrieve.builder().name("").build(), "name must not be blank", 422),
                    arguments(ProjectRetrieve.builder().name(null).build(), "name must not be blank", 422),
                    arguments(ProjectRetrieve.builder().name(UUID.randomUUID().toString()).build(), "Project not found",
                            404));
        }

    }

    @Nested
    @DisplayName("Get:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindProject {

        @Test
        @DisplayName("Success")
        void getProjects() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List.of(
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    Project.builder()
                            .name("The most expressive LLM: " + UUID.randomUUID()
                                    + " \uD83D\uDE05\uD83E\uDD23\uD83D\uDE02\uD83D\uDE42\uD83D\uDE43\uD83E\uDEE0")
                            .description("Emoji Test \uD83E\uDD13\uD83E\uDDD0")
                            .build(),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class))
                    .forEach(project -> ProjectsResourceTest.this.createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(10);
            assertThat(actualEntity.content()).hasSize(10);
            assertThat(actualEntity.page()).isEqualTo(1);
        }

        @Test
        @DisplayName("when limit is 5 but there are 10 projects, then return 5 projects and total 10")
        void getProjects__whenLimitIs5ButThereAre10Projects__thenReturn5ProjectsAndTotal10() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            List.of(
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class))
                    .forEach(project -> ProjectsResourceTest.this.createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 5)
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(5);
            assertThat(actualEntity.content()).hasSize(5);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(10);
        }

        @Test
        @DisplayName("when fetching all project without specifying sorting, then return project sorted by created date")
        void getProjects__whenFetchingAllProject__thenReturnProjectSortedByCreatedDate() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = List.of(
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build());

            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 5)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(5);

            var actualProjects = actualEntity.content();
            assertThat(projects.get(4).name()).isEqualTo(actualProjects.get(0).name());
            assertThat(projects.get(3).name()).isEqualTo(actualProjects.get(1).name());
            assertThat(projects.get(2).name()).isEqualTo(actualProjects.get(2).name());
            assertThat(projects.get(1).name()).isEqualTo(actualProjects.get(3).name());
            assertThat(projects.get(0).name()).isEqualTo(actualProjects.get(4).name());
        }

        @ParameterizedTest
        @MethodSource("sortDirectionProvider")
        @DisplayName("when fetching all projects with name sorting, then return projects sorted by name")
        void getProjects__whenSortingProjectsByName__thenReturnProjectSortedByName(Direction expected,
                Direction request) {
            final int NUM_OF_PROJECTS = 5;
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = IntStream.range(0, NUM_OF_PROJECTS)
                    .mapToObj(i -> factory.manufacturePojo(Project.class).toBuilder()
                            .name("TestName%03d".formatted(i))
                            .build())
                    .toList();

            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            var sorting = List.of(SortingField.builder()
                    .field(SortableFields.NAME)
                    .direction(request)
                    .build());

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", NUM_OF_PROJECTS)
                    .queryParam("sorting", URLEncoder.encode(JsonUtils.writeValueAsString(sorting),
                            StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(5);

            var actualProjects = actualEntity.content();
            if (expected == Direction.DESC) {
                for (int i = 0; i < NUM_OF_PROJECTS; i++) {
                    assertThat(projects.get(NUM_OF_PROJECTS - i - 1).name()).isEqualTo(actualProjects.get(i).name());
                }
            } else {
                for (int i = 0; i < NUM_OF_PROJECTS; i++) {
                    assertThat(projects.get(i).name()).isEqualTo(actualProjects.get(i).name());
                }
            }
        }

        @Test
        @DisplayName("when fetching projects with multiple sorting, then return an error")
        void getProjects__whenMultipleSorting__thenReturnAnError() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var sorting = List.of(
                    SortingField.builder()
                            .field(SortableFields.NAME)
                            .build(),
                    SortingField.builder()
                            .field(SortableFields.LAST_UPDATED_AT)
                            .build());

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 10)
                    .queryParam("sorting", URLEncoder.encode(JsonUtils.writeValueAsString(sorting),
                            StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

            var actualEntity = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

            assertThat(actualEntity.getMessage()).isEqualTo(SortingFactory.ERR_MULTIPLE_SORTING);

        }

        @ParameterizedTest
        @MethodSource("sortDirectionProvider")
        @DisplayName("when fetching all project with last trace sorting, then return projects sorted by last trace")
        void getProjects__whenSortingProjectsByLastTrace__thenReturnProjectSorted(Direction expected,
                Direction request) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = createProjectsWithLastTrace(apiKey, workspaceName);

            requestAndAssertLastTraceSorting(workspaceName, apiKey, projects, request, expected, 1, projects.size());
        }

        @Test
        @DisplayName("when fetching all project with last trace sorting and out of range pagination, then return empty list")
        void getProjects__whenSortingProjectsByLastTraceWithPagination__thenReturnEmptyList() {
            final int OUT_OF_RANGE_PAGE = 3;
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = createProjectsWithLastTrace(apiKey, workspaceName);

            requestAndAssertLastTraceSorting(workspaceName, apiKey, List.of(), Direction.DESC, Direction.DESC,
                    OUT_OF_RANGE_PAGE, projects.size());
        }

        @ParameterizedTest
        @MethodSource("sortDirectionProvider")
        @DisplayName("when sorting by last trace sorting projects with no traces, then return projects sorted by last trace or last updated")
        void getProjects__whenSortingProjectsByLastTraceAndNoTraceExists__thenReturnProjectSorted(Direction expected,
                Direction request) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> withTraceProjects = PodamFactoryUtils.manufacturePojoList(factory, Project.class);

            withTraceProjects = withTraceProjects.stream().map(project -> {
                UUID projectId = createProject(project, apiKey, workspaceName);
                List<UUID> traceIds = IntStream.range(0, 5)
                        .mapToObj(i -> createCreateTrace(project.name(), apiKey, workspaceName))
                        .toList();

                Trace trace = getTrace(traceIds.getLast(), apiKey, workspaceName);
                return project.toBuilder()
                        .id(projectId)
                        .lastUpdatedTraceAt(trace.lastUpdatedAt()).build();
            }).toList();

            // add a project with no traces
            List<Project> noTraceProjects = PodamFactoryUtils.manufacturePojoList(factory, Project.class)
                    .stream().map(project -> {
                        UUID projectId = createProject(project, apiKey, workspaceName);
                        return project.toBuilder().id(projectId).build();
                    }).toList();

            List<Project> allProjects = Stream.concat(withTraceProjects.stream(), noTraceProjects.stream()).toList();

            requestAndAssertLastTraceSorting(
                    workspaceName, apiKey, allProjects, request, expected, 1, allProjects.size());
        }

        public static Stream<Arguments> sortDirectionProvider() {
            return Stream.of(
                    Arguments.of(Named.of("non specified", null), Direction.ASC),
                    Arguments.of(Named.of("ascending", Direction.ASC), Direction.ASC),
                    Arguments.of(Named.of("descending", Direction.DESC), Direction.DESC));
        }

        private List<Project> createProjectsWithLastTrace(String apiKey, String workspaceName) {
            List<Project> projects = PodamFactoryUtils.manufacturePojoList(factory, Project.class);

            return projects.stream().map(project -> {
                UUID projectId = createProject(project, apiKey, workspaceName);
                List<UUID> traceIds = IntStream.range(0, 5)
                        .mapToObj(i -> createCreateTrace(project.name(), apiKey, workspaceName))
                        .toList();

                Trace trace = getTrace(traceIds.getLast(), apiKey, workspaceName);
                return project.toBuilder()
                        .id(projectId)
                        .lastUpdatedTraceAt(trace.lastUpdatedAt()).build();
            }).toList();
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("sort by non-sortable field should return an error")
        void getProjects__whenSortingProjectsByNonSortableField__thenReturnAnError(String sortField) {
            final int NUM_OF_PROJECTS = 5;
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var sorting = List.of(SortingField.builder()
                    .field(sortField)
                    .build());

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", NUM_OF_PROJECTS)
                    .queryParam("sorting", URLEncoder.encode(JsonUtils.writeValueAsString(sorting),
                            StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(actualEntity.getMessage())
                    .isEqualTo(SortingFactory.ERR_ILLEGAL_SORTING_FIELDS_TEMPLATE.formatted(sortField));
        }

        Stream<Arguments> getProjects__whenSortingProjectsByNonSortableField__thenReturnAnError() {
            return Stream.of(
                    Arguments.of(Named.of("non-sortable field", "created_by")),
                    Arguments.of(Named.of("non-sortable field", "last_updated_by")),
                    Arguments.of(Named.of("non-existing field", "imaginary")));
        }

        @Test
        @DisplayName("when searching by project name, then return full text search result")
        void getProjects__whenSearchingByProjectName__thenReturnFullTextSearchResult() {
            UUID projectSuffix = UUID.randomUUID();
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = List.of(
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("MySQL, realtime chatboot: " + projectSuffix).build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot using mysql: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot MYSQL expert: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot expert (my SQL): " + projectSuffix).build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot expert: " + projectSuffix)
                            .build());

            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", "MySql")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.total()).isEqualTo(3);
            assertThat(actualEntity.size()).isEqualTo(3);

            var actualProjects = actualEntity.content();
            assertThat(actualProjects.stream().map(Project::name).toList()).contains(
                    "MySQL, realtime chatboot: " + projectSuffix,
                    "Chatboot using mysql: " + projectSuffix,
                    "Chatboot MYSQL expert: " + projectSuffix);
        }

        @Test
        @DisplayName("when searching by project name fragments, then return full text search result")
        void getProjects__whenSearchingByProjectNameFragments__thenReturnFullTextSearchResult() {
            UUID projectSuffix = UUID.randomUUID();

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = List.of(
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("MySQL: " + projectSuffix).build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chat-boot using mysql: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("MYSQL CHATBOOT expert: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Expert Chatboot: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("My chat expert: " + projectSuffix)
                            .build());

            projects
                    .forEach(project -> ProjectsResourceTest.this.createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", "cha")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.total()).isEqualTo(4);
            assertThat(actualEntity.size()).isEqualTo(4);

            var actualProjects = actualEntity.content();

            assertThat(actualProjects.stream().map(Project::name).toList()).contains(
                    "Chat-boot using mysql: " + projectSuffix,
                    "MYSQL CHATBOOT expert: " + projectSuffix,
                    "Expert Chatboot: " + projectSuffix,
                    "My chat expert: " + projectSuffix);
        }

        @Test
        @DisplayName("when projects with traces, then return project with last updated trace at")
        void getProjects__whenProjectsHasTraces__thenReturnProjectWithLastUpdatedTraceAt() {

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = factory.manufacturePojo(Project.class);
            var project2 = factory.manufacturePojo(Project.class);
            var project3 = factory.manufacturePojo(Project.class);

            var id = createProject(project, apiKey, workspaceName);
            var id2 = createProject(project2, apiKey, workspaceName);
            var id3 = createProject(project3, apiKey, workspaceName);

            List<UUID> traceIds = IntStream.range(0, 5)
                    .mapToObj(i -> createCreateTrace(project.name(), apiKey, workspaceName))
                    .toList();

            List<UUID> traceIds2 = IntStream.range(0, 5)
                    .mapToObj(i -> createCreateTrace(project2.name(), apiKey, workspaceName))
                    .toList();

            List<UUID> traceIds3 = IntStream.range(0, 5)
                    .mapToObj(i -> createCreateTrace(project3.name(), apiKey, workspaceName))
                    .toList();

            Trace trace = getTrace(traceIds.getLast(), apiKey, workspaceName);
            Trace trace2 = getTrace(traceIds2.getLast(), apiKey, workspaceName);
            Trace trace3 = getTrace(traceIds3.getLast(), apiKey, workspaceName);

            Project expectedProject = project.toBuilder().id(id).lastUpdatedTraceAt(trace.lastUpdatedAt()).build();
            Project expectedProject2 = project2.toBuilder().id(id2).lastUpdatedTraceAt(trace2.lastUpdatedAt()).build();
            Project expectedProject3 = project3.toBuilder().id(id3).lastUpdatedTraceAt(trace3.lastUpdatedAt()).build();

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            assertThat(actualEntity.content().stream().map(Project::id).toList())
                    .isEqualTo(List.of(id3, id2, id));

            assertThat(actualEntity.content().get(0).lastUpdatedTraceAt())
                    .isEqualTo(expectedProject3.lastUpdatedTraceAt());
            assertThat(actualEntity.content().get(1).lastUpdatedTraceAt())
                    .isEqualTo(expectedProject2.lastUpdatedTraceAt());
            assertThat(actualEntity.content().get(2).lastUpdatedTraceAt())
                    .isEqualTo(expectedProject.lastUpdatedTraceAt());

            assertAllProjectsHavePersistedLastTraceAt(workspaceId, List.of(expectedProject, expectedProject2,
                    expectedProject3));
        }

        @Test
        @DisplayName("when projects with traces, spans, feedback scores, and usage, then return project aggregations")
        void getProjects__whenProjectsHasTracesSpansFeedbackScoresAndUsage__thenReturnProjectAggregations() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Comparator<Project> comparator = Comparator.comparing(Project::id).reversed();

            List<ProjectStatsSummaryItem> expectedProjectStats = getProjectStatsSummaryItems(apiKey, workspaceName,
                    comparator);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/stats")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(ProjectStatsSummary.class);
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(org.apache.http.HttpStatus.SC_OK);

            assertThat(expectedProjectStats).hasSameSizeAs(actualEntity.content());

            assertThat(actualEntity.content())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "totalEstimatedCost")
                    .isEqualTo(expectedProjectStats);
        }

        @Test
        @DisplayName("when projects with traces, spans, feedback scores, and usage and sorted by last updated trace at, then return project aggregations")
        void getProjects__whenProjectsHasTracesSpansFeedbackScoresAndUsageSortedLastTrace__thenReturnProjectAggregations() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Comparator<Project> comparator = Comparator.comparing(Project::lastUpdatedTraceAt).reversed();

            List<ProjectStatsSummaryItem> expectedProjectStats = getProjectStatsSummaryItems(apiKey, workspaceName,
                    comparator);

            var sorting = List.of(SortingField.builder()
                    .field(SortableFields.LAST_UPDATED_TRACE_AT)
                    .direction(Direction.DESC)
                    .build());

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("sorting", URLEncoder.encode(JsonUtils.writeValueAsString(sorting),
                            StandardCharsets.UTF_8))
                    .path("/stats")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(ProjectStatsSummary.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(expectedProjectStats).hasSameSizeAs(actualEntity.content());

            assertThat(actualEntity.content())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "totalEstimatedCost")
                    .isEqualTo(expectedProjectStats);
        }

        private List<ProjectStatsSummaryItem> getProjectStatsSummaryItems(String apiKey, String workspaceName,
                Comparator<Project> comparing) {
            var projects = PodamFactoryUtils.manufacturePojoList(factory, Project.class)
                    .parallelStream()
                    .map(project -> project.toBuilder()
                            .id(createProject(project, apiKey, workspaceName))
                            .totalEstimatedCost(null)
                            .usage(null)
                            .feedbackScores(null)
                            .duration(null)
                            .build())
                    .toList();

            return projects
                    .parallelStream()
                    .map(project -> buildProjectStats(project, apiKey, workspaceName))
                    .sorted(comparing)
                    .map(project -> ProjectStatsSummaryItem.builder()
                            .duration(project.duration())
                            .totalEstimatedCost(project.totalEstimatedCost())
                            .usage(project.usage())
                            .feedbackScores(project.feedbackScores())
                            .projectId(project.id())
                            .traceCount(project.traceCount())
                            .guardrailsFailedCount(project.guardrailsFailedCount())
                            .build())
                    .toList();
        }

        @Test
        @DisplayName("when projects without traces, spans, feedback scores, and usage, then return project aggregations")
        void getProjects__whenProjectsHasNoTracesSpansFeedbackScoresAndUsage__thenReturnProjectAggregations() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projects = PodamFactoryUtils.manufacturePojoList(factory, Project.class)
                    .parallelStream()
                    .map(project -> project.toBuilder()
                            .id(createProject(project, apiKey, workspaceName))
                            .totalEstimatedCost(null)
                            .usage(null)
                            .feedbackScores(null)
                            .duration(null)
                            .build())
                    .toList();

            List<ProjectStatsSummaryItem> expectedProjectStats = projects.parallelStream()
                    .map(project -> ProjectStatsSummaryItem.builder()
                            .duration(null)
                            .totalEstimatedCost(null)
                            .usage(null)
                            .feedbackScores(null)
                            .projectId(project.id())
                            .build())
                    .sorted(Comparator.comparing(ProjectStatsSummaryItem::projectId).reversed())
                    .toList();

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/stats")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(ProjectStatsSummary.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(expectedProjectStats).hasSameSizeAs(actualEntity.content());

            assertThat(actualEntity.content())
                    .usingRecursiveComparison()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "totalEstimatedCost")
                    .isEqualTo(expectedProjectStats);
        }

        @Test
        @DisplayName("when projects is with traces created in batch, then return project with last updated trace at")
        void getProjects__whenProjectsHasTracesBatch__thenReturnProjectWithLastUpdatedTraceAt() {
            // Use dedicated workspace to avoid collisions with other tests when finding all projects
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(API_KEY, workspaceName, workspaceId);

            var project1 = factory.manufacturePojo(Project.class);
            var project2 = factory.manufacturePojo(Project.class);
            var project3 = factory.manufacturePojo(Project.class);

            var id1 = createProject(project1, API_KEY, workspaceName);
            var id2 = createProject(project2, API_KEY, workspaceName);
            var id3 = createProject(project3, API_KEY, workspaceName);

            var traces1 = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(project1.name())
                            .lastUpdatedAt(null) // Server side
                            .build())
                    .toList();
            var traces2 = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(project2.name())
                            .build())
                    .toList();
            var traces3 = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(project3.name())
                            .lastUpdatedAt(Instant.now().plus(1, ChronoUnit.HOURS))
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(
                    Stream.of(traces1, traces2, traces3).flatMap(List::stream).toList(),
                    API_KEY,
                    workspaceName);

            var latestActualTrace1 = traceResourceClient.getById(traces1.getLast().id(), workspaceName, API_KEY);
            var latestActualTrace2 = traceResourceClient.getById(traces2.getLast().id(), workspaceName, API_KEY);
            var latestActualTrace3 = traceResourceClient.getById(traces3.getLast().id(), workspaceName, API_KEY);

            var expectedProject1 = project1.toBuilder().id(id1)
                    .lastUpdatedTraceAt(latestActualTrace1.lastUpdatedAt()).build();
            var expectedProject2 = project2.toBuilder().id(id2)
                    .lastUpdatedTraceAt(latestActualTrace2.lastUpdatedAt()).build();
            var expectedProject3 = project3.toBuilder().id(id3)
                    .lastUpdatedTraceAt(latestActualTrace3.lastUpdatedAt()).build();

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.hasEntity()).isTrue();
            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(actualEntity.content().stream().map(Project::id).toList())
                    .isEqualTo(List.of(id3, id2, id1));

            assertThat(actualEntity.content().get(0).lastUpdatedTraceAt())
                    .isEqualTo(expectedProject3.lastUpdatedTraceAt());
            assertThat(actualEntity.content().get(1).lastUpdatedTraceAt())
                    .isEqualTo(expectedProject2.lastUpdatedTraceAt());
            assertThat(actualEntity.content().get(2).lastUpdatedTraceAt())
                    .isEqualTo(expectedProject1.lastUpdatedTraceAt());

            assertAllProjectsHavePersistedLastTraceAt(
                    workspaceId, List.of(expectedProject1, expectedProject2, expectedProject3));
        }

        @Test
        @DisplayName("when updating a trace, then return project with last updated trace at")
        void getProjects__whenTraceIsUpdated__thenUpdateProjectsLastUpdatedTraceAt() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = factory.manufacturePojo(Project.class);

            var projectId = createProject(project, apiKey, workspaceName);

            UUID traceId = traceResourceClient.createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(project.name()).build(), apiKey, workspaceName);

            traceResourceClient.updateTrace(traceId, TraceUpdate.builder()
                    .tags(Set.of("tag1", "tag2"))
                    .projectName(project.name())
                    .build(), apiKey, workspaceName);

            Trace trace = getTrace(traceId, apiKey, workspaceName);

            Project expectedProject = project.toBuilder().id(projectId).lastUpdatedTraceAt(trace.lastUpdatedAt())
                    .build();

            assertAllProjectsHavePersistedLastTraceAt(workspaceId, List.of(expectedProject));
        }

        private void assertAllProjectsHavePersistedLastTraceAt(String workspaceId, List<Project> expectedProjects) {
            Awaitility.await().untilAsserted(() -> {
                List<Project> dbProjects = projectService.findByIds(workspaceId, expectedProjects.stream()
                        .map(Project::id).collect(Collectors.toUnmodifiableSet()));
                Map<UUID, Instant> actualLastTraceByProjectId = dbProjects.stream()
                        .collect(toMap(Project::id, Project::lastUpdatedTraceAt));
                Map<UUID, Instant> expectedLastTraceByProjectId = expectedProjects.stream()
                        .collect(toMap(Project::id, Project::lastUpdatedTraceAt));

                assertThat(actualLastTraceByProjectId)
                        .usingRecursiveComparison()
                        .withComparatorForType(TestComparators::compareMicroNanoTime, Instant.class)
                        .isEqualTo(expectedLastTraceByProjectId);
            });
        }
    }

    private Project buildProjectStats(Project project, String apiKey, String workspaceName) {
        var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                .map(trace -> {
                    Instant startTime = Instant.now();
                    Instant endTime = startTime.plusMillis(PodamUtils.getIntegerInRange(1, 1000));
                    return trace.toBuilder()
                            .projectName(project.name())
                            .startTime(startTime)
                            .endTime(endTime)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(startTime, endTime))
                            .build();
                })
                .toList();

        traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

        List<FeedbackScoreBatchItem> scores = PodamFactoryUtils.manufacturePojoList(factory,
                FeedbackScoreBatchItem.class);

        var guardrailsByTraceId = traces.stream()
                .collect(Collectors.toMap(Trace::id, trace -> guardrailsGenerator.generateGuardrailsForTrace(
                        trace.id(), randomUUID(), trace.projectName())));
        guardrailsByTraceId.values().forEach(guardrail -> guardrailsResourceClient.addBatch(
                guardrail, apiKey, workspaceName));

        traces = traces.stream().map(trace -> {
            List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .usage(spanResourceClient.getTokenUsage())
                            .model(spanResourceClient.randomModel().toString())
                            .provider(spanResourceClient.provider())
                            .traceId(trace.id())
                            .projectName(trace.projectName())
                            .totalEstimatedCost(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            List<FeedbackScoreBatchItem> feedbackScores = scores.stream()
                    .map(feedbackScore -> feedbackScore.toBuilder()
                            .projectId(project.id())
                            .projectName(project.name())
                            .id(trace.id())
                            .build())
                    .toList();

            traceResourceClient.feedbackScores(feedbackScores, apiKey, workspaceName);

            return trace.toBuilder()
                    .feedbackScores(
                            feedbackScores.stream()
                                    .map(score -> FeedbackScore.builder()
                                            .value(score.value())
                                            .name(score.name())
                                            .build())
                                    .toList())
                    .usage(StatsUtils.aggregateSpansUsage(spans))
                    .totalEstimatedCost(StatsUtils.aggregateSpansCost(spans))
                    .guardrailsValidations(GuardrailsMapper.INSTANCE.mapToValidations(
                            guardrailsByTraceId.get(trace.id())))
                    .build();
        }).toList();

        List<BigDecimal> durations = StatsUtils.calculateQuantiles(
                traces.stream()
                        .map(Trace::duration)
                        .toList(),
                List.of(0.5, 0.90, 0.99));

        return project.toBuilder()
                .duration(new PercentageValues(durations.get(0), durations.get(1), durations.get(2)))
                .totalEstimatedCost(getTotalEstimatedCost(traces))
                .usage(traces.stream()
                        .map(Trace::usage)
                        .flatMap(usage -> usage.entrySet().stream())
                        .collect(groupingBy(Map.Entry::getKey, averagingDouble(Map.Entry::getValue))))
                .feedbackScores(getScoreAverages(traces))
                .lastUpdatedTraceAt(traces.stream().map(Trace::lastUpdatedAt).max(Instant::compareTo).orElse(null))
                .traceCount((long) traces.size())
                .guardrailsFailedCount(traces.stream()
                        .map(Trace::guardrailsValidations)
                        .flatMap(List::stream)
                        .map(GuardrailsValidation::checks)
                        .flatMap(List::stream)
                        .filter(guardrail -> guardrail.result() == GuardrailResult.FAILED)
                        .count())
                .build();
    }

    private List<FeedbackScoreAverage> getScoreAverages(List<Trace> traces) {
        return traces.stream()
                .map(Trace::feedbackScores)
                .flatMap(List::stream)
                .collect(groupingBy(FeedbackScore::name,
                        BigDecimalCollectors.averagingBigDecimal(FeedbackScore::value)))
                .entrySet()
                .stream()
                .map(entry -> FeedbackScoreAverage.builder()
                        .name(entry.getKey())
                        .value(entry.getValue())
                        .build())
                .toList();
    }

    private double getTotalEstimatedCost(List<Trace> traces) {
        long count = traces.stream()
                .map(Trace::totalEstimatedCost)
                .filter(Objects::nonNull)
                .filter(cost -> cost.compareTo(BigDecimal.ZERO) > 0)
                .count();

        return traces.stream()
                .map(Trace::totalEstimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(count), ValidationUtils.SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    @Nested
    @DisplayName("Get: {id}")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetProject {

        @Test
        @DisplayName("Success")
        void getProjectById() {

            var now = Instant.now();

            var project = Project.builder().name("Test Project: " + UUID.randomUUID())
                    .description("Simple Test")
                    .lastUpdatedAt(now)
                    .createdAt(now)
                    .build();

            var id = createProject(project);

            assertProject(project.toBuilder().id(id)
                    .visibility(PRIVATE)
                    .lastUpdatedTraceAt(null)
                    .build());
        }

        @Test
        @DisplayName("when project not found, then return 404")
        void getProjectById__whenProjectNotFound__whenReturn404() {

            var id = UUID.randomUUID().toString();

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id).request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            assertThat(actualResponse.hasEntity()).isTrue();
            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Project not found");
        }

        @Test
        @DisplayName("when project has traces, then return project with last updated trace at")
        void getProjectById__whenProjectHasTraces__thenReturnProjectWithLastUpdatedTraceAt() {

            var project = factory.manufacturePojo(Project.class);

            var id = createProject(project);

            List<UUID> traceIds = IntStream.range(0, 5)
                    .mapToObj(i -> createCreateTrace(project.name(), API_KEY, TEST_WORKSPACE))
                    .toList();

            Trace trace = getTrace(traceIds.getLast(), API_KEY, TEST_WORKSPACE);

            Project expectedProject = project.toBuilder().id(id).lastUpdatedTraceAt(trace.lastUpdatedAt()).build();

            assertProject(expectedProject);
        }

    }

    private UUID createCreateTrace(String projectName, String apiKey, String workspaceName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();

        traceResourceClient.batchCreateTraces(List.of(trace), apiKey, workspaceName);
        return trace.id();
    }

    private Trace getTrace(UUID id, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE_TRACE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            return actualResponse.readEntity(Trace.class);
        }
    }

    private void assertProject(Project project) {
        assertProject(project, API_KEY, TEST_WORKSPACE);
    }

    private void assertProject(Project project, String apiKey, String workspaceName) {
        var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(project.id().toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        var actualEntity = actualResponse.readEntity(Project.class);

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

        assertThat(actualEntity)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(project);

        assertThat(actualEntity.lastUpdatedBy()).isEqualTo(USER);
        assertThat(actualEntity.createdBy()).isEqualTo(USER);

        assertThat(actualEntity.lastUpdatedTraceAt()).isEqualTo(project.lastUpdatedTraceAt());
        assertThat(actualEntity.createdAt()).isAfter(project.createdAt());
        assertThat(actualEntity.lastUpdatedAt()).isAfter(project.createdAt());
    }

    private void requestAndAssertLastTraceSorting(String workspaceName, String apiKey, List<Project> allProjects,
            Direction request, Direction expected, int page, int size) {
        var sorting = List.of(SortingField.builder()
                .field(SortableFields.LAST_UPDATED_TRACE_AT)
                .direction(request)
                .build());

        var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .queryParam("size", size)
                .queryParam("page", page)
                .queryParam("sorting", URLEncoder.encode(JsonUtils.writeValueAsString(sorting),
                        StandardCharsets.UTF_8))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
        assertThat(actualEntity.size()).isEqualTo(allProjects.size());
        assertThat(actualEntity.total()).isEqualTo(allProjects.size());
        assertThat(actualEntity.page()).isEqualTo(page);

        if (expected == Direction.DESC) {
            allProjects = allProjects.reversed();
        }

        assertThat(actualEntity.content()).usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                .containsExactlyElementsOf(allProjects);
    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateProject {

        private String name;

        @BeforeEach
        void setUp() {
            this.name = "Test Project: " + UUID.randomUUID();
        }

        @Test
        @DisplayName("Success")
        void create() {

            var project = factory.manufacturePojo(Project.class);

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.entity(project, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertProject(project.toBuilder()
                    .id(id)
                    .lastUpdatedTraceAt(null)
                    .build());
        }

        @Test
        @DisplayName("when workspace name is specified, then accept the request")
        void create__whenWorkspaceNameIsSpecified__thenAcceptTheRequest() {
            var project = factory.manufacturePojo(Project.class);

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(project))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                id = TestUtils.getIdFromLocation(actualResponse.getLocation());

            }

            assertProject(project.toBuilder()
                    .id(id)
                    .lastUpdatedTraceAt(null)
                    .build(), apiKey, workspaceName);
        }

        @Test
        @DisplayName("when workspace description is multiline, then accept the request")
        void create__whenDescriptionIsMultiline__thenAcceptTheRequest() {
            var project = factory.manufacturePojo(Project.class);

            project = project.toBuilder().description("Test Project\n\nMultiline Description").build();

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(project))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                assertThat(actualResponse.getHeaderString("Location")).matches(Pattern.compile(URL_PATTERN));

                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertProject(project.toBuilder().lastUpdatedTraceAt(null).id(id).build());
        }

        @Test
        @DisplayName("when description is null, then accept the request")
        void create__whenDescriptionIsNull__thenAcceptNameCreate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(Project.builder().name(name).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                assertThat(actualResponse.getHeaderString("Location")).matches(Pattern.compile(URL_PATTERN));
            }
        }

        @Test
        @DisplayName("when name is null, then reject the request")
        void create__whenNameIsNull__thenRejectNameCreate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(Project.builder().description("Test Project").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("name must not be blank");
            }
        }

        @Test
        @DisplayName("when project name already exists, then reject the request")
        void create__whenProjectNameAlreadyExists__thenRejectNameCreate() {

            String projectName = UUID.randomUUID().toString();

            Project project = Project.builder().name(projectName).build();

            createProject(project);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(project))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Project already exists");
            }
        }

        @Test
        @DisplayName("when projects with same name but different workspace, then accept the request")
        void create__whenProjectsHaveSameNameButDifferentWorkspace__thenAcceptTheRequest() {

            var project1 = factory.manufacturePojo(Project.class);

            String workspaceId = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String apiKey2 = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey2, workspaceName, workspaceId);

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(project1))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            var project2 = project1.toBuilder()
                    .id(factory.manufacturePojo(UUID.class))
                    .lastUpdatedTraceAt(null)
                    .build();

            UUID id2;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey2)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(project2))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();

                id2 = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertProject(project1.toBuilder().id(id).lastUpdatedTraceAt(null).build());
            assertProject(project2.toBuilder().id(id2).lastUpdatedTraceAt(null).build(), apiKey2, workspaceName);
        }
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateProject {

        private UUID projectId;
        private String name;

        @BeforeEach
        void setUp() {
            this.name = "Test Project: " + UUID.randomUUID();
            this.projectId = createProject(Project.builder()
                    .name(name)
                    .description("Simple Test")
                    .build());
        }

        @ParameterizedTest
        @ValueSource(strings = {"Simple Test 2", ""})
        @DisplayName("Success")
        void update(String descriptionUpdate) {
            String name = "Test Project: " + UUID.randomUUID();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().name(name).description(descriptionUpdate)
                                    .visibility(Visibility.PUBLIC)
                                    .build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var actualEntity = actualResponse.readEntity(Project.class);

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualEntity.description()).isEqualTo(descriptionUpdate);
                assertThat(actualEntity.visibility()).isEqualTo(Visibility.PUBLIC);
                assertThat(actualEntity.name()).isEqualTo(name);
            }
        }

        @Test
        @DisplayName("Not Found")
        void update__whenProjectNotFound__thenReturn404() {
            var id = UUID.randomUUID().toString();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(ProjectUpdate.builder()
                            .name("Test Project 2")
                            .description("Simple Test 2")
                            .build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Project not found");
            }
        }

        @Test
        @DisplayName("when description is null, then accept name update")
        void update__whenDescriptionIsNull__thenAcceptNameUpdate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(ProjectUpdate.builder().name("Test Project xxx").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var actualEntity = actualResponse.readEntity(Project.class);

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualEntity.description()).isEqualTo("Simple Test");
                assertThat(actualEntity.name()).isEqualTo("Test Project xxx");
            }
        }

        @Test
        @DisplayName("when name is null, then accept description update")
        void update__whenNameIsNull__thenAcceptDescriptionUpdate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().description("Simple Test xxx").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var actualEntity = actualResponse.readEntity(Project.class);

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualEntity.description()).isEqualTo("Simple Test xxx");
                assertThat(actualEntity.name()).isEqualTo(name);
            }
        }

        @Test
        @DisplayName("when name is blank, then reject the update")
        void update__whenNameIsBlank__thenRejectTheUpdate() {
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().description("Simple Test: ").name("").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("name must not be blank");
            }
        }
    }

    @Nested
    @DisplayName("Delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteProject {

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success")
        void delete(String projectName) {
            Project project = Project.builder()
                    .name(projectName)
                    .build();
            var id = createProject(project);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            }
        }

        private Stream<Arguments> delete() {
            return Stream.of(
                    Arguments.of(Named.of("Generic project", factory.manufacturePojo(String.class))),
                    Arguments.of(Named.of("Default project", DEFAULT_PROJECT)));
        }

        @Test
        @DisplayName("delete batch projects")
        void deleteBatch() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var ids = PodamFactoryUtils.manufacturePojoList(factory, Project.class).stream()
                    .map(project -> createProject(project, apiKey, workspaceName)).toList();
            var idsToDelete = ids.subList(0, 3);
            var notDeletedIds = ids.subList(3, ids.size());

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new BatchDelete(new HashSet<>(idsToDelete))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", ids.size())
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualEntity.size()).isEqualTo(notDeletedIds.size());
            assertThat(actualEntity.content().stream().map(Project::id).toList())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .isEqualTo(notDeletedIds);
        }
    }

    @Nested
    @DisplayName("Get Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackScoreNames {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("when get feedback score names, then return feedback score names")
        void findFeedbackScoreNames(boolean userProjectId) {

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

            traceResourceClient.createMultiValueScores(
                    multipleValuesFeedbackScores, project, apiKey, workspaceName);

            traceResourceClient.createMultiValueScores(List.of(names.getLast()),
                    project, apiKey, workspaceName);

            // Create unexpected feedback scores
            String unexpectedProjectName = UUID.randomUUID().toString();

            UUID unexpectedProjectId = projectResourceClient.createProject(unexpectedProjectName, apiKey,
                    workspaceName);
            Project unexpectedProject = projectResourceClient.getProject(unexpectedProjectId, apiKey, workspaceName);

            traceResourceClient.createMultiValueScores(otherNames, unexpectedProject,
                    apiKey, workspaceName);

            String projectIdsQueryParam = userProjectId ? JsonUtils.writeValueAsString(List.of(projectId)) : null;
            List<String> expectedNames = userProjectId
                    ? names
                    : Stream.of(names, otherNames).flatMap(List::stream).toList();

            var feedbackScoreNamesByProjectId = projectResourceClient.findFeedbackScoreNames(projectIdsQueryParam,
                    apiKey, workspaceName);
            assertFeedbackScoreNames(feedbackScoreNamesByProjectId, expectedNames);
        }
    }

    private List<Project> prepareProjectsListWithOnePublic() {
        var projects = PodamFactoryUtils.manufacturePojoList(factory, Project.class).stream()
                .map(project -> project.toBuilder()
                        .visibility(PRIVATE)
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
        projects.set(0, projects.getFirst().toBuilder().visibility(PUBLIC).build());

        return projects;
    }
}
