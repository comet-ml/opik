package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Project;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadIdentifier;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.Visibility;
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
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.api.resources.utils.traces.TracePageTestAssertion;
import com.comet.opik.api.resources.utils.traces.TraceStatsAssertion;
import com.comet.opik.api.resources.utils.traces.TraceStreamTestAssertion;
import com.comet.opik.api.resources.utils.traces.TraceTestAssertion;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.GuardrailsMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotFoundException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.com.google.common.collect.Lists;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.api.Visibility.PRIVATE;
import static com.comet.opik.api.Visibility.PUBLIC;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertTraceComment;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertUpdatedComment;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.QuotaLimitTestUtils.ERR_USAGE_LIMIT_EXCEEDED;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.PROJECT_NAME_NOT_FOUND_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.PROJECT_NOT_FOUND_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.utils.traces.TraceAssertions.IGNORED_FIELDS_TRACES;
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
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Traces Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/traces";
    private static final String URL_TEMPLATE_SPANS = "%s/v1/private/spans";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    public static final Map<Trace.TraceField, Function<Trace, Trace>> EXCLUDE_FUNCTIONS = new EnumMap<>(
            Trace.TraceField.class);

    static {
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.NAME, it -> it.toBuilder().name(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.START_TIME, it -> it.toBuilder().startTime(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.END_TIME, it -> it.toBuilder().endTime(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.INPUT, it -> it.toBuilder().input(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.OUTPUT, it -> it.toBuilder().output(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.METADATA, it -> it.toBuilder().metadata(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.TAGS, it -> it.toBuilder().tags(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.USAGE, it -> it.toBuilder().usage(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.ERROR_INFO, it -> it.toBuilder().errorInfo(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.CREATED_AT, it -> it.toBuilder().createdAt(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.CREATED_BY, it -> it.toBuilder().createdBy(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.LAST_UPDATED_BY, it -> it.toBuilder().lastUpdatedBy(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.FEEDBACK_SCORES, it -> it.toBuilder().feedbackScores(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.COMMENTS, it -> it.toBuilder().comments(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.GUARDRAILS_VALIDATIONS,
                it -> it.toBuilder().guardrailsValidations(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.SPAN_COUNT, it -> it.toBuilder().spanCount(0).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.TOTAL_ESTIMATED_COST,
                it -> it.toBuilder().totalEstimatedCost(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.THREAD_ID, it -> it.toBuilder().threadId(null).build());
        EXCLUDE_FUNCTIONS.put(Trace.TraceField.DURATION, it -> it.toBuilder().duration(null).build());
    }

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

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
    private GuardrailsResourceClient guardrailsResourceClient;
    private GuardrailsGenerator guardrailsGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
        this.guardrailsGenerator = new GuardrailsGenerator();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID getProjectId(String projectName, String workspaceName, String apiKey) {
        return projectResourceClient.getByName(projectName, apiKey, workspaceName).id();
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
        @MethodSource("publicCredentials")
        @DisplayName("get traces, when api key is present, then return proper response")
        void get__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility, int expectedCode) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = factory.manufacturePojo(Project.class).toBuilder().name(DEFAULT_PROJECT)
                    .visibility(visibility).build();
            projectResourceClient.createProject(project, okApikey, workspaceName);

            int tracesCount = setupTracesForWorkspace(workspaceName, okApikey);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", DEFAULT_PROJECT)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var response = actualResponse.readEntity(Trace.TracePage.class);
                    assertThat(response.content()).hasSize(tracesCount);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(DEFAULT_PROJECT));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void getById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility, int expectedCode) {

            publicCredentialsTest(apiKey, visibility, expectedCode,
                    id -> "/" + id, "project_id");
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnTraceStats(String apiKey,
                Visibility visibility, int expectedCode) {

            publicCredentialsTest(apiKey, visibility, expectedCode,
                    id -> "/stats", "project_name");

            publicCredentialsTest(apiKey, visibility, expectedCode,
                    id -> "/stats", "project_id");
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnTraceFeedbackScoresNames(String apiKey,
                Visibility visibility, int expectedCode) {

            publicCredentialsTest(apiKey, visibility, expectedCode,
                    id -> "/feedback-scores/names", "project_id");
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnTraceThreads(String apiKey,
                Visibility visibility, int expectedCode) {

            publicCredentialsTest(apiKey, visibility, expectedCode,
                    id -> "/threads", "project_name");

            publicCredentialsTest(apiKey, visibility, expectedCode,
                    id -> "/threads", "project_id");
        }

        private void publicCredentialsTest(String apiKey,
                Visibility visibility, int expectedCode,
                Function<UUID, String> urlSuffix, String queryParam) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = factory.manufacturePojo(Project.class).toBuilder().name(DEFAULT_PROJECT)
                    .visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, okApikey, workspaceName);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .build();
            var traceId = create(trace, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + urlSuffix.apply(traceId))
                    .queryParam(queryParam, "project_id".equals(queryParam) ? projectId : DEFAULT_PROJECT)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    if ("project_id".equals(queryParam)) {
                        assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                                .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                    } else {
                        assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                                .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(DEFAULT_PROJECT));
                    }
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnTraceThread(String apiKey,
                Visibility visibility, int expectedCode) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = factory.manufacturePojo(Project.class).toBuilder().name(DEFAULT_PROJECT)
                    .visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, okApikey, workspaceName);

            var threadId = UUID.randomUUID().toString();
            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .threadId(threadId)
                    .projectName(DEFAULT_PROJECT)
                    .build();
            create(trace, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/threads/retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity
                            .json(TraceThreadIdentifier.builder().projectId(projectId).threadId(threadId).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnSearchTrace(String apiKey,
                Visibility visibility, int expectedCode) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = factory.manufacturePojo(Project.class).toBuilder().name(DEFAULT_PROJECT)
                    .visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, okApikey, workspaceName);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(DEFAULT_PROJECT)
                    .build();
            create(trace, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/search")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity
                            .json(TraceSearchStreamRequest.builder().projectId(projectId).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
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

        Stream<Arguments> publicCredentials() {
            return Stream.of(
                    arguments(sessionToken, PRIVATE, "OK_" + UUID.randomUUID(), 200),
                    arguments(sessionToken, PUBLIC, "OK_" + UUID.randomUUID(), 200),
                    arguments(fakeSessionToken, PRIVATE, UUID.randomUUID().toString(), 404),
                    arguments(fakeSessionToken, PUBLIC, UUID.randomUUID().toString(), 200));
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
        @MethodSource("publicCredentials")
        @DisplayName("get traces, when session token is present, then return proper response")
        void get__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            Project project = factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, API_KEY, workspaceName);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(t -> t.toBuilder()
                            .projectId(null)
                            .projectName(project.name())
                            .feedbackScores(null)
                            .build())
                    .toList();

            traces.forEach(trace -> create(trace, API_KEY, workspaceName));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", project.name())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var response = actualResponse.readEntity(Trace.TracePage.class);
                    assertThat(response.content()).hasSize(traces.size());
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(project.name()));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void getById__whenApiKeyIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            publicCredentialsTest(sessionToken, visibility, workspaceName, expectedCode,
                    id -> "/" + id, "project_id");
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenSessionTokenIsPresent__thenReturnTraceStats(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            publicCredentialsTest(sessionToken, visibility, workspaceName, expectedCode,
                    id -> "/stats", "project_id");

            publicCredentialsTest(sessionToken, visibility, workspaceName, expectedCode,
                    id -> "/stats", "project_name");
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenSessionTokenIsPresent__thenReturnTraceFeedbackScoresNames(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            publicCredentialsTest(sessionToken, visibility, workspaceName, expectedCode,
                    id -> "/feedback-scores/names", "project_id");
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenSessionTokenIsPresent__thenReturnTraceThreads(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            publicCredentialsTest(sessionToken, visibility, workspaceName, expectedCode,
                    id -> "/threads", "project_id");

            publicCredentialsTest(sessionToken, visibility, workspaceName, expectedCode,
                    id -> "/threads", "project_name");
        }

        private void publicCredentialsTest(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode,
                Function<UUID, String> urlSuffix, String queryParam) {
            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            Project project = factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, API_KEY, workspaceName);

            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .projectName(project.name())
                    .build();
            var traceId = create(trace, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + urlSuffix.apply(traceId))
                    .queryParam(queryParam, "project_id".equals(queryParam) ? projectId : project.name())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    if ("project_id".equals(queryParam)) {
                        assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                                .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                    } else {
                        assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                                .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(project.name()));
                    }
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnTraceThread(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            Project project = factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, API_KEY, workspaceName);

            var threadId = UUID.randomUUID().toString();
            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .threadId(threadId)
                    .projectName(project.name())
                    .build();
            create(trace, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/threads/retrieve")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity
                            .json(TraceThreadIdentifier.builder().projectId(projectId).threadId(threadId).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnSearchTrace(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            Project project = factory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, API_KEY, workspaceName);

            var threadId = UUID.randomUUID().toString();
            var trace = createTrace()
                    .toBuilder()
                    .projectId(null)
                    .threadId(threadId)
                    .projectName(project.name())
                    .build();
            create(trace, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/search")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity
                            .json(TraceSearchStreamRequest.builder().projectId(projectId).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
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

        private final TraceStatsAssertion traceStatsAssertion = new TraceStatsAssertion(traceResourceClient);
        private final TraceTestAssertion traceTestAssertion = new TraceTestAssertion(traceResourceClient, USER);
        private final TraceStreamTestAssertion traceStreamTestAssertion = new TraceStreamTestAssertion(
                traceResourceClient, USER);

        private Stream<Arguments> getFilterTestArguments() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion));
        }

        private Stream<Arguments> equalAndNotEqualFilters() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(
                            "/traces/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStreamTestAssertion));
        }

        private Stream<Arguments> getUsageKeyArgs() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion,
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion,
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion,
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion,
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion,
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion,
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion,
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion,
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion,
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
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(
                            "/traces/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            traceStreamTestAssertion));
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
                    arguments("/traces/stats", traceStatsAssertion, arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/traces", traceTestAssertion, arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/traces/search", traceStreamTestAssertion,
                            arg.get()[0],
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
                                    Arguments.of("/search", TraceFilter.builder()
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
                                                // if no value is expected, create an invalid filter by an empty key
                                                .key(Operator.NO_VALUE_OPERATORS.contains(operator)
                                                        ? ""
                                                        : getKey(filter.getKey()))
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
                    arguments("", filter),
                    arguments("/search", filter)));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project name and project id are null, then return bad request")
        void whenProjectNameAndIdAreNull__thenReturnBadRequest(String endpoint, TracePageTestAssertion testAssertion) {

            Project project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            testAssertion.assertTest(null, projectId, API_KEY, TEST_WORKSPACE, List.of(), List.of(), List.of(),
                    List.of(), Map.of());
        }

        private Instant generateStartTime() {
            return Instant.now().minusMillis(randomNumber(1, 1000));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void findWithUsage(String endpoint, TracePageTestAssertion testAssertion) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .startTime(generateStartTime())
                            .usage(null)
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, API_KEY, TEST_WORKSPACE, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void findWithoutUsage(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project name is not empty, then return traces by project name")
        void whenProjectNameIsNotEmpty__thenReturnTracesByProjectName(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                        .duration(null)
                        .output(null)
                        .tags(null)
                        .feedbackScores(null)
                        .guardrailsValidations(null)
                        .build();

                traces.add(trace);
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project id is not empty, then return traces by project id")
        void whenProjectIdIsNotEmpty__thenReturnTracesByProjectId(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Trace trace = createTrace()
                    .toBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .duration(null)
                    .output(null)
                    .projectId(null)
                    .tags(null)
                    .feedbackScores(null)
                    .guardrailsValidations(null)
                    .build();

            create(trace, apiKey, workspaceName);

            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            var values = testAssertion.transformTestParams(List.of(), List.of(trace), List.of());

            testAssertion.assertTest(null, projectId, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when filtering by workspace name, then return traces filtered")
        void whenFilterWorkspaceName__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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
                            .guardrailsValidations(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces1, apiKey1, workspaceName1);
            traceResourceClient.batchCreateTraces(traces2, apiKey2, workspaceName2);

            var valueTraces1 = testAssertion.transformTestParams(traces1, traces1.reversed(), List.of());
            var valueTraces2 = testAssertion.transformTestParams(traces2, traces2.reversed(), List.of());

            testAssertion.assertTest(projectName1, null, apiKey1, workspaceName1, valueTraces1.expected(),
                    valueTraces1.unexpected(), valueTraces1.all(), List.of(), Map.of());
            testAssertion.assertTest(projectName1, null, apiKey2, workspaceName2, valueTraces2.expected(),
                    valueTraces2.unexpected(), valueTraces2.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when traces have cost estimation, then return total cost estimation")
        void whenTracesHaveCostEstimation__thenReturnTotalCostEstimation(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                        .duration(null)
                        .output(null)
                        .projectId(null)
                        .tags(null)
                        .feedbackScores(null)
                        .usage(null)
                        .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(null, projectId, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterIdAndNameEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterByThreadEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameStartsWith__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEndsWith__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameContains__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameNotContains__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterStartTimeEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterEndTimeEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterInputEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterOutputEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTotalEstimatedCostGreaterThen__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, List.of(expectedTrace), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterTotalEstimatedCostEqual_NotEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces, // Here we swap the expected and unexpected traces
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterMetadataEqualString__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());

        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsString__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanString__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanString__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTagsContains__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoresArgs")
        void whenFilterFeedbackScoresEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource
        void getTracesByProject__whenFilterFeedbackScoresIsEmpty__thenReturnTracesFiltered(
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
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
                            .feedbackScores(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()))
                            .totalEstimatedCost(null)
                            .guardrailsValidations(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(traces.size() - 1, traces.getLast().toBuilder().feedbackScores(null).build());
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.subList(0, traces.size() - 1).forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.FEEDBACK_SCORES)
                    .operator(operator)
                    .key(traces.getFirst().feedbackScores().getFirst().name())
                    .value("")
                    .build());
            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        private Stream<Arguments> getTracesByProject__whenFilterFeedbackScoresIsEmpty__thenReturnTracesFiltered() {
            return Stream.of(
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceTestAssertion),
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStatsAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStatsAssertion),
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStreamTestAssertion));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
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
                            .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getDurationArgs")
        void whenFilterByDuration__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
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
                                .guardrailsValidations(null)
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

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidOperatorForFieldTypeArgs")
        void whenFilterInvalidOperatorForFieldType__thenReturn400(String path, TraceFilter filter) {

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_BAD_REQUEST,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);

            Response actualResponse;
            if (path.equals("/search")) {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .post(Entity.json(TraceSearchStreamRequest.builder()
                                .projectName(projectName)
                                .filters(filters)
                                .build()));

            } else {

                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .get();
            }

            try (actualResponse) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(String path, TraceFilter filter) {
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

            Response actualResponse;

            if (path.equals("/search")) {

                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, apiKey)
                        .header(WORKSPACE_HEADER, workspaceName)
                        .post(Entity.json(TraceSearchStreamRequest.builder()
                                .projectName(projectName)
                                .filters(filters)
                                .build()));

            } else {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, apiKey)
                        .header(WORKSPACE_HEADER, workspaceName)
                        .get();
            }

            try (actualResponse) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterGuardrails__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
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
                            .feedbackScores(null)
                            .guardrailsValidations(null)
                            .comments(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var guardrailsByTraceId = traces.stream()
                    .collect(Collectors.toMap(Trace::id, trace -> guardrailsGenerator.generateGuardrailsForTrace(
                            trace.id(), randomUUID(), trace.projectName())));

            // set the first trace with failed guardrails
            guardrailsByTraceId.put(traces.getFirst().id(), guardrailsByTraceId.get(traces.getFirst().id()).stream()
                    .map(guardrail -> guardrail.toBuilder().result(GuardrailResult.FAILED).build())
                    .toList());

            // set the rest of traces with passed guardrails
            traces.subList(1, traces.size()).forEach(trace -> guardrailsByTraceId.put(trace.id(),
                    guardrailsByTraceId.get(trace.id()).stream()
                            .map(guardrail -> guardrail.toBuilder()
                                    .result(GuardrailResult.PASSED)
                                    .build())
                            .toList()));

            guardrailsByTraceId.values()
                    .forEach(guardrail -> guardrailsResourceClient.addBatch(guardrail, apiKey,
                            workspaceName));

            traces = traces.stream().map(trace -> trace.toBuilder()
                    .guardrailsValidations(GuardrailsMapper.INSTANCE.mapToValidations(
                            guardrailsByTraceId.get(trace.id())))
                    .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            // assert failed guardrails
            var filtersFailed = List.of(
                    TraceFilter.builder()
                            .field(TraceField.GUARDRAILS)
                            .operator(Operator.EQUAL)
                            .value(GuardrailResult.FAILED.getResult())
                            .build());

            var valuesFailed = testAssertion.transformTestParams(traces, List.of(traces.getFirst()),
                    traces.subList(1, traces.size()));
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, valuesFailed.expected(),
                    valuesFailed.unexpected(), valuesFailed.all(), filtersFailed, Map.of());

            // assert passed guardrails
            var filtersPassed = List.of(
                    TraceFilter.builder()
                            .field(TraceField.GUARDRAILS)
                            .operator(Operator.EQUAL)
                            .value(GuardrailResult.PASSED.getResult())
                            .build());

            var valuesPassed = testAssertion.transformTestParams(traces, traces.subList(1, traces.size()).reversed(),
                    List.of(traces.getFirst()));
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, valuesPassed.expected(),
                    valuesPassed.unexpected(), valuesPassed.all(), filtersPassed, Map.of());
        }
    }

    private BigDecimal calculateEstimatedCost(List<Span> spans) {
        return spans.stream()
                .map(span -> CostService.calculateCost(span.model(), span.provider(), span.usage(), null))
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
            assertThat(actualThread.lastUpdatedAt())
                    // Some JVMs can resolve higher than microseconds, such as nanoseconds in the Ubuntu AMD64 JVM
                    .isBetween(expectedThread.lastUpdatedAt().truncatedTo(ChronoUnit.MICROS), Instant.now());
        }
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
                                    .value(traces.stream().min(Comparator.comparing(Trace::startTime))
                                            .orElseThrow().input().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.LAST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                            .output().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.CREATED_AT)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(traces.stream().min(Comparator.comparing(Trace::createdAt))
                                            .orElseThrow().createdAt()
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
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
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
                    .numberOfMessages(traces.size() * 2L)
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
        return expectedTraces.isEmpty()
                ? List.of()
                : List.of(TraceThread.builder()
                        .firstMessage(expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                .input())
                        .lastMessage(expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                .output())
                        .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                        .startTime(),
                                expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                        .endTime()))
                        .projectId(projectId)
                        .createdBy(USER)
                        .startTime(expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                .startTime())
                        .endTime(expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                .endTime())
                        .numberOfMessages(expectedTraces.size() * 2L)
                        .id(threadId)
                        .createdAt(expectedTraces.stream().min(Comparator.comparing(Trace::createdAt)).orElseThrow()
                                .createdAt())
                        .lastUpdatedAt(
                                expectedTraces.stream().max(Comparator.comparing(Trace::lastUpdatedAt)).orElseThrow()
                                        .lastUpdatedAt())
                        .build());
    }

    @Nested
    @DisplayName("Find traces:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraces {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
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
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void searchWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
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

            TraceSearchStreamRequest streamRequest = TraceSearchStreamRequest.builder()
                    .truncate(truncate)
                    .projectName(projectName)
                    .limit(5)
                    .build();

            var actualTraces = traceResourceClient.getStreamAndAssertContent(API_KEY, TEST_WORKSPACE, streamRequest);

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

            TraceAssertions.assertTraces(actualTraces, expectedTraces, USER);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void whenUsingPagination__thenReturnTracesPaginated(boolean stream) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

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
                            .comments(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = traces.stream()
                    .sorted(Comparator.comparing(Trace::id).reversed())
                    .toList();

            int pageSize = 2;

            if (stream) {
                AtomicReference<UUID> lastId = new AtomicReference<>(null);
                Lists.partition(expectedTraces, pageSize)
                        .forEach(trace -> {
                            var actualTraces = traceResourceClient.getStreamAndAssertContent(apiKey, workspaceName,
                                    TraceSearchStreamRequest.builder()
                                            .projectName(projectName)
                                            .lastRetrievedId(lastId.get())
                                            .limit(pageSize)
                                            .build());

                            TraceAssertions.assertTraces(actualTraces, trace, USER);

                            lastId.set(actualTraces.getLast().id());
                        });
            } else {
                for (int i = 0; i < expectedTraces.size() / pageSize; i++) {
                    int page = i + 1;
                    getAndAssertPage(
                            page,
                            pageSize,
                            projectName,
                            null,
                            List.of(),
                            expectedTraces.subList(i * pageSize, Math.min((i + 1) * pageSize, expectedTraces.size())),
                            List.of(),
                            workspaceName,
                            apiKey,
                            List.of(),
                            traces.size(), Set.of());
                }
            }
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
                    sortingFields, Set.of());
        }

        @Test
        void createAndRetrieveTraces__spanCountReflectsActualSpans_andTotalCountMatches() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            // Create traces with varying spanCount values
            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .spanCount(i * 3) // e.g., 0, 3, 6, 9, 12
                            .usage(null)
                            .feedbackScores(null)
                            .endTime(Instant.now())
                            .comments(null)
                            .build())
                    .collect(Collectors.toList());

            int expectedTotalSpanCount = traces.stream().mapToInt(Trace::spanCount).sum();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // For each trace, create the actual number of spans matching the spanCount
            List<Span> allSpans = new ArrayList<>();
            for (Trace trace : traces) {
                List<Span> spansForTrace = IntStream.range(0, trace.spanCount())
                        .mapToObj(j -> factory.manufacturePojo(Span.class).toBuilder()
                                .projectName(projectName)
                                .traceId(trace.id())
                                .build())
                        .toList();
                allSpans.addAll(spansForTrace);
            }
            spanResourceClient.batchCreateSpans(allSpans, apiKey, workspaceName);

            // Retrieve traces from the API
            UUID projectId = getProjectId(projectName, workspaceName, apiKey);
            Trace.TracePage resultPage = traceResourceClient.getTraces(projectName, projectId, apiKey, workspaceName,
                    List.of(), List.of(), 100, Map.of());
            List<Trace> returnedTraces = resultPage.content();

            // Check that all created traces are present and have the correct spanCount
            for (Trace created : traces) {
                returnedTraces.stream()
                        .filter(returned -> returned.id().equals(created.id()))
                        .findFirst()
                        .ifPresentOrElse(returned -> assertThat(returned.spanCount())
                                .as("Trace with id %s should have spanCount %d", created.id(), created.spanCount())
                                .isEqualTo(created.spanCount()),
                                () -> assertThat(false)
                                        .as("Trace with id %s should be present", created.id())
                                        .isTrue());
            }

            int actualTotalSpanCount = returnedTraces.stream()
                    .filter(rt -> traces.stream().anyMatch(t -> t.id().equals(rt.id())))
                    .mapToInt(Trace::spanCount)
                    .sum();

            assertThat(actualTotalSpanCount)
                    .as("Total spanCount across all traces should match the expected total")
                    .isEqualTo(expectedTotalSpanCount);
        }
        private Stream<Arguments> getTracesByProject__whenSortingByValidFields__thenReturnTracesSorted() {

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
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::threadId), SortingField.builder()
                            .field(SortableFields.THREAD_ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::threadId).reversed(), SortingField.builder()
                            .field(SortableFields.THREAD_ID).direction(Direction.DESC).build()));
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

        @ParameterizedTest
        @EnumSource(Direction.class)
        void getTracesByProject__whenSortingByFeedbackScores__thenReturnTracesSorted(Direction direction) {
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
                            .build())
                    .map(trace -> trace.toBuilder()
                            .duration(trace.startTime().until(trace.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            List<FeedbackScoreBatchItem> scoreForTrace = PodamFactoryUtils.manufacturePojoList(factory,
                    FeedbackScoreBatchItem.class);

            List<FeedbackScoreBatchItem> allScores = new ArrayList<>();
            for (Trace trace : traces) {
                for (FeedbackScoreBatchItem item : scoreForTrace) {

                    if (traces.getLast().equals(trace) && scoreForTrace.getFirst().equals(item)) {
                        continue;
                    }

                    allScores.add(item.toBuilder()
                            .id(trace.id())
                            .projectName(trace.projectName())
                            .value(factory.manufacturePojo(BigDecimal.class).abs())
                            .build());
                }
            }

            traceResourceClient.feedbackScores(allScores, apiKey, workspaceName);

            var sortingField = new SortingField(
                    "feedback_scores.%s".formatted(scoreForTrace.getFirst().name()),
                    direction);

            Comparator<Trace> comparing = Comparator.comparing(
                    (Trace trace) -> trace.feedbackScores()
                            .stream()
                            .filter(score -> score.name().equals(scoreForTrace.getFirst().name()))
                            .findFirst()
                            .map(FeedbackScore::value)
                            .orElse(null),
                    direction == Direction.ASC
                            ? Comparator.nullsFirst(Comparator.naturalOrder())
                            : Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(Trace::id).reversed());

            var expectedTraces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .feedbackScores(allScores
                                    .stream()
                                    .filter(score -> score.id().equals(trace.id()))
                                    .map(scores -> FeedbackScore.builder()
                                            .name(scores.name())
                                            .value(scores.value())
                                            .categoryName(scores.categoryName())
                                            .source(scores.source())
                                            .reason(scores.reason())
                                            .build())
                                    .toList())
                            .build())
                    .sorted(comparing)
                    .toList();

            List<SortingField> sortingFields = List.of(sortingField);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, expectedTraces, List.of(), apiKey,
                    sortingFields, Set.of());
        }

        @ParameterizedTest
        @EnumSource(Trace.TraceField.class)
        void getTracesByProject__whenExcludeParamIdDefined__thenReturnSpanExcludingFields(Trace.TraceField field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder().projectName(projectName).build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            Map<UUID, Comment> expectedComments = traces
                    .stream()
                    .map(trace -> Map.entry(trace.id(),
                            traceResourceClient.generateAndCreateComment(trace.id(), apiKey, workspaceName, 201)))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            traces = traces.stream()
                    .map(span -> span.toBuilder()
                            .comments(List.of(expectedComments.get(span.id())))
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                    span.endTime()))
                            .build())
                    .toList();

            List<Span> spans = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(trace.projectName())
                            .traceId(trace.id())
                            .build())
                    .toList();

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            traces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .totalEstimatedCost(spans.stream()
                                    .filter(span -> span.traceId().equals(trace.id()))
                                    .map(Span::totalEstimatedCost)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                            .spanCount((int) spans.stream()
                                    .filter(span -> span.traceId().equals(trace.id()))
                                    .count())
                            .usage(spans.stream()
                                    .filter(span -> span.traceId().equals(trace.id()))
                                    .map(Span::usage)
                                    .flatMap(map -> map.entrySet().stream())
                                    .collect(Collectors.groupingBy(Map.Entry::getKey,
                                            Collectors.summingLong(Map.Entry::getValue))))
                            .build())
                    .toList();

            List<Trace> finalTraces = traces;
            List<FeedbackScoreBatchItem> scoreForSpan = IntStream.range(0, traces.size())
                    .mapToObj(i -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .projectName(finalTraces.get(i).projectName())
                            .id(finalTraces.get(i).id())
                            .build())
                    .toList();

            traceResourceClient.feedbackScores(scoreForSpan, apiKey, workspaceName);

            traces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .feedbackScores(
                                    scoreForSpan
                                            .stream()
                                            .filter(score -> score.id().equals(trace.id()))
                                            .map(scores -> FeedbackScore.builder()
                                                    .name(scores.name())
                                                    .value(scores.value())
                                                    .categoryName(scores.categoryName())
                                                    .source(scores.source())
                                                    .reason(scores.reason())
                                                    .build())
                                            .toList())
                            .build())
                    .toList();

            List<Guardrail> guardrailsByTraceId = traces.stream()
                    .map(trace -> guardrailsGenerator.generateGuardrailsForTrace(trace.id(), randomUUID(),
                            trace.projectName()))
                    .flatMap(Collection::stream)
                    .toList();

            traces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .guardrailsValidations(
                                    GuardrailsMapper.INSTANCE.mapToValidations(
                                            guardrailsByTraceId
                                                    .stream()
                                                    .filter(gr -> gr.entityId().equals(trace.id()))
                                                    .toList()))
                            .build())
                    .toList();

            guardrailsResourceClient.addBatch(guardrailsByTraceId, apiKey, workspaceName);

            traces = traces.stream()
                    .map(span -> EXCLUDE_FUNCTIONS.get(field).apply(span))
                    .toList();

            Set<Trace.TraceField> exclude = Set.of(field);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey,
                    List.of(), exclude);

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
                apiKey, null, Set.of());
    }

    private void getAndAssertPage(String workspaceName, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> traces,
            List<Trace> expectedTraces,
            List<Trace> unexpectedTraces,
            String apiKey,
            List<SortingField> sortingFields,
            Set<Trace.TraceField> exclude) {
        int page = 1;

        int size = traces.size() + expectedTraces.size() + unexpectedTraces.size();
        getAndAssertPage(page, size, projectName, projectId, filters, expectedTraces, unexpectedTraces,
                workspaceName, apiKey, sortingFields, expectedTraces.size(), exclude);
    }

    private void getAndAssertPage(int page, int size, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String workspaceName, String apiKey,
            List<SortingField> sortingFields, int total, Set<Trace.TraceField> exclude) {

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

        if (CollectionUtils.isNotEmpty(exclude)) {
            target = target.queryParam("exclude", toURLEncodedQueryParam(List.copyOf(exclude)));
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
        assertThat(actualPage.total()).isEqualTo(total);

        TraceAssertions.assertTraces(actualTraces, expectedTraces, unexpectedTraces, USER);
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
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(SpanAssertions.IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedSpans);
            SpanAssertions.assertIgnoredFields(actualSpans, expectedSpans, USER);

            if (!unexpectedSpans.isEmpty()) {
                assertThat(actualSpans)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(SpanAssertions.IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedSpans);
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
                    .duration(null)
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
                .usage(null)
                .build();
    }

    private UUID create(Trace trace, String apiKey, String workspaceName) {
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private void create(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        traceResourceClient.feedbackScore(entityId, score, workspaceName, apiKey);
    }

    private Trace getAndAssert(Trace expectedTrace, UUID expectedProjectId, String apiKey, String workspaceName) {
        var actualTrace = traceResourceClient.getById(expectedTrace.id(), workspaceName, apiKey);

        if (expectedProjectId == null) {
            assertThat(actualTrace.projectId()).isNotNull();
        } else {
            assertThat(actualTrace.projectId()).isEqualTo(expectedProjectId);
        }

        TraceAssertions.assertTraces(List.of(actualTrace), List.of(expectedTrace), USER);

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
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var projectId = projectResourceClient.getByName(trace.projectName(), API_KEY, TEST_WORKSPACE).id();
            getAndAssert(trace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when creating traces with different project names, then return created traces")
        void create__whenCreatingTracesWithDifferentProjectNames__thenReturnCreatedTraces() {
            var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                    // when project name is null, uses the default project
                    .projectName(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var trace2 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(32))
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace1, API_KEY, TEST_WORKSPACE);
            traceResourceClient.createTrace(trace2, API_KEY, TEST_WORKSPACE);

            var projectId1 = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();
            var projectId2 = projectResourceClient.getByName(trace2.projectName(), API_KEY, TEST_WORKSPACE).id();
            getAndAssert(trace1, projectId1, API_KEY, TEST_WORKSPACE);
            getAndAssert(trace2, projectId2, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createWithMissingFields() {
            var trace = Trace.builder()
                    .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(32))
                    .startTime(Instant.now())
                    .createdAt(Instant.now())
                    .build();
            var id = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var expectedTrace = trace.toBuilder().id(id).build();
            getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace input is big, then accept and create trace")
        void createAndGet__whenTraceInputIsBig__thenReturnSpan() {
            int size = 1000;
            var jsonMap = IntStream.range(0, size)
                    .mapToObj(
                            i -> Map.entry(
                                    RandomStringUtils.secure().nextAlphabetic(10),
                                    RandomStringUtils.secure().nextAlphabetic(size)))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .input(JsonUtils.readTree(jsonMap))
                    .output(JsonUtils.readTree(jsonMap))
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            getAndAssert(trace, null, API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.QuotaLimitTestUtils#quotaLimitsTestProvider")
        void testQuotasLimit_whenLimitIsEmptyOrNotReached_thenAcceptCreation(
                List<Quota> quotas, boolean isLimitReached) {
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, workspaceName, workspaceId, USER, quotas);

            var trace = factory.manufacturePojo(Trace.class);
            try (var actualResponse = traceResourceClient.callCreateTrace(trace, API_KEY, workspaceName)) {

                if (isLimitReached) {
                    assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_PAYMENT_REQUIRED);
                    var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                            HttpStatus.SC_PAYMENT_REQUIRED, ERR_USAGE_LIMIT_EXCEEDED);
                    var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(actualError).isEqualTo(expectedError);
                } else {
                    assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
                }
            }
        }
    }

    @Nested
    @DisplayName("Batch:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchInsert {

        Stream<Arguments> batch__whenCreateTraces__thenReturnNoContent() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            return Stream.of(
                    arguments(projectName, projectName),
                    arguments(null, DEFAULT_PROJECT));
        }

        @ParameterizedTest
        @MethodSource
        void batch__whenCreateTraces__thenReturnNoContent(String projectName, String expectedProjectName) {
            // Use dedicated workspace to avoid collisions with other tests in the default project
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(API_KEY, workspaceName, workspaceId);

            var expectedTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(expectedTraces, API_KEY, workspaceName);

            getAndAssertPage(
                    workspaceName,
                    expectedProjectName.toUpperCase(), // Testing case sensitivity
                    null,
                    List.of(),
                    List.of(),
                    expectedTraces.reversed(),
                    List.of(),
                    API_KEY);
        }

        @Test
        void batch__whenSendingMultipleTracesWithSameId__thenReturn422() {
            var id = generator.generate();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .id(id)
                            .build())
                    .toList();
            try (var actualResponse = traceResourceClient.callBatchCreateTraces(traces, API_KEY, TEST_WORKSPACE)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode())
                        .isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                assertThat(actualResponse.hasEntity()).isTrue();
                var actualErrorMessage = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                var expectedErrorMessage = new io.dropwizard.jersey.errors.ErrorMessage(
                        HttpStatus.SC_UNPROCESSABLE_ENTITY, "Duplicate trace id '%s'".formatted(id));
                assertThat(actualErrorMessage).isEqualTo(expectedErrorMessage);
            }
        }

        @Test
        void batch__whenMissingFields__thenReturnNoContent() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var expectedTraces = IntStream.range(0, 5)
                    .mapToObj(i -> Trace.builder()
                            .projectName(projectName)
                            .startTime(Instant.now())
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(expectedTraces, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void upsert() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            // Ingesting traces with the minimum required fields
            var expectedTraces0 = IntStream.range(0, 5)
                    .mapToObj(i -> Trace.builder()
                            .projectName(projectName)
                            .id(generator.generate())
                            .startTime(Instant.now())
                            .createdAt(Instant.now())
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(expectedTraces0, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    null,
                    List.of(),
                    List.of(),
                    expectedTraces0.reversed(),
                    List.of(),
                    API_KEY);

            // The traces are overwritten, by using a server side generated last_updated_at
            var expectedTraces1 = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .id(expectedTraces0.get(i).id())
                            .name("name-01-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .startTime(expectedTraces0.get(i).startTime())
                            .lastUpdatedAt(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(expectedTraces1, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    null,
                    List.of(),
                    List.of(),
                    expectedTraces1.reversed(), // Finds the updated
                    expectedTraces0, // Does not find the previous
                    API_KEY);

            // The trace are overwritten, by using a client side generated last_updated_at
            var expectedTraces2 = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .id(expectedTraces0.get(i).id())
                            .name("name-02-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .startTime(expectedTraces0.get(i).startTime())
                            .lastUpdatedAt(Instant.now().plus(1, ChronoUnit.DAYS))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(expectedTraces2, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    null,
                    List.of(),
                    List.of(),
                    expectedTraces2.reversed(), // Finds the updated
                    expectedTraces1, // Does not find the previous
                    API_KEY);

            // The trace is not overwritten, the client side last_updated_at is older
            var unexpectedTraces = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .id(expectedTraces0.get(i).id())
                            .name("name-03-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .startTime(expectedTraces0.get(i).startTime())
                            .lastUpdatedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(unexpectedTraces, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    null,
                    List.of(),
                    List.of(),
                    expectedTraces2.reversed(), // finds the previous
                    unexpectedTraces, // Does not find the update attempt
                    API_KEY);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.QuotaLimitTestUtils#quotaLimitsTestProvider")
        void testQuotasLimit_whenLimitIsEmptyOrNotReached_thenAcceptCreation(
                List<Quota> quotas, boolean isLimitReached) {
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, workspaceName, workspaceId, USER, quotas);

            var trace = factory.manufacturePojo(Trace.class);
            try (var actualResponse = traceResourceClient.callBatchCreateTraces(
                    List.of(trace), API_KEY, workspaceName)) {

                if (isLimitReached) {
                    assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_PAYMENT_REQUIRED);
                    var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                            HttpStatus.SC_PAYMENT_REQUIRED, ERR_USAGE_LIMIT_EXCEEDED);
                    var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(actualError).isEqualTo(expectedError);
                } else {
                    assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                }
            }
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
            trace = Trace.builder()
                    .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(32))
                    .id(generator.generate())
                    .startTime(Instant.now().minusSeconds(10))
                    .createdAt(Instant.now())
                    .build();
            id = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace does not exist and id is invalid, then return 400")
        void when__traceDoesNotExistAndIdIsInvalid__thenReturn400() {
            var id = UUID.randomUUID();
            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            try (var actualResponse = traceResourceClient.updateTrace(
                    id, traceUpdate, API_KEY, TEST_WORKSPACE, HttpStatus.SC_BAD_REQUEST)) {
                assertErrorResponse(
                        actualResponse, "Trace id must be a version 7 UUID", HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("when trace does not exist, then return create it")
        void when__traceDoesNotExist__thenReturnCreateIt() {
            var id = generator.generate();
            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .name(null)
                    .build();
            var expectedTrace = Trace.builder()
                    .id(id)
                    .name(null)
                    .startTime(Instant.EPOCH)
                    .endTime(traceUpdate.endTime())
                    .input(traceUpdate.input())
                    .output(traceUpdate.output())
                    .metadata(traceUpdate.metadata())
                    .tags(traceUpdate.tags())
                    .createdAt(Instant.now())
                    .errorInfo(traceUpdate.errorInfo())
                    .threadId(traceUpdate.threadId())
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var projectId = projectResourceClient.getByName(traceUpdate.projectName(), API_KEY, TEST_WORKSPACE).id();
            getAndAssert(expectedTrace, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when trace update and insert are processed out of other, then return trace")
        void when__traceUpdateAndInsertAreProcessedOutOfOther__thenReturnTrace() {
            var id = generator.generate();
            var createdAt = Instant.now();
            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var newTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(traceUpdate.projectName())
                    .id(id)
                    .createdAt(createdAt)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(newTrace, API_KEY, TEST_WORKSPACE);

            var expectedTrace = newTrace.toBuilder()
                    .name(traceUpdate.name())
                    .endTime(traceUpdate.endTime())
                    .input(traceUpdate.input())
                    .output(traceUpdate.output())
                    .metadata(traceUpdate.metadata())
                    .tags(traceUpdate.tags())
                    .errorInfo(traceUpdate.errorInfo())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            newTrace.startTime(), traceUpdate.endTime()))
                    .threadId(traceUpdate.threadId())
                    .build();
            getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when multiple trace update and insert are processed out of other and concurrent, then return trace")
        void when__multipleTraceUpdateAndInsertAreProcessedOutOfOtherAndConcurrent__thenReturnTrace() {
            var id = generator.generate();
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var traceUpdate1 = TraceUpdate.builder()
                    .projectName(projectName)
                    .metadata(factory.manufacturePojo(JsonNode.class))
                    .build();
            var traceUpdate2 = TraceUpdate.builder()
                    .projectName(projectName)
                    .input(factory.manufacturePojo(JsonNode.class))
                    .tags(PodamFactoryUtils.manufacturePojoSet(factory, String.class))
                    .build();
            var traceUpdate3 = TraceUpdate.builder()
                    .projectName(projectName)
                    .output(factory.manufacturePojo(JsonNode.class))
                    .endTime(Instant.now())
                    .build();
            var newTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(traceUpdate1.projectName())
                    .id(id)
                    .endTime(null)
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            var create = Mono.fromRunnable(() -> traceResourceClient.createTrace(newTrace, API_KEY, TEST_WORKSPACE));
            var update1 = Mono.fromRunnable(() -> traceResourceClient.updateTrace(
                    id, traceUpdate1, API_KEY, TEST_WORKSPACE));
            var update3 = Mono.fromRunnable(() -> traceResourceClient.updateTrace(
                    id, traceUpdate2, API_KEY, TEST_WORKSPACE));
            var update2 = Mono.fromRunnable(() -> traceResourceClient.updateTrace(
                    id, traceUpdate3, API_KEY, TEST_WORKSPACE));
            Flux.merge(update1, update2, create, update3).blockLast();

            var expectedTrace = newTrace.toBuilder()
                    .endTime(traceUpdate3.endTime())
                    .input(traceUpdate2.input())
                    .output(traceUpdate3.output())
                    .metadata(traceUpdate1.metadata())
                    .tags(traceUpdate2.tags())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            newTrace.startTime(), traceUpdate3.endTime()))
                    .build();
            getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("Success")
        void update() {
            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectName(trace.projectName())
                    .projectId(null)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var expectedTrace = trace.toBuilder()
                    .name(traceUpdate.name())
                    .endTime(traceUpdate.endTime())
                    .input(traceUpdate.input())
                    .output(traceUpdate.output())
                    .metadata(traceUpdate.metadata())
                    .tags(traceUpdate.tags())
                    .errorInfo(traceUpdate.errorInfo())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            trace.startTime(), traceUpdate.endTime()))
                    .threadId(traceUpdate.threadId())
                    .build();
            getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
        }

        Stream<Arguments> updateOnlyName() {
            var name = RandomStringUtils.secure().nextAlphanumeric(32);
            return Stream.of(
                    arguments(name, name),
                    arguments(null, null),
                    arguments("", null),
                    arguments("   ", null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyName(String name, String expectedName) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .name(name)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().name(expectedName).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.name()).isEqualTo(expectedName);
        }

        Stream<Instant> updateOnlyEndTime() {
            return Stream.of(Instant.now(), null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyEndTime(Instant endTime) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .endTime(endTime)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var expectedTrace = trace.toBuilder()
                    .endTime(endTime)
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(), endTime))
                    .build();
            var actualTrace = getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.endTime()).isEqualTo(traceUpdate.endTime());
        }

        Stream<JsonNode> updateOnlyInput() {
            return Stream.of(
                    factory.manufacturePojo(JsonNode.class),
                    JsonUtils.getJsonNodeFromString("{}"),
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyInput(JsonNode input) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .input(input)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().input(input).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.input()).isEqualTo(traceUpdate.input());
        }

        Stream<JsonNode> updateOnlyOutput() {
            return Stream.of(
                    factory.manufacturePojo(JsonNode.class),
                    JsonUtils.getJsonNodeFromString("{}"),
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyOutput(JsonNode output) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .output(output)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().output(output).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.output()).isEqualTo(traceUpdate.output());
        }

        Stream<JsonNode> updateOnlyMetadata() {
            return Stream.of(
                    factory.manufacturePojo(JsonNode.class),
                    JsonUtils.getJsonNodeFromString("{}"),
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyMetadata(JsonNode metadata) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .metadata(metadata)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().metadata(metadata).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.metadata()).isEqualTo(traceUpdate.metadata());
        }

        Stream<Arguments> updateOnlyTags() {
            var tags = PodamFactoryUtils.manufacturePojoSet(factory, String.class);
            return Stream.of(
                    arguments(tags, tags),
                    arguments(Set.of(), null),
                    arguments(null, null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyTags(Set<String> tags, Set<String> expectedTags) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .tags(tags)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().tags(expectedTags).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.tags()).isEqualTo(expectedTags);
        }

        Stream<ErrorInfo> updateOnlyErrorInfo() {
            return Stream.of(factory.manufacturePojo(ErrorInfo.class), null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyErrorInfo(ErrorInfo errorInfo) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .errorInfo(errorInfo)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().errorInfo(errorInfo).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.errorInfo()).isEqualTo(traceUpdate.errorInfo());
        }

        Stream<Arguments> updateOnlyThreadId() {
            var name = RandomStringUtils.secure().nextAlphanumeric(32);
            return Stream.of(
                    arguments(name, name),
                    arguments(null, null),
                    arguments("", null),
                    arguments("   ", null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyThreadId(String threadId, String expectedThreadId) {
            var traceUpdate = TraceUpdate.builder()
                    .projectName(trace.projectName())
                    .threadId(threadId)
                    .build();
            traceResourceClient.updateTrace(id, traceUpdate, API_KEY, TEST_WORKSPACE);

            var actualTrace = getAndAssert(
                    trace.toBuilder().threadId(expectedThreadId).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.threadId()).isEqualTo(expectedThreadId);
        }

        @Test
        @DisplayName("when updating using projectId, then accept update")
        void update__whenUpdatingUsingProjectId__thenAcceptUpdate() {
            var newTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(newTrace, API_KEY, TEST_WORKSPACE);

            var projectId = projectResourceClient.getByName(newTrace.projectName(), API_KEY, TEST_WORKSPACE).id();
            var traceUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(projectId)
                    .build();
            traceResourceClient.updateTrace(newTrace.id(), traceUpdate, API_KEY, TEST_WORKSPACE);

            var updatedTrace = newTrace.toBuilder()
                    .name(traceUpdate.name())
                    .metadata(traceUpdate.metadata())
                    .input(traceUpdate.input())
                    .output(traceUpdate.output())
                    .endTime(traceUpdate.endTime())
                    .tags(traceUpdate.tags())
                    .errorInfo(traceUpdate.errorInfo())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            newTrace.startTime(), traceUpdate.endTime()))
                    .threadId(traceUpdate.threadId())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                            traceUpdate.endTime()))
                    .build();
            getAndAssert(updatedTrace, projectId, API_KEY, TEST_WORKSPACE);
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
                    API_KEY, null, traces.size(), Set.of());
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
                    .duration(null)
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
                    .duration(null)
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
                    .duration(null)
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
                    .duration(null)
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
                    .duration(null)
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
                    .duration(null)
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
                    .duration(null)
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
                    .duration(null)
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

            projectResourceClient.createProject(projectName.toUpperCase(), API_KEY, TEST_WORKSPACE);

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
            create(trace, API_KEY, TEST_WORKSPACE);

            traceResourceClient.deleteTraceThreads(List.of(UUID.randomUUID().toString()), trace.projectName(), null,
                    API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when project neither project name or project id are specified, then return bad request")
        void deleteTraceThread__whenProjectNeitherProjectNameOrProjectIdAreSpecified__thenReturnBadRequest() {
            var trace = createTrace().toBuilder()
                    .threadId(UUID.randomUUID().toString())
                    .build();

            create(trace, API_KEY, TEST_WORKSPACE);

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

    private int setupTracesForWorkspace(String workspaceName, String okApikey) {
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

    private void mockGetWorkspaceIdByName(String workspaceName, String workspaceId) {
        AuthTestUtils.mockGetWorkspaceIdByName(wireMock.server(), workspaceName, workspaceId);
    }
}
