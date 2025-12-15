package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.BatchDeleteByProject;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchContainer;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem.FeedbackScoreBatchItemBuilder;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThread.TraceThreadPage;
import com.comet.opik.api.TraceThreadBatchUpdate;
import com.comet.opik.api.TraceThreadIdentifier;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AttachmentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.ThreadCommentResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.api.resources.utils.traces.TraceDBUtils;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.InRangeStrategy;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AttachmentPayloadUtilsTest;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.collect.Lists;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.awaitility.Awaitility;
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
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.Visibility.PRIVATE;
import static com.comet.opik.api.Visibility.PUBLIC;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComment;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertUpdatedComment;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.QuotaLimitTestUtils.ERR_USAGE_LIMIT_EXCEEDED;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.PROJECT_NAME_NOT_FOUND_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.PROJECT_NOT_FOUND_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.validation.InRangeValidator.MAX_ANALYTICS_DB;
import static com.comet.opik.api.validation.InRangeValidator.MAX_ANALYTICS_DB_PRECISION_9;
import static com.comet.opik.api.validation.InRangeValidator.MIN_ANALYTICS_DB;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Traces Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final GenericContainer<?> minIOContainer = MinIOContainerUtils.newMinIOContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer, minIOContainer)
                .join();
        String minioUrl = "http://%s:%d".formatted(minIOContainer.getHost(), minIOContainer.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .isMinIO(true)
                        .minioUrl(minioUrl)
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private ThreadCommentResourceClient threadCommentResourceClient;
    private AttachmentResourceClient attachmentResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.threadCommentResourceClient = new ThreadCommentResourceClient(client, baseURI);
        this.attachmentResourceClient = new AttachmentResourceClient(client);
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

            try (var actualResponse = traceResourceClient.callCreateTrace(trace, apiKey, workspaceName)) {
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

            try (var actualResponse = traceResourceClient.callUpdateTrace(id, update, apiKey, workspaceName)) {
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

            try (var actualResponse = traceResourceClient.callDeleteTrace(id, apiKey, workspaceName)) {
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

            try (var actualResponse = traceResourceClient.callGetTracesWithQueryParams(apiKey, workspaceName,
                    Map.of("project_name", DEFAULT_PROJECT))) {

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

            try (var actualResponse = traceResourceClient.callGetWithPath(urlSuffix.apply(traceId), queryParam,
                    "project_id".equals(queryParam) ? projectId.toString() : DEFAULT_PROJECT, apiKey, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callRetrieveThreadResponse(
                    TraceThreadIdentifier.builder().projectId(projectId).threadId(threadId).build(),
                    apiKey, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callSearchTracesStream(
                    TraceSearchStreamRequest.builder().projectId(projectId).build(),
                    apiKey, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callPutToPath(
                    id.toString() + "/feedback-scores", feedback, apiKey, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callPostToPath(
                    id.toString() + "/feedback-scores/delete",
                    DeleteFeedbackScore.builder().name("name").build(),
                    apiKey, workspaceName)) {

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
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            var id = create(trace, okApikey, workspaceName);

            List<FeedbackScoreBatchItem> scores = IntStream.range(0, 5)
                    .mapToObj(i -> FeedbackScoreBatchItem.builder()
                            .name("name" + i)
                            .id(id)
                            .value(BigDecimal.valueOf(i))
                            .source(ScoreSource.SDK)
                            .projectName(trace.projectName())
                            .build())
                    .collect(Collectors.toList());

            try (var actualResponse = traceResourceClient.callFeedbackScores(scores, apiKey, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callPostWithCookie(trace, sessionToken, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callUpdateTraceWithCookie(id, update, sessionToken,
                    workspaceName)) {
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

            try (var actualResponse = traceResourceClient.callDeleteTraceWithCookie(id, sessionToken, workspaceName)) {
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

            try (var actualResponse = traceResourceClient.callGetWithQueryParamAndCookie(
                    "project_name", project.name(), sessionToken, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callGetWithPathAndCookie(urlSuffix.apply(traceId), queryParam,
                    "project_id".equals(queryParam) ? projectId.toString() : project.name(), sessionToken,
                    workspaceName)) {

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
        void get__whenSessionTokenIsPresent__thenReturnTraceThread(String sessionToken,
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

            try (var actualResponse = traceResourceClient.callRetrieveThreadResponseWithCookie(
                    TraceThreadIdentifier.builder().projectId(projectId).threadId(threadId).build(),
                    sessionToken, workspaceName)) {

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
                    .threadId(threadId)
                    .projectName(project.name())
                    .build();

            create(trace, API_KEY, workspaceName);

            try (var actualResponse = traceResourceClient.callSearchTracesStreamWithCookie(
                    TraceSearchStreamRequest.builder().projectId(projectId).build(),
                    sessionToken, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callPutToPathWithCookie(
                    id.toString() + "/feedback-scores", feedback, sessionToken, workspaceName)) {

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

            try (var actualResponse = traceResourceClient.callPostToPathWithCookie(
                    id.toString() + "/feedback-scores/delete",
                    DeleteFeedbackScore.builder().name("name").build(),
                    sessionToken, workspaceName)) {

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
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            var id = create(trace, API_KEY, workspaceName);

            List<FeedbackScoreBatchItem> scores = IntStream.range(0, 5)
                    .mapToObj(i -> FeedbackScoreBatchItem.builder()
                            .name("name" + i)
                            .id(id)
                            .value(BigDecimal.valueOf(i))
                            .source(ScoreSource.SDK)
                            .projectName(trace.projectName())
                            .build())
                    .collect(Collectors.toList());

            try (var actualResponse = traceResourceClient.callFeedbackScoresWithCookie(scores, sessionToken,
                    workspaceName)) {

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

    private BigDecimal calculateEstimatedCost(List<Span> spans) {
        return spans.stream()
                .map(span -> CostService.calculateCost(span.model(), span.provider(), span.usage(), null))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<TraceThread> getExpectedThreads(List<Trace> expectedTraces, UUID projectId, String threadId,
            List<Span> spans, TraceThreadStatus status) {
        return getExpectedThreads(expectedTraces, projectId, threadId, spans, status, null);
    }

    private List<TraceThread> getExpectedThreads(List<Trace> expectedTraces, UUID projectId, String threadId,
            List<Span> spans, TraceThreadStatus status, List<FeedbackScore> feedbackScores) {

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
                        .totalEstimatedCost(Optional.ofNullable(getTotalEstimatedCost(spans))
                                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                                .orElse(null))
                        .usage(getUsage(spans))
                        .status(status)
                        .feedbackScores(feedbackScores)
                        .createdAt(expectedTraces.stream().min(Comparator.comparing(Trace::createdAt)).orElseThrow()
                                .createdAt())
                        .lastUpdatedAt(
                                expectedTraces.stream().max(Comparator.comparing(Trace::lastUpdatedAt)).orElseThrow()
                                        .lastUpdatedAt())
                        .build());
    }

    private Map<String, Long> getUsage(List<Span> spans) {
        return Optional.ofNullable(spans)
                .map(this::aggregateSpansUsage)
                .filter(not(Map::isEmpty))
                .orElse(null);
    }

    private BigDecimal getTotalEstimatedCost(List<Span> spans) {
        boolean shouldUseTotalEstimatedCostField = spans.stream().allMatch(span -> span.totalEstimatedCost() != null);

        if (shouldUseTotalEstimatedCostField) {
            return spans.stream()
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        return calculateEstimatedCost(spans);
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

        Map<String, String> queryParams = new HashMap<>();

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            queryParams.put("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        if (page > 0) {
            queryParams.put("page", String.valueOf(page));
        }

        if (size > 0) {
            queryParams.put("size", String.valueOf(size));
        }

        if (projectName != null) {
            queryParams.put("project_name", projectName);
        }

        if (projectId != null) {
            queryParams.put("project_id", projectId.toString());
        }

        if (CollectionUtils.isNotEmpty(exclude)) {
            queryParams.put("exclude", toURLEncodedQueryParam(List.copyOf(exclude)));
        }

        queryParams.put("filters", toURLEncodedQueryParam(filters));

        var actualResponse = traceResourceClient.callGetTracesWithQueryParams(apiKey, workspaceName, queryParams);

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
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("page", String.valueOf(page));
        queryParams.put("size", String.valueOf(size));
        if (projectName != null) {
            queryParams.put("project_name", projectName);
        }
        if (projectId != null) {
            queryParams.put("project_id", projectId.toString());
        }
        if (traceId != null) {
            queryParams.put("trace_id", traceId.toString());
        }
        if (type != null) {
            queryParams.put("type", type.toString());
        }
        queryParams.put("filters", toURLEncodedQueryParam(filters));

        try (var actualResponse = spanResourceClient.callGetSpansWithQueryParams(apiKey, workspaceName, queryParams)) {
            var actualPage = actualResponse.readEntity(Span.SpanPage.class);
            var actualSpans = actualPage.content();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(actualPage.page()).isEqualTo(page);
            assertThat(actualPage.size()).isEqualTo(expectedSpans.size());
            assertThat(actualPage.total()).isEqualTo(expectedTotal);

            SpanAssertions.assertSpan(actualSpans, expectedSpans, USER);

            if (!unexpectedSpans.isEmpty()) {
                assertThat(actualSpans)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(SpanAssertions.IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedSpans);
            }
        }
    }

    @Nested
    @DisplayName("Thread Feedback Scores Creation:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadFeedbackScoresCreation {

        @Test
        @DisplayName("When scoring batch of threads with valid data, then scores are persisted")
        void scoreBatchOfThreads_withValidData_thenScoresArePersisted() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name(projectName)
                    .build();

            UUID projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            var threadId1 = UUID.randomUUID().toString();
            var threadId2 = UUID.randomUUID().toString();

            // Create traces with threads first (threads need traces to exist)
            Trace trace1 = createTrace().toBuilder()
                    .threadId(threadId1)
                    .projectId(null)
                    .projectName(projectName)
                    .startTime(Instant.now().minusSeconds(5))
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            Trace trace2 = createTrace().toBuilder()
                    .threadId(threadId2)
                    .projectId(null)
                    .projectName(projectName)
                    .startTime(Instant.now().minusSeconds(2))
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            traceResourceClient.batchCreateTraces(List.of(trace1, trace2), apiKey, workspaceName);

            // Close thread
            Mono.delay(Duration.ofMillis(500)).block();
            traceResourceClient.closeTraceThread(threadId1, null, projectName, apiKey, workspaceName);
            traceResourceClient.closeTraceThread(threadId2, null, projectName, apiKey, workspaceName);

            List<FeedbackScoreBatchItemThread> scoreItems = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItemThread.class).stream()
                    .map(item -> item.toBuilder()
                            .threadId(threadId1)
                            .projectName(projectName)
                            .projectId(null) // Project ID is not required for thread scores
                            .build())
                    .collect(Collectors.toList());

            String score1 = RandomStringUtils.secure().nextAlphanumeric(10);
            String score2 = RandomStringUtils.secure().nextAlphanumeric(10);
            String score3 = RandomStringUtils.secure().nextAlphanumeric(10);
            String score4 = RandomStringUtils.secure().nextAlphanumeric(10);

            scoreItems.set(0, scoreItems.get(0).toBuilder()
                    .name(score1)
                    .build());

            scoreItems.set(1, scoreItems.get(1).toBuilder()
                    .name(score2)
                    .build());

            // Update only relevant fields for thread 2 scores
            scoreItems.set(2, scoreItems.get(2).toBuilder()
                    .threadId(threadId2)
                    .name(score1)
                    .build());

            scoreItems.set(3, scoreItems.get(3).toBuilder()
                    .threadId(threadId2)
                    .name(score3)
                    .build());

            scoreItems.set(4, scoreItems.get(4).toBuilder()
                    .name(score4)
                    .build());

            // Create expected feedback scores for thread 1
            Instant now = Instant.now();
            var expectedScores1 = List.of(
                    createExpectedFeedbackScore(scoreItems.get(0), now),
                    createExpectedFeedbackScore(scoreItems.get(1), now),
                    createExpectedFeedbackScore(scoreItems.get(4), now));

            // Create expected feedback scores for thread 2
            var expectedScores2 = List.of(
                    createExpectedFeedbackScore(scoreItems.get(2), now),
                    createExpectedFeedbackScore(scoreItems.get(3), now));

            // When
            traceResourceClient.threadFeedbackScores(scoreItems, apiKey, workspaceName);

            // Then - Get threads using getTraceThreads API
            var traceThreadPage = traceResourceClient.getTraceThreads(projectId, null, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            // Create expected threads using helper method with actual traces
            var expectedThread1 = getExpectedThreads(List.of(trace1), projectId, threadId1, List.of(),
                    TraceThreadStatus.INACTIVE, expectedScores1).getFirst();
            var expectedThread2 = getExpectedThreads(List.of(trace2), projectId, threadId2, List.of(),
                    TraceThreadStatus.INACTIVE, expectedScores2).getFirst();

            var expectedThreads = List.of(expectedThread2, expectedThread1);

            // Use TraceAssertions to verify threads and their feedback scores
            TraceAssertions.assertThreads(expectedThreads, traceThreadPage.content());
        }

        @ParameterizedTest
        @MethodSource("threadFeedbackScoreTestCases")
        @DisplayName("Thread feedback scores contract test")
        void threadFeedbackScores_contractTest(List<FeedbackScoreBatchItemThread> scores, int expectedStatus,
                String expectedError) {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // When
            try (var response = traceResourceClient.callThreadFeedbackScores(scores, apiKey, workspaceName)) {

                // Then
                assertThat(response.getStatus()).isEqualTo(expectedStatus);
                assertThat(response.readEntity(ErrorMessage.class).errors()).contains(expectedError);
            }
        }

        @Test
        @DisplayName("When scoring batch of threads with threads that are open, then return 409")
        void scoreBatchOfThreads_withThreadsAreOpen_thenReturns409() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            projectResourceClient.createProject(projectName, apiKey, workspaceName);

            List<FeedbackScoreBatchItemThread> scores = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItemThread.class).stream()
                    .map(item -> item.toBuilder()
                            .threadId(UUID.randomUUID().toString())
                            .projectName(projectName)
                            .projectId(null) // Project ID is not required for thread scores
                            .source(ScoreSource.SDK)
                            .build())
                    .collect(Collectors.toList());

            List<Trace> traces = scores.stream()
                    .map(item -> createTrace().toBuilder()
                            .threadId(item.threadId())
                            .projectId(null)
                            .projectName(projectName)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // When
            try (var response = traceResourceClient.callThreadFeedbackScores(scores, apiKey, workspaceName)) {

                // Then
                assertOpenThreadScoreConflict(scores, response);
            }
        }

        @Test
        @DisplayName("When scoring batch of threads with threads that dont exist, then return 409")
        void scoreBatchOfThreads_withThreadsDontExist_thenReturns409() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            projectResourceClient.createProject(projectName, apiKey, workspaceName);

            List<FeedbackScoreBatchItemThread> scores = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItemThread.class).stream()
                    .map(item -> item.toBuilder()
                            .threadId(UUID.randomUUID().toString())
                            .projectName(projectName)
                            .projectId(null) // Project ID is not required for thread scores
                            .source(ScoreSource.SDK)
                            .build())
                    .collect(Collectors.toList());

            // When
            try (var response = traceResourceClient.callThreadFeedbackScores(scores, apiKey, workspaceName)) {

                // Then
                assertOpenThreadScoreConflict(scores, response);
            }
        }

        private static void assertOpenThreadScoreConflict(List<FeedbackScoreBatchItemThread> scores,
                Response response) {
            String threadIds = scores.stream()
                    .map(FeedbackScoreItem::threadId)
                    .sorted()
                    .collect(Collectors.joining(", "));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
            assertThat(response.hasEntity()).isTrue();

            var errorMessage = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_CONFLICT);
            assertThat(errorMessage.getMessage())
                    .isEqualTo("Threads must be closed before scoring. Thread IDs are active: '[%s]'"
                            .formatted(threadIds));
        }

        Stream<Arguments> threadFeedbackScoreTestCases() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            // Valid case
            String score1 = RandomStringUtils.secure().nextAlphanumeric(10);

            var validScore = initThreadFeedbackItem()
                    .threadId(threadId)
                    .projectName(projectName)
                    .projectId(null)
                    .name(score1)
                    .build();

            // Missing thread ID case
            var missingThreadIdScore = initThreadFeedbackItem()
                    .threadId(null)
                    .projectName(projectName)
                    .projectId(null)
                    .name(score1)
                    .build();

            // Missing thread ID
            var blankThreadId = initThreadFeedbackItem()
                    .threadId("")
                    .projectName(projectName)
                    .projectId(null)
                    .name(score1)
                    .build();

            // Missing value
            var missingValueCase = initThreadFeedbackItem()
                    .threadId(threadId)
                    .projectName(projectName)
                    .projectId(null)
                    .name(score1)
                    .value(null)
                    .build();

            var missingNameCase = initThreadFeedbackItem()
                    .threadId(threadId)
                    .projectName(projectName)
                    .projectId(null)
                    .name(null)
                    .build();

            var blankNameCase = initThreadFeedbackItem()
                    .threadId(threadId)
                    .projectName(projectName)
                    .projectId(null)
                    .name("")
                    .build();

            var missingSourceCase = initThreadFeedbackItem()
                    .threadId(threadId)
                    .projectName(projectName)
                    .projectId(null)
                    .source(null)
                    .build();

            return Stream.of(
                    // Missing thread ID - 422 Unprocessable Entity
                    arguments(List.of(missingThreadIdScore), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "scores[0].threadId must not be blank"),

                    // Blank thread ID - 422 Unprocessable Entity
                    arguments(List.of(blankThreadId), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "scores[0].threadId must not be blank"),

                    // Missing value - 422 Unprocessable Entity
                    arguments(List.of(missingValueCase), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "scores[0].value must not be null"),

                    // Missing name - 422 Unprocessable Entity
                    arguments(List.of(missingNameCase), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "scores[0].name must not be blank"),

                    // Blank name - 422 Unprocessable Entity
                    arguments(List.of(blankNameCase), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "scores[0].name must not be blank"),

                    // Missing source - 422 Unprocessable Entity
                    arguments(List.of(missingSourceCase), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "scores[0].source must not be null"));
        }
    }

    private FeedbackScoreBatchItemThread.FeedbackScoreBatchItemThreadBuilder<?, ?> initThreadFeedbackItem() {
        return factory.manufacturePojo(FeedbackScoreBatchItemThread.class).toBuilder();
    }

    private FeedbackScore createExpectedFeedbackScore(FeedbackScoreItem item, Instant now) {
        return FeedbackScore.builder()
                .name(item.name())
                .value(item.value())
                .categoryName(item.categoryName())
                .source(item.source())
                .reason(item.reason())
                .lastUpdatedAt(now)
                .createdAt(now)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .build();
    }

    @Nested
    @DisplayName("Thread Feedback Scores Deletion:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadFeedbackScoresDeletion {

        @Test
        @DisplayName("When deleting thread feedback scores with valid data, then scores are deleted")
        void deleteThreadFeedbackScores_withValidData_thenScoresAreDeleted() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name(projectName)
                    .build();

            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            var threadId = UUID.randomUUID().toString();

            // Create trace with thread first (threads need traces to exist)
            Trace trace = createTrace().toBuilder()
                    .threadId(threadId)
                    .projectId(projectId)
                    .projectName(projectName)
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            traceResourceClient.createTrace(trace, apiKey, workspaceName);

            // Close thread
            Mono.delay(Duration.ofMillis(500)).block();
            traceResourceClient.closeTraceThread(threadId, null, projectName, apiKey, workspaceName);

            List<FeedbackScoreBatchItemThread> scoreItems = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItemThread.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .projectId(null)
                            .build())
                    .collect(Collectors.toList());

            // Create expected thread with initial scores
            Instant now = Instant.now();
            var expectedInitialScores = List.of(
                    createExpectedFeedbackScore(scoreItems.get(0), now),
                    createExpectedFeedbackScore(scoreItems.get(1), now),
                    createExpectedFeedbackScore(scoreItems.get(2), now),
                    createExpectedFeedbackScore(scoreItems.get(3), now),
                    createExpectedFeedbackScore(scoreItems.get(4), now));

            // When
            traceResourceClient.threadFeedbackScores(scoreItems, apiKey, workspaceName);

            // Then - Verify initial scores using getTraceThreads API
            var initialThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            var expectedInitialThread = getExpectedThreads(List.of(trace), projectId, threadId, List.of(),
                    TraceThreadStatus.INACTIVE, expectedInitialScores).getFirst();

            TraceAssertions.assertThreads(List.of(expectedInitialThread), initialThreadPage.content());

            Set<String> scoresToDelete = Set.of(scoreItems.getFirst().name(), scoreItems.get(1).name(),
                    scoreItems.get(3).name(), scoreItems.getLast().name());

            // And When
            traceResourceClient.deleteThreadFeedbackScores(projectName, threadId, scoresToDelete, null, apiKey,
                    workspaceName);

            // Then - Verify remaining scores using getTraceThreads API
            var remainingThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            // Create expected thread with remaining scores
            var expectedRemainingScores = List.of(
                    createExpectedFeedbackScore(scoreItems.get(2), now));

            var expectedRemainingThread = getExpectedThreads(List.of(trace), projectId, threadId, List.of(),
                    TraceThreadStatus.INACTIVE, expectedRemainingScores).getFirst();

            TraceAssertions.assertThreads(List.of(expectedRemainingThread), remainingThreadPage.content());
        }

        @ParameterizedTest
        @MethodSource("deleteThreadFeedbackScoreTestCases")
        @DisplayName("Delete thread feedback scores contract test")
        void deleteThreadFeedbackScores_contractTest(String projectName, String threadId, Set<String> scoreNames,
                int expectedStatus, String expectedError) {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // When
            try (var response = traceResourceClient.callDeleteThreadFeedbackScores(
                    projectName, threadId, scoreNames, null, apiKey, workspaceName)) {
                // Then
                assertThat(response.getStatus()).isEqualTo(expectedStatus);

                if (expectedStatus == HttpStatus.SC_NO_CONTENT) {
                    assertThat(response.hasEntity()).isFalse();
                } else {
                    var actualError = response.readEntity(ErrorMessage.class);
                    assertThat(actualError.errors()).contains(expectedError);
                }
            }
        }

        static Stream<Arguments> deleteThreadFeedbackScoreTestCases() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            return Stream.of(
                    // Valid case - 204 No Content
                    arguments(projectName, threadId, Set.of("accuracy"), HttpStatus.SC_NO_CONTENT, null),

                    // Non-existent project - 204 No Content (idempotent)
                    arguments("non-existent-project", threadId, Set.of("accuracy"), HttpStatus.SC_NO_CONTENT, null),

                    // Non-existent thread ID - 204 No Content (idempotent)
                    arguments(projectName, "non-existent-thread", Set.of("accuracy"), HttpStatus.SC_NO_CONTENT, null),

                    // Missing project name - 422 Unprocessable Entity
                    arguments(null, threadId, Set.of("accuracy"), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "projectName must not be blank"),

                    // Missing thread ID - 422 Unprocessable Entity
                    arguments(projectName, null, Set.of("accuracy"), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "threadId must not be blank"),

                    // Empty set of score names - 422 Unprocessable Entity
                    arguments(projectName, threadId, Set.of(), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "names size must be between 1 and 1000"),

                    // Empty score name - 422 Unprocessable Entity
                    arguments(projectName, threadId, Set.of(""), HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            "names[].<iterable element> must not be blank"));
        }
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
        return fromBuilder(factory.manufacturePojo(Trace.class).toBuilder());
    }

    private Trace fromBuilder(Trace.TraceBuilder builder) {
        return builder
                .feedbackScores(null)
                .threadId(null)
                .comments(null)
                .totalEstimatedCost(null)
                .usage(null)
                .errorInfo(null)
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
        var actualResponse = traceResourceClient.callGetById(id, apiKey, testWorkspace, null);

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
                    .visibilityMode(VisibilityMode.DEFAULT)
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

        @Test
        @DisplayName("when trace contains base64 attachments, then attachments are stripped and stored")
        void create__whenTraceContainsBase64Attachments__thenAttachmentsAreStrippedAndStored() throws Exception {
            // Given a trace with base64 encoded attachments in its input
            // Create longer base64 strings that exceed the 5000 character threshold using utility
            String base64Png = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Gif = AttachmentPayloadUtilsTest.createLargeGifBase64();

            String originalInputJson = String.format(
                    "{\"message\": \"Images attached:\", " +
                            "\"png_data\": \"image %s used for training\", " +
                            "\"gif_data\": \"%s\", " +
                            "\"user_id\": \"user123\", " +
                            "\"session_id\": \"session456\", " +
                            "\"timestamp\": \"2024-01-15T10:30:00Z\", " +
                            "\"model_config\": {\"temperature\": 0.7, \"max_tokens\": 1000}, " +
                            "\"prompt\": \"Please analyze these images and provide a detailed description\", " +
                            "\"context\": [\"Previous conversation history\", \"User preferences\"], " +
                            "\"metadata\": {\"source\": \"web_app\", \"version\": \"1.2.3\"}}",
                    base64Png, base64Gif);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed\"}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .build();

            // When creating the trace
            UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
            assertThat(traceId).isNotNull();

            // Then the trace should have attachments stripped and replaced with references
            // Wait for async processing and attachment stripping
            // Use strip_attachments=true to get attachment references instead of re-injected full base64 data
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                Trace retrievedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, true);
                assertThat(retrievedTrace).isNotNull();
                // Ensure trace is retrieved successfully before proceeding with assertions
            });

            Trace retrievedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, true);
            assertThat(retrievedTrace).isNotNull();

            JsonNode retrievedInput = retrievedTrace.input();
            assertThat(retrievedInput).isNotNull();

            String retrievedInputString = retrievedInput.toString();

            // Verify the base64 data is replaced by attachment references (bracketed, with context prefix and timestamp)
            assertThat(retrievedInputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
            assertThat(retrievedInputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.gif\\]");
            assertThat(retrievedInputString).doesNotContain(base64Png);
            assertThat(retrievedInputString).doesNotContain(base64Gif);

            var projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();

            // Verify attachments can be listed via AttachmentResourceClient
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());

            var attachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.TRACE,
                    traceId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);

            // Verify we got attachments
            assertThat(attachmentPage).isNotNull();
            assertThat(attachmentPage.content()).hasSize(2);

            // Verify attachment names contain our references with context prefixes
            var attachmentNames = attachmentPage.content().stream()
                    .map(Attachment::fileName)
                    .toList();
            // Verify attachment names contain our references with context prefixes (with timestamps)
            assertThat(attachmentNames).anyMatch(name -> name.matches("input-attachment-1-\\d+\\.png"));
            assertThat(attachmentNames).anyMatch(name -> name.matches("input-attachment-2-\\d+\\.gif"));
        }

        @Test
        @DisplayName("when trace is fetched with truncate flag, then attachments are handled accordingly")
        void getById__whenFetchedWithTruncateFlag__thenAttachmentsAreHandledAccordingly() throws Exception {
            // Given a trace with base64 encoded attachments in its input
            String base64Png = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Gif = AttachmentPayloadUtilsTest.createLargeGifBase64();

            String originalInputJson = String.format(
                    "{\"message\": \"Images attached:\", " +
                            "\"png_data\": \"%s\", " +
                            "\"gif_data\": \"image %s used for training\"}",
                    base64Png, base64Gif);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed\"}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .build();

            // When creating the trace
            UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
            assertThat(traceId).isNotNull();

            // Wait for async attachment stripping - verify the base64 data is no longer in the payload
            // (it's been replaced with attachment references)
            Awaitility.await()
                    .pollInterval(1, TimeUnit.SECONDS)
                    .atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        Trace retrievedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, true);
                        assertThat(retrievedTrace).isNotNull();

                        String inputString = retrievedTrace.input().toString();
                        // The key indicator: the original base64 data should be gone
                        assertThat(inputString).doesNotContain(base64Png);
                        assertThat(inputString).doesNotContain(base64Gif);
                        // And replaced with references (this is secondary - main check is data is gone)
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.gif\\]");
                    });

            // Verify exactly 2 attachments were created (no duplicates)
            var projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());

            var attachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.TRACE,
                    traceId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);

            assertThat(attachmentPage).isNotNull();
            assertThat(attachmentPage.content()).hasSize(2); // Should have exactly 2, not duplicates

            // Test 1: Fetch with strip_attachments=true - should show references
            Trace truncatedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, true);
            assertThat(truncatedTrace).isNotNull();

            JsonNode truncatedInput = truncatedTrace.input();
            assertThat(truncatedInput).isNotNull();
            String truncatedInputString = truncatedInput.toString();

            // Verify the base64 data is replaced by attachment references (with timestamps)
            assertThat(truncatedInputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
            assertThat(truncatedInputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.gif\\]");
            assertThat(truncatedInputString).doesNotContain(base64Png);
            assertThat(truncatedInputString).doesNotContain(base64Gif);

            // Test 2: Fetch with truncate=false - should show original base64 data
            Trace fullTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, false);
            assertThat(fullTrace).isNotNull();

            JsonNode fullInput = fullTrace.input();
            assertThat(fullInput).isNotNull();
            String fullInputString = fullInput.toString();

            // Verify the base64 data is restored (whitespace formatting may differ due to Jackson read/write)
            assertThat(fullInputString).contains(base64Png);
            assertThat(fullInputString).contains(base64Gif);
            assertThat(fullInputString).contains("Images attached:");
            // Should not contain attachment references
            assertThat(fullInputString).doesNotContainPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
            assertThat(fullInputString).doesNotContainPattern("\\[input-attachment-\\d+-\\d+\\.gif\\]");
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

        @ParameterizedTest
        @MethodSource
        void batch__whenSendingMultipleTracesWithSameId__dedupeTraces__thenReturnNoContent(
                Function<Trace, Trace> traceModifier) {
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(API_KEY, workspaceName, workspaceId);

            var id = generator.generate();
            String projectName = UUID.randomUUID().toString();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .id(id)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .usage(null)
                            .build())
                    .toList();

            var modifiedTraces = IntStream.range(0, traces.size())
                    .mapToObj(i -> i == traces.size() - 1
                            ? traceModifier.apply(traces.get(i)) // modify last item
                            : traces.get(i))
                    .toList();

            try (var actualResponse = traceResourceClient.callBatchCreateTraces(modifiedTraces, API_KEY,
                    workspaceName)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode())
                        .isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    List.of(),
                    List.of(),
                    List.of(modifiedTraces.getLast()),
                    List.of(),
                    API_KEY);
        }

        Stream<Arguments> batch__whenSendingMultipleTracesWithSameId__dedupeTraces__thenReturnNoContent() {
            return Stream.of(
                    arguments(
                            (Function<Trace, Trace>) t -> t),
                    arguments(
                            (Function<Trace, Trace>) trace -> trace.toBuilder()
                                    .lastUpdatedAt(null).build()));
        }

        @Test
        void batch__whenMissingFields__thenReturnNoContent() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var expectedTraces = IntStream.range(0, 5)
                    .mapToObj(i -> Trace.builder()
                            .projectName(projectName)
                            .startTime(Instant.now())
                            .visibilityMode(VisibilityMode.DEFAULT)
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
                            .visibilityMode(VisibilityMode.DEFAULT)
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

        Stream<Arguments> testInRange() {
            return Stream.of(
                    arguments(
                            Instant.parse(MIN_ANALYTICS_DB),
                            Instant.parse(MIN_ANALYTICS_DB),
                            Instant.parse(MIN_ANALYTICS_DB)),
                    arguments(
                            Instant.parse(MAX_ANALYTICS_DB_PRECISION_9).minusNanos(1),
                            Instant.parse(MAX_ANALYTICS_DB_PRECISION_9).minusNanos(1),
                            Instant.parse(MAX_ANALYTICS_DB).minus(1, ChronoUnit.MICROS)),
                    arguments(
                            InRangeStrategy.INSTANCE.getRandomInstant(MIN_ANALYTICS_DB, MAX_ANALYTICS_DB_PRECISION_9),
                            InRangeStrategy.INSTANCE.getRandomInstant(MIN_ANALYTICS_DB, MAX_ANALYTICS_DB_PRECISION_9),
                            InRangeStrategy.INSTANCE.getRandomInstant(MIN_ANALYTICS_DB, MAX_ANALYTICS_DB)),
                    arguments(
                            Instant.now(),
                            Instant.now(),
                            Instant.now()),
                    arguments(
                            Instant.now(),
                            null,
                            null));
        }

        @ParameterizedTest
        @MethodSource
        void testInRange(Instant startTime, Instant endTime, Instant lastUpdatedAt) {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var expectedTraces = IntStream.range(0, 1)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(startTime)
                            .endTime(endTime)
                            .lastUpdatedAt(lastUpdatedAt)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(startTime, endTime))
                            .comments(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(expectedTraces, API_KEY, TEST_WORKSPACE);

            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    null,
                    List.of(),
                    List.of(),
                    expectedTraces.reversed(),
                    List.of(),
                    API_KEY);
        }

        Stream<Arguments> testInRangeError() {
            return Stream.of(
                    arguments(
                            Instant.parse(MIN_ANALYTICS_DB).minusNanos(1),
                            Instant.parse(MIN_ANALYTICS_DB).minusNanos(1),
                            Instant.parse(MIN_ANALYTICS_DB).minusNanos(1)),
                    arguments(
                            Instant.parse(MAX_ANALYTICS_DB_PRECISION_9),
                            Instant.parse(MAX_ANALYTICS_DB_PRECISION_9),
                            Instant.parse(MAX_ANALYTICS_DB)));
        }

        @ParameterizedTest
        @MethodSource
        void testInRangeError(Instant startTime, Instant endTime, Instant lastUpdatedAt) {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var expectedTraces = IntStream.range(0, 1)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .startTime(startTime)
                            .endTime(endTime)
                            .lastUpdatedAt(lastUpdatedAt)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(startTime, endTime))
                            .comments(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            try (var actualResponse = traceResourceClient.callBatchCreateTraces(
                    expectedTraces, API_KEY, TEST_WORKSPACE)) {

                assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                var expectedErrors = List.of(
                        "traces[0].startTime must be after or equal 1970-01-01T00:00:00.000000Z and before 2262-04-11T23:47:16.000000000Z",
                        "traces[0].endTime must be after or equal 1970-01-01T00:00:00.000000Z and before 2262-04-11T23:47:16.000000000Z",
                        "traces[0].lastUpdatedAt must be after or equal 1970-01-01T00:00:00.000000Z and before 2300-01-01T00:00:00.000000Z");
                var actualError = actualResponse.readEntity(ErrorMessage.class);
                assertThat(actualError.errors()).containsExactlyInAnyOrderElementsOf(expectedErrors);
            }
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

        @Test
        @DisplayName("batch create traces with base64 attachments, then attachments are stripped and stored")
        void batchCreate__whenTracesContainBase64Attachments__thenAttachmentsAreStrippedAndStored() throws Exception {
            // Given multiple traces with base64 encoded attachments in their inputs and outputs
            String base64Png = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Gif = AttachmentPayloadUtilsTest.createLargeGifBase64();
            String base64Pdf = AttachmentPayloadUtilsTest.createLargePdfBase64();

            // Create first trace with PNG in input and GIF in output
            String inputJson1 = String.format(
                    "{\"message\": \"First trace with PNG\", " +
                            "\"image_data\": \"image %s used for training\", " +
                            "\"user_id\": \"user123\", " +
                            "\"request_type\": \"image_analysis\"}",
                    base64Png);

            String outputJson1 = String.format(
                    "{\"result\": \"Analysis complete\", " +
                            "\"chart_data\": \"%s\", " +
                            "\"confidence\": 0.95}",
                    base64Gif);

            // Create second trace with PDF in input and PNG in metadata
            String inputJson2 = String.format(
                    "{\"message\": \"Second trace with PDF\", " +
                            "\"document_data\": \"%s\", " +
                            "\"user_id\": \"user456\", " +
                            "\"request_type\": \"document_processing\"}",
                    base64Pdf);

            String metadataJson2 = String.format(
                    "{\"processed_image\": \"%s\", " +
                            "\"processing_time\": 1250, " +
                            "\"model_version\": \"v2.1\"}",
                    base64Png);

            // Create third trace with multiple attachments in different fields
            String inputJson3 = String.format(
                    "{\"message\": \"Third trace with multiple attachments\", " +
                            "\"primary_image\": \"%s\", " +
                            "\"secondary_document\": \"%s\", " +
                            "\"user_id\": \"user789\", " +
                            "\"batch_id\": \"batch_001\"}",
                    base64Gif, base64Pdf);

            var traces = List.of(
                    factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .input(JsonUtils.readTree(inputJson1))
                            .output(JsonUtils.readTree(outputJson1))
                            .metadata(JsonUtils.readTree("{}"))
                            .build(),
                    factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .input(JsonUtils.readTree(inputJson2))
                            .output(JsonUtils.readTree("{\"result\": \"Document processed successfully\"}"))
                            .metadata(JsonUtils.readTree(metadataJson2))
                            .build(),
                    factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .input(JsonUtils.readTree(inputJson3))
                            .output(JsonUtils.readTree("{\"result\": \"Multi-attachment processing complete\"}"))
                            .metadata(JsonUtils.readTree("{}"))
                            .build());

            // When batch creating the traces
            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            // Then all traces should have attachments stripped and replaced with references
            // Wait for async processing and attachment stripping
            var projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();

            // Use strip_attachments=true to get attachment references instead of re-injected full base64 data
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                // Ensure all traces can be retrieved successfully before proceeding with assertions
                for (var originalTrace : traces) {
                    Trace retrievedTrace = traceResourceClient.getById(originalTrace.id(), TEST_WORKSPACE, API_KEY,
                            true);
                    assertThat(retrievedTrace).isNotNull();
                }
            });
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());

            // Verify each trace has its attachments processed
            for (int i = 0; i < traces.size(); i++) {
                var originalTrace = traces.get(i);
                var retrievedTrace = traceResourceClient.getById(originalTrace.id(), TEST_WORKSPACE, API_KEY, true);
                assertThat(retrievedTrace).isNotNull();

                // Verify original base64 data is not present in any field
                String inputString = retrievedTrace.input().toString();
                String outputString = retrievedTrace.output().toString();
                String metadataString = retrievedTrace.metadata().toString();

                assertThat(inputString).doesNotContain(base64Png);
                assertThat(inputString).doesNotContain(base64Gif);
                assertThat(inputString).doesNotContain(base64Pdf);

                assertThat(outputString).doesNotContain(base64Png);
                assertThat(outputString).doesNotContain(base64Gif);
                assertThat(outputString).doesNotContain(base64Pdf);

                assertThat(metadataString).doesNotContain(base64Png);
                assertThat(metadataString).doesNotContain(base64Gif);
                assertThat(metadataString).doesNotContain(base64Pdf);

                // Verify attachment references are present based on trace content (bracketed, with context prefix and timestamp)
                switch (i) {
                    case 0 : // First trace: PNG in input, GIF in output
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
                        assertThat(outputString).containsPattern("\\[output-attachment-\\d+-\\d+\\.gif\\]");
                        break;
                    case 1 : // Second trace: PDF in input, PNG in metadata
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.pdf\\]");
                        assertThat(metadataString).containsPattern("\\[metadata-attachment-\\d+-\\d+\\.png\\]");
                        break;
                    case 2 : // Third trace: GIF and PDF in input
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.gif\\]");
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.pdf\\]");
                        break;
                }

                // Verify attachments are stored and can be listed
                var attachmentPage = attachmentResourceClient.attachmentList(
                        projectId,
                        EntityType.TRACE,
                        originalTrace.id(),
                        baseUrl,
                        API_KEY,
                        TEST_WORKSPACE,
                        200);

                assertThat(attachmentPage).isNotNull();
                assertThat(attachmentPage.content()).isNotEmpty();

                var attachmentNames = attachmentPage.content().stream()
                        .map(attachment -> attachment.fileName())
                        .toList();

                // Verify correct number of attachments based on trace content
                switch (i) {
                    case 0 : // First trace should have 2 attachments (PNG + GIF)
                        assertThat(attachmentPage.content()).hasSize(2);
                        assertThat(attachmentNames).anyMatch(name -> name.matches("input-attachment-1-\\d+\\.png"));
                        assertThat(attachmentNames).anyMatch(name -> name.matches("output-attachment-1-\\d+\\.gif"));
                        break;
                    case 1 : // Second trace should have 2 attachments (PDF + PNG)
                        assertThat(attachmentPage.content()).hasSize(2);
                        assertThat(attachmentNames).anyMatch(name -> name.matches("input-attachment-1-\\d+\\.pdf"));
                        assertThat(attachmentNames).anyMatch(name -> name.matches("metadata-attachment-1-\\d+\\.png"));
                        break;
                    case 2 : // Third trace should have 2 attachments (GIF + PDF)
                        assertThat(attachmentPage.content()).hasSize(2);
                        assertThat(attachmentNames).anyMatch(name -> name.matches("input-attachment-1-\\d+\\.gif"));
                        assertThat(attachmentNames).anyMatch(name -> name.matches("input-attachment-2-\\d+\\.pdf"));
                        break;
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
            createAndAssertForSpan(FeedbackScoreBatch.builder().scores(spanScores).build(), workspaceName,
                    apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var project = projectResourceClient.getByName(projectName, apiKey, workspaceName);

            traceResourceClient.deleteTraces(
                    BatchDeleteByProject.builder().ids(Set.of(traces.getFirst().id())).projectId(project.id()).build(),
                    workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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
            createAndAssertForSpan(FeedbackScoreBatch.builder().scores(spanScores).build(), workspaceName,
                    apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey);
            getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey);

            var request = BatchDeleteByProject.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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

            var request = BatchDeleteByProject.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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

            var request = BatchDeleteByProject.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, workspaceName, apiKey);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, List.of(), List.of(), apiKey);
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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

            var request = BatchDeleteByProject.builder()
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

            var request = factory.manufacturePojo(BatchDeleteByProject.class);
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
                    .visibilityMode(VisibilityMode.DEFAULT)
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

        @Test
        @DisplayName("when updating trace with different attachments, then old attachments are deleted and new ones are stored")
        void update__whenUpdatingTraceWithDifferentAttachments__thenOldAttachmentsAreDeletedAndNewOnesAreStored()
                throws Exception {
            // Step 1: Create a trace with 3 JPG attachments
            String base64Jpg1 = AttachmentPayloadUtilsTest.createLargeJpegBase64();
            String base64Jpg2 = AttachmentPayloadUtilsTest.createLargeJpegBase64();
            String base64Jpg3 = AttachmentPayloadUtilsTest.createLargeJpegBase64();

            String originalInputJson = String.format(
                    "{\"message\": \"Original trace with 3 JPG images\", " +
                            "\"image1\": \"%s\", " +
                            "\"image2\": \"%s\", " +
                            "\"image3\": \"%s\", " +
                            "\"user_id\": \"user123\", " +
                            "\"operation\": \"image_processing\"}",
                    base64Jpg1, base64Jpg2, base64Jpg3);

            var originalTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed 3 images\"}"))
                    .metadata(JsonUtils.readTree("{\"format\": \"jpg\", \"count\": 3}"))
                    .build();

            // Create the trace
            UUID traceId = traceResourceClient.createTrace(originalTrace, API_KEY, TEST_WORKSPACE);
            assertThat(traceId).isNotNull();

            // Wait for async processing and attachment stripping
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                Trace retrievedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, true);
                assertThat(retrievedTrace).isNotNull();
                // Ensure the trace is fully processed
                String inputString = retrievedTrace.input().toString();
                assertThat(inputString).doesNotContain(base64Jpg1);
                assertThat(inputString).doesNotContain(base64Jpg2);
                assertThat(inputString).doesNotContain(base64Jpg3);
            });

            var projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());

            // Step 2: Verify we have 3 JPG attachments initially
            var initialAttachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.TRACE,
                    traceId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);

            assertThat(initialAttachmentPage).isNotNull();
            assertThat(initialAttachmentPage.content()).hasSize(3);

            // Verify all initial attachments are JPEGs
            var initialAttachmentNames = initialAttachmentPage.content().stream()
                    .map(Attachment::fileName)
                    .toList();
            assertThat(initialAttachmentNames).allSatisfy(name -> assertThat(name).contains(".jpg"));

            // Step 3: Update the trace with 2 PNG attachments (different type and count)
            String base64Png1 = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Png2 = AttachmentPayloadUtilsTest.createLargePngBase64();

            String updatedInputJson = String.format(
                    "{\"message\": \"Updated trace with 2 PNG images\", " +
                            "\"png_image1\": \"image %s used for training\", " +
                            "\"png_image2\": \"%s\", " +
                            "\"user_id\": \"user123\", " +
                            "\"operation\": \"png_processing\"}",
                    base64Png1, base64Png2);

            var traceUpdate = TraceUpdate.builder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(updatedInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed 2 PNG images\"}"))
                    .metadata(JsonUtils.readTree("{\"format\": \"png\", \"count\": 2}"))
                    .build();

            // Perform the update
            traceResourceClient.updateTrace(traceId, traceUpdate, API_KEY, TEST_WORKSPACE);

            // Wait for async processing and attachment stripping for the update
            // Also wait for ClickHouse ReplicatedReplacingMergeTree to merge the rows
            Awaitility.await()
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        Trace updatedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY, true);
                        assertThat(updatedTrace).isNotNull();

                        String updatedInputString = updatedTrace.input().toString();

                        // First, check for the new message to confirm ClickHouse merge completed
                        assertThat(updatedInputString).contains("Updated trace with 2 PNG images");

                        // Verify original JPG base64 data is not present
                        assertThat(updatedInputString).doesNotContain(base64Jpg1);
                        assertThat(updatedInputString).doesNotContain(base64Jpg2);
                        assertThat(updatedInputString).doesNotContain(base64Jpg3);

                        // Verify new PNG base64 data is not present (should be replaced by references)
                        assertThat(updatedInputString).doesNotContain(base64Png1);
                        assertThat(updatedInputString).doesNotContain(base64Png2);

                        // Verify PNG attachment references are present (with timestamps)
                        assertThat(updatedInputString).containsPattern("input-attachment-1-\\d+\\.png");
                        assertThat(updatedInputString).containsPattern("input-attachment-2-\\d+\\.png");
                    });

            // Step 4: Verify we now have 2 PNG attachments (old JPGs should be deleted)
            var finalAttachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.TRACE,
                    traceId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);

            assertThat(finalAttachmentPage).isNotNull();
            assertThat(finalAttachmentPage.content()).hasSize(2);

            // Verify all final attachments are PNGs
            var finalAttachmentNames = finalAttachmentPage.content().stream()
                    .map(Attachment::fileName)
                    .toList();
            assertThat(finalAttachmentNames).allSatisfy(name -> assertThat(name).contains(".png"));

            // Step 5: Verify the updated trace exists and was updated
            Trace finalTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
            assertThat(finalTrace).isNotNull();
            assertThat(finalTrace.id()).isEqualTo(traceId);
        }
    }

    @Nested
    @DisplayName("Batch Update Traces Tags:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchUpdateTraces {

        Stream<Arguments> mergeTagsTestCases() {
            return Stream.of(
                    Arguments.of(true, "merge"),
                    Arguments.of(false, "replace"));
        }

        @ParameterizedTest(name = "Success: batch update tags with {1} mode")
        @MethodSource("mergeTagsTestCases")
        @DisplayName("Success: batch update tags for multiple traces")
        void batchUpdate__success(boolean mergeTags, String mode) {
            // Create traces with existing tags
            var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .tags(mergeTags ? Set.of("existing-tag-1", "existing-tag-2") : Set.of("old-tag-1", "old-tag-2"))
                    .feedbackScores(null)
                    .build();
            var trace2 = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .tags(mergeTags ? Set.of("existing-tag-3") : Set.of("old-tag-3"))
                    .feedbackScores(null)
                    .build();
            var trace3 = mergeTags
                    ? factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .tags(null)
                            .feedbackScores(null)
                            .build()
                    : null;

            var id1 = traceResourceClient.createTrace(trace1, API_KEY, TEST_WORKSPACE);
            var id2 = traceResourceClient.createTrace(trace2, API_KEY, TEST_WORKSPACE);
            var id3 = mergeTags ? traceResourceClient.createTrace(trace3, API_KEY, TEST_WORKSPACE) : null;

            // Batch update with new tags
            var newTags = mergeTags ? Set.of("new-tag-1", "new-tag-2") : Set.of("new-tag");
            var ids = mergeTags ? Set.of(id1, id2, id3) : Set.of(id1, id2);
            var batchUpdate = TraceBatchUpdate.builder()
                    .ids(ids)
                    .update(TraceUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .tags(newTags)
                            .build())
                    .mergeTags(mergeTags)
                    .build();

            traceResourceClient.batchUpdateTraces(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Verify traces were updated
            var updatedTrace1 = traceResourceClient.getById(id1, TEST_WORKSPACE, API_KEY);
            if (mergeTags) {
                assertThat(updatedTrace1.tags()).containsExactlyInAnyOrder(
                        "existing-tag-1", "existing-tag-2", "new-tag-1", "new-tag-2");
            } else {
                assertThat(updatedTrace1.tags()).containsExactly("new-tag");
            }

            var updatedTrace2 = traceResourceClient.getById(id2, TEST_WORKSPACE, API_KEY);
            if (mergeTags) {
                assertThat(updatedTrace2.tags()).containsExactlyInAnyOrder("existing-tag-3", "new-tag-1", "new-tag-2");
            } else {
                assertThat(updatedTrace2.tags()).containsExactly("new-tag");
            }

            if (mergeTags) {
                var updatedTrace3 = traceResourceClient.getById(id3, TEST_WORKSPACE, API_KEY);
                assertThat(updatedTrace3.tags()).containsExactlyInAnyOrder("new-tag-1", "new-tag-2");
            }
        }

        @Test
        @DisplayName("when batch update with empty IDs, then return 400")
        void batchUpdate__whenEmptyIds__thenReturn400() {
            var batchUpdate = TraceBatchUpdate.builder()
                    .ids(Set.of())
                    .update(TraceUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .tags(Set.of("tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            try (var actualResponse = traceResourceClient.callBatchUpdateTraces(batchUpdate, API_KEY, TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                var error = actualResponse.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anySatisfy(msg -> assertThat(msg).contains("ids"));
            }
        }

        @Test
        @DisplayName("when batch update with too many IDs, then return 400")
        void batchUpdate__whenTooManyIds__thenReturn400() {
            // Create 1001 IDs (exceeds max of 1000)
            var ids = new HashSet<UUID>();
            for (int i = 0; i < 1001; i++) {
                ids.add(generator.generate());
            }

            var batchUpdate = TraceBatchUpdate.builder()
                    .ids(ids)
                    .update(TraceUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .tags(Set.of("tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            try (var actualResponse = traceResourceClient.callBatchUpdateTraces(batchUpdate, API_KEY, TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                var error = actualResponse.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anySatisfy(msg -> assertThat(msg).contains("ids"));
            }
        }

        @Test
        @DisplayName("when batch update with null update, then return 400")
        void batchUpdate__whenNullUpdate__thenReturn400() {
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();
            var id = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var batchUpdate = TraceBatchUpdate.builder()
                    .ids(Set.of(id))
                    .update(null)
                    .mergeTags(true)
                    .build();

            try (var actualResponse = traceResourceClient.callBatchUpdateTraces(batchUpdate, API_KEY, TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
            }
        }

        @Test
        @DisplayName("when batch update with max size (1000), then success")
        void batchUpdate__whenMaxSize__thenSuccess() {
            // Create 1000 traces
            var ids = new HashSet<UUID>();
            for (int i = 0; i < 1000; i++) {
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(DEFAULT_PROJECT)
                        .tags(Set.of("old-tag"))
                        .feedbackScores(null)
                        .build();
                var id = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
                ids.add(id);
            }

            var batchUpdate = TraceBatchUpdate.builder()
                    .ids(ids)
                    .update(TraceUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .tags(Set.of("new-tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            traceResourceClient.batchUpdateTraces(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Verify a sample of traces
            var sampleIds = ids.stream().limit(10).toList();
            for (var id : sampleIds) {
                var trace = traceResourceClient.getById(id, TEST_WORKSPACE, API_KEY);
                assertThat(trace.tags()).containsExactlyInAnyOrder("old-tag", "new-tag");
            }
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
            assertComment(expectedComment, actualComment);
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
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                expectedTraceComments.forEach(
                        comment -> traceResourceClient.getCommentById(comment.id(), traceId, API_KEY, TEST_WORKSPACE,
                                404));
            });

            // Verify span comments were actually deleted via get endpoint
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                spanWithComments.getRight().forEach(
                        comment -> spanResourceClient.getCommentById(comment.id(), spanWithComments.getLeft(), API_KEY,
                                TEST_WORKSPACE, 404));
            });
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

            var request = BatchDeleteByProject.builder()
                    .ids(traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet()))
                    .build();

            traceResourceClient.deleteTraces(request, TEST_WORKSPACE, API_KEY);

            // Verify comments were actually deleted via get endpoint
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                expectedTraceComments
                        .forEach(comment -> traceResourceClient.getCommentById(comment.id(), traces.getFirst().id(),
                                API_KEY, TEST_WORKSPACE, 404));
            });

            // Verify span comments were actually deleted via get endpoint
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                spanWithComments.getRight().forEach(
                        comment -> spanResourceClient.getCommentById(comment.id(), spanWithComments.getLeft(), API_KEY,
                                TEST_WORKSPACE, 404));
            });
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
            var score = factory.manufacturePojo(FeedbackScore.class);
            try (var actualResponse = traceResourceClient.callFeedbackScore(id, score, TEST_WORKSPACE, API_KEY)) {

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
            try (var actualResponse = traceResourceClient.callFeedbackScore(id, feedbackScore, TEST_WORKSPACE,
                    API_KEY)) {
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
            var deleteFeedbackScore = factory.manufacturePojo(DeleteFeedbackScore.class);
            traceResourceClient.deleteTraceFeedbackScore(deleteFeedbackScore, id, API_KEY, TEST_WORKSPACE);
        }

        Stream<String> deleteFeedback() {
            return Stream.of(USER, null, "", "   ");
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success")
        void deleteFeedback(String author) {
            var expectedTrace = createTrace();
            var id = create(expectedTrace, API_KEY, TEST_WORKSPACE);
            var score = factory.manufacturePojo(FeedbackScore.class);
            create(id, score, TEST_WORKSPACE, API_KEY);
            expectedTrace = expectedTrace.toBuilder().feedbackScores(List.of(score)).build();
            var actualTrace = getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
            assertThat(actualTrace.feedbackScores()).hasSize(1);

            var deleteFeedbackScore = DeleteFeedbackScore.builder()
                    .name(score.name())
                    .author(author)
                    .build();
            traceResourceClient.deleteTraceFeedbackScore(deleteFeedbackScore, id, API_KEY, TEST_WORKSPACE);

            expectedTrace = expectedTrace.toBuilder().feedbackScores(null).build();
            var actualEntity = getAndAssert(expectedTrace, null, API_KEY, TEST_WORKSPACE);
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
                                    .mapToObj(__ -> initFeedbackScoreItem()
                                            .projectName(DEFAULT_PROJECT).build())
                                    .collect(toList()))
                            .build(), "scores size must be between 1 and 1000"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(initFeedbackScoreItem()
                                            .projectName(DEFAULT_PROJECT).name(null).build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(initFeedbackScoreItem()
                                            .projectName(DEFAULT_PROJECT).name("").build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(initFeedbackScoreItem()
                                            .projectName(DEFAULT_PROJECT).value(null).build()))
                                    .build(),
                            "scores[0].value must not be null"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(initFeedbackScoreItem()
                                            .projectName(DEFAULT_PROJECT)
                                            .value(BigDecimal.valueOf(-999999999.9999999991))
                                            .build()))
                                    .build(),
                            "scores[0].value must be greater than or equal to -999999999.999999999"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List.of(initFeedbackScoreItem()
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

            var score1 = initFeedbackScoreItem()
                    .id(id1)
                    .projectName(trace1.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score2 = initFeedbackScoreItem()
                    .id(id2)
                    .name("hallucination")
                    .projectName(trace2.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score3 = initFeedbackScoreItem()
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
            var score2 = initFeedbackScoreItem()
                    .id(id2)
                    .name("hallucination")
                    .projectName(expectedTrace2.projectName())
                    .value(factory.manufacturePojo(BigDecimal.class))
                    .build();
            var score3 = initFeedbackScoreItem()
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
        void feedback__whenBatchRequestIsInvalid__thenReturnBadRequest(FeedbackScoreBatchContainer batch,
                String errorMessage) {
            try (var actualResponse = traceResourceClient.callFeedbackScores(batch.scores(),
                    API_KEY, TEST_WORKSPACE)) {
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

            var score = initFeedbackScoreItem()
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

            var score = initFeedbackScoreItem()
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

            var score = initFeedbackScoreItem()
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
            var score = initFeedbackScoreItem()
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

            List<FeedbackScoreBatchItem> scores = IntStream.range(0, 1000)
                    .mapToObj(__ -> initFeedbackScoreItem()
                            .projectName(DEFAULT_PROJECT)
                            .id(id)
                            .build())
                    .collect(Collectors.toList());
            traceResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback trace id is not valid, then return 400")
        void feedback__whenFeedbackTraceIdIsNotValid__thenReturn400() {
            var score = initFeedbackScoreItem()
                    .id(UUID.randomUUID())
                    .projectName(DEFAULT_PROJECT)
                    .build();

            try (var actualResponse = traceResourceClient.callFeedbackScores(List.of(score), API_KEY, TEST_WORKSPACE)) {
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

            List<FeedbackScoreBatchItem> scores = traces.stream()
                    .map(trace -> initFeedbackScoreItem()
                            .id(trace.id())
                            .projectName(projectNameModifier.apply(projectName))
                            .build())
                    .collect(Collectors.toList());

            traceResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);

            var expectedTracesWithScores = traces.stream()
                    .map(trace -> {
                        FeedbackScoreItem feedbackScoreBatchItem = scores.stream()
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

    @Nested
    @DisplayName("Span Feedback Scores Aggregation:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanFeedbackScoresAggregation {

        @Test
        @DisplayName("when trace has spans with feedback scores, then return aggregated span scores")
        void getTrace__whenTraceHasSpansWithFeedbackScores__thenReturnAggregatedSpanScores() {
            // Create trace
            var expectedTrace = createTrace();
            var traceId = create(expectedTrace, API_KEY, TEST_WORKSPACE);
            var projectId = getProjectId(expectedTrace.projectName(), TEST_WORKSPACE, API_KEY);

            // Create spans with feedback scores
            var span1 = factory.manufacturePojo(Span.class).toBuilder()
                    .traceId(traceId)
                    .projectName(expectedTrace.projectName())
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(span1, API_KEY, TEST_WORKSPACE);

            var span2 = factory.manufacturePojo(Span.class).toBuilder()
                    .traceId(traceId)
                    .projectName(expectedTrace.projectName())
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(span2, API_KEY, TEST_WORKSPACE);

            // Add feedback scores to spans
            // Span1: accuracy=0.8, relevance=0.9
            // Span2: accuracy=0.9, relevance=0.7
            // Expected aggregated: accuracy=(0.8+0.9)/2=0.85, relevance=(0.9+0.7)/2=0.8
            var span1AccuracyScore = FeedbackScoreBatchItem.builder()
                    .id(span1.id())
                    .projectName(expectedTrace.projectName())
                    .name("accuracy")
                    .value(BigDecimal.valueOf(0.8))
                    .source(ScoreSource.SDK)
                    .build();
            var span1RelevanceScore = FeedbackScoreBatchItem.builder()
                    .id(span1.id())
                    .projectName(expectedTrace.projectName())
                    .name("relevance")
                    .value(BigDecimal.valueOf(0.9))
                    .source(ScoreSource.SDK)
                    .build();
            var span2AccuracyScore = FeedbackScoreBatchItem.builder()
                    .id(span2.id())
                    .projectName(expectedTrace.projectName())
                    .name("accuracy")
                    .value(BigDecimal.valueOf(0.9))
                    .source(ScoreSource.SDK)
                    .build();
            var span2RelevanceScore = FeedbackScoreBatchItem.builder()
                    .id(span2.id())
                    .projectName(expectedTrace.projectName())
                    .name("relevance")
                    .value(BigDecimal.valueOf(0.7))
                    .source(ScoreSource.SDK)
                    .build();

            spanResourceClient.feedbackScores(
                    List.of(span1AccuracyScore, span1RelevanceScore, span2AccuracyScore, span2RelevanceScore),
                    API_KEY, TEST_WORKSPACE);

            // Get trace and verify aggregated span scores
            var actualTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

            assertThat(actualTrace.spanFeedbackScores()).isNotNull();
            assertThat(actualTrace.spanFeedbackScores()).hasSize(2);

            // Verify accuracy score (average of 0.8 and 0.9 = 0.85)
            var accuracyScore = actualTrace.spanFeedbackScores().stream()
                    .filter(score -> "accuracy".equals(score.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(accuracyScore.value()).isEqualByComparingTo(BigDecimal.valueOf(0.85));

            // Verify relevance score (average of 0.9 and 0.7 = 0.8)
            var relevanceScore = actualTrace.spanFeedbackScores().stream()
                    .filter(score -> "relevance".equals(score.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(relevanceScore.value()).isEqualByComparingTo(BigDecimal.valueOf(0.8));
        }

        @Test
        @DisplayName("when trace has spans without feedback scores, then return null span scores")
        void getTrace__whenTraceHasSpansWithoutFeedbackScores__thenReturnNullSpanScores() {
            // Create trace
            var expectedTrace = createTrace();
            var traceId = create(expectedTrace, API_KEY, TEST_WORKSPACE);

            // Create spans without feedback scores
            var span1 = factory.manufacturePojo(Span.class).toBuilder()
                    .traceId(traceId)
                    .projectName(expectedTrace.projectName())
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(span1, API_KEY, TEST_WORKSPACE);

            // Get trace and verify span scores are null
            var actualTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
            assertThat(actualTrace.spanFeedbackScores()).isNull();
        }

        @Test
        @DisplayName("when trace has no spans, then return null span scores")
        void getTrace__whenTraceHasNoSpans__thenReturnNullSpanScores() {
            // Create trace without spans
            var expectedTrace = createTrace();
            var traceId = create(expectedTrace, API_KEY, TEST_WORKSPACE);

            // Get trace and verify span scores are null
            var actualTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
            assertThat(actualTrace.spanFeedbackScores()).isNull();
        }
    }

    private FeedbackScoreBatchItemBuilder<?, ?> initFeedbackScoreItem() {
        return factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder();
    }

    private FeedbackScore mapFeedbackScore(FeedbackScoreItem feedbackScoreBatchItem) {
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

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("when get feedback score names, then return feedback score names")
        void getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames(boolean useProjectId) {

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

            List<String> allNames = new ArrayList<>(names);
            allNames.addAll(otherNames);

            fetchAndAssertResponse(useProjectId ? names : allNames, useProjectId ? projectId : null, apiKey,
                    workspaceName);
        }
    }

    @Nested
    @DisplayName("Get Trace Threads Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetTraceThreadsFeedbackScoreNames {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("when get trace threads feedback score names, then return feedback score names")
        void getTraceThreadsFeedbackScoreNames__whenGetTraceThreadsFeedbackScoreNames__thenReturnFeedbackScoreNames(
                boolean useProjectId) {

            // given
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = UUID.randomUUID().toString();

            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            Project project = projectResourceClient.getProject(projectId, apiKey, workspaceName);

            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);
            List<String> otherNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            // Create multiple values feedback scores for trace threads
            List<String> multipleValuesFeedbackScores = names.subList(0, names.size() - 1);

            createMultiValueTraceThreadScores(multipleValuesFeedbackScores, project, apiKey, workspaceName);

            createMultiValueTraceThreadScores(List.of(names.getLast()), project, apiKey, workspaceName);

            // Create unexpected feedback scores for a different project
            String unexpectedProjectName = RandomStringUtils.secure().nextAlphanumeric(20);
            UUID unexpectedProjectId = projectResourceClient.createProject(unexpectedProjectName, apiKey,
                    workspaceName);
            Project unexpectedProject = projectResourceClient.getProject(unexpectedProjectId, apiKey, workspaceName);

            createMultiValueTraceThreadScores(otherNames, unexpectedProject, apiKey, workspaceName);

            // when
            FeedbackScoreNames actualNames = traceResourceClient.getTraceThreadsFeedbackScoreNames(
                    useProjectId ? projectId : null, apiKey,
                    workspaceName);

            List<String> allNames = new ArrayList<>(names);
            allNames.addAll(otherNames);

            // then
            assertFeedbackScoreNames(actualNames, useProjectId ? names : allNames);
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
        @DisplayName("when trace thread is deleted, then related spans are deleted as well")
        void deleteTraceThread__whenTraceThreadIdIsPresent__thenDeleteRelatedSpans() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var trace = createTrace().toBuilder()
                    .threadId(UUID.randomUUID().toString())
                    .projectName(projectName)
                    .build();

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(null)
                            .build())
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var id = create(trace, apiKey, workspaceName);

            traceResourceClient.deleteTraceThreads(List.of(trace.threadId()), trace.projectName(), null, apiKey,
                    workspaceName);

            getAndAssertTraceNotFound(id, apiKey, workspaceName);

            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                getAndAssertPageSpans(workspaceName, projectName, List.of(), spans, List.of(), List.of(), apiKey);
            });
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

            var traceThreads = DeleteTraceThreads.builder()
                    .threadIds(List.of(trace.threadId()))
                    .build();

            try (var actualResponse = traceResourceClient.callDeleteTraceThreads(traceThreads, API_KEY,
                    TEST_WORKSPACE)) {
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

            DeleteTraceThreads traceThreads = DeleteTraceThreads.builder().projectId(projectId).threadIds(threadIds)
                    .build();

            try (var actualResponse = traceResourceClient.callDeleteTraceThreads(traceThreads, API_KEY,
                    TEST_WORKSPACE)) {
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

            List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .usage(null)
                            .model(null)
                            .provider(null)
                            .traceId(traces.getFirst().id())
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .comments(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateSpansAndAssert(spans, API_KEY, TEST_WORKSPACE);

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            var actualThread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, TEST_WORKSPACE);

            var expectedThreads = getExpectedThreads(traces, projectId, threadId, spans, TraceThreadStatus.ACTIVE);

            TraceAssertions.assertThreads(expectedThreads, List.of(actualThread));
        }

        @Test
        @DisplayName("when trace thread is retrieved with truncate parameter, then messages are truncated accordingly")
        void getTraceThread__whenTruncateParameter__thenMessagesAreTruncatedAccordingly() {

            var threadId = UUID.randomUUID().toString();
            var projectName = UUID.randomUUID().toString();

            // Create a long message that exceeds the truncation threshold of 10001 characters
            var longMessage = "x".repeat(15000);
            var longInput = "{\"content\": \"" + longMessage + "\"}";
            var longOutput = "{\"result\": \"" + longMessage + "\"}";

            var trace1 = createTrace().toBuilder()
                    .threadId(threadId)
                    .projectName(projectName)
                    .input(JsonUtils.getJsonNodeFromString(longInput))
                    .build();

            var trace2 = createTrace().toBuilder()
                    .threadId(threadId)
                    .projectName(projectName)
                    .output(JsonUtils.getJsonNodeFromString(longOutput))
                    .build();

            traceResourceClient.batchCreateTraces(List.of(trace1, trace2), API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            // Test with truncate=false (default behavior) - should return full messages
            var threadWithoutTruncate = traceResourceClient.getTraceThread(threadId, projectId, false, API_KEY,
                    TEST_WORKSPACE);

            assertThat(threadWithoutTruncate.firstMessage()).isNotNull();
            assertThat(threadWithoutTruncate.lastMessage()).isNotNull();
            assertThat(threadWithoutTruncate.firstMessage().toString()).contains(longMessage);
            assertThat(threadWithoutTruncate.lastMessage().toString()).contains(longMessage);

            // Test with truncate=true - should return truncated messages
            var threadWithTruncate = traceResourceClient.getTraceThread(threadId, projectId, true, API_KEY,
                    TEST_WORKSPACE);

            assertThat(threadWithTruncate.firstMessage()).isNotNull();
            assertThat(threadWithTruncate.lastMessage()).isNotNull();
            // Truncated messages should be significantly shorter than the original
            assertThat(threadWithTruncate.firstMessage().toString().length()).isLessThan(longInput.length());
            assertThat(threadWithTruncate.lastMessage().toString().length()).isLessThan(longOutput.length());
            // Truncated messages should be around 10001 characters (the threshold) plus some JSON formatting overhead
            // Allow up to 10% overhead for JSON serialization
            assertThat(threadWithTruncate.firstMessage().toString().length()).isLessThan(11000);
            assertThat(threadWithTruncate.lastMessage().toString().length()).isLessThan(11000);
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

        @ParameterizedTest
        @MethodSource
        @DisplayName("when sorting trace threads by valid fields, then return sorted trace threads")
        void getTraceThreads__whenSortingByValidFields__thenReturnTraceThreadsSorted(
                Comparator<TraceThread> comparator, SortingField sorting) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            // Create multiple threads with different values
            List<String> threadIds = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            Map<String, TraceThreadUpdate> updates = threadIds.stream()
                    .collect(Collectors.toMap(
                            threadId -> threadId,
                            threadId -> factory.manufacturePojo(TraceThreadUpdate.class)));

            // Create traces for each thread with varying number of messages
            List<Trace> allTraces = new ArrayList<>();
            Map<String, List<Trace>> tracesByThread = new HashMap<>();
            Map<String, List<Span>> spansByThread = new HashMap<>();

            for (String threadId : threadIds) {
                // Create multiple traces for each thread
                int numMessages = PodamUtils.getIntegerInRange(3, 5);
                List<Trace> threadTraces = IntStream.range(0, numMessages)
                        .mapToObj(i -> createTrace().toBuilder()
                                .threadId(threadId)
                                .projectName(projectName)
                                .build())
                        .toList();

                traceResourceClient.batchCreateTraces(threadTraces, apiKey, workspaceName);

                threadTraces.forEach(trace -> {
                    List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(spanResourceClient.getTokenUsage())
                                    .totalEstimatedCost(null)
                                    .model(spanResourceClient.randomModel().toString())
                                    .provider(spanResourceClient.provider())
                                    .feedbackScores(null)
                                    .comments(null)
                                    .errorInfo(null)
                                    .build())
                            .toList();

                    spansByThread.computeIfAbsent(threadId, k -> new ArrayList<>()).addAll(spans);
                });

                // Create spans for each trace
                batchCreateSpansAndAssert(spansByThread.get(threadId), apiKey, workspaceName);

                allTraces.addAll(threadTraces);
                tracesByThread.put(threadId, threadTraces);
            }

            // Get project ID
            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            // Build expected threads from our created traces
            List<TraceThread> threads = threadIds.stream()
                    .map(threadId -> {

                        List<Span> spansWithTotalEstimatedCost = spansByThread.get(threadId).stream()
                                .map(span -> span.toBuilder()
                                        .totalEstimatedCost(calculateEstimatedCost(List.of(span)))
                                        .build())
                                .toList();

                        // Update thread to add tags
                        var createdThread = traceResourceClient.getTraceThread(threadId, projectId, apiKey,
                                workspaceName);
                        traceResourceClient.updateThread(updates.get(threadId), createdThread.threadModelId(), apiKey,
                                workspaceName, 204);

                        return getExpectedThreads(tracesByThread.get(threadId), projectId, threadId,
                                spansWithTotalEstimatedCost, TraceThreadStatus.ACTIVE)
                                .stream()
                                .map(thread -> thread.toBuilder()
                                        .tags(updates.get(threadId).tags())
                                        .build())
                                .toList();
                    })
                    .flatMap(List::stream)
                    .toList();

            List<TraceThread> expectedThreads = threads
                    .stream()
                    .sorted(comparator)
                    .toList();

            // mark threads are opened
            threads.forEach(thread -> {
                traceResourceClient.openTraceThread(thread.id(), thread.projectId(), null, apiKey, workspaceName);
            });

            // Get trace threads with sorting
            TraceThreadPage traceThreadPage = traceResourceClient.getTraceThreads(projectId, null,
                    apiKey, workspaceName, null, List.of(sorting), null);

            // Verify that the sortableBy field contains the expected values
            assertSortableFields(traceThreadPage);

            // Verify that the threads are sorted correctly
            TraceAssertions.assertThreads(expectedThreads, traceThreadPage.content());
        }

        private Stream<Arguments> getTraceThreads__whenSortingByValidFields__thenReturnTraceThreadsSorted() {
            Comparator<TraceThread> tagsComparator = Comparator.comparing(thread -> thread.tags().toString());
            return Stream.of(
                    Arguments.of(
                            Comparator.comparing(TraceThread::totalEstimatedCost)
                                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::totalEstimatedCost).reversed()
                                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::numberOfMessages)
                                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.NUMBER_OF_MESSAGES).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::numberOfMessages).reversed()
                                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.NUMBER_OF_MESSAGES).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::id),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::id).reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::startTime),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::startTime).reversed(),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::endTime),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::endTime).reversed(),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::duration),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::duration).reversed(),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::lastUpdatedAt),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::createdBy)
                                    .thenComparing(TraceThread::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::createdBy).reversed()
                                    .thenComparing(TraceThread::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::createdAt)
                                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(TraceThread::createdAt).reversed()
                                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                    Arguments.of(
                            tagsComparator,
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                    Arguments.of(
                            tagsComparator.reversed(),
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()));
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        @DisplayName("when sorting trace threads by usage, then return sorted trace threads")
        void getTraceThreads__whenSortingByUsage__thenReturnTraceThreadsSorted(Direction direction) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            // Create multiple threads with different values
            List<String> threadIds = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            // Ensure usage data is consistent across threads
            List<String> usageKey = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            // Create traces for each thread with varying number of messages and usage data
            List<Trace> allTraces = new ArrayList<>();
            Map<String, List<Trace>> tracesByThread = new HashMap<>();
            Map<String, List<Span>> spansByThread = new HashMap<>();

            for (String threadId : threadIds) {
                // Create multiple traces for each thread
                int numMessages = PodamUtils.getIntegerInRange(3, 5);
                List<Trace> threadTraces = new ArrayList<>();

                for (int j = 0; j < numMessages; j++) {

                    Trace trace = createTrace().toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .build();

                    // Create spans for each trace to ensure usage data is properly stored
                    List<Span> spans = createSpans(trace, projectName, usageKey, null);

                    spansByThread.computeIfAbsent(threadId, k -> new ArrayList<>()).addAll(spans);
                    threadTraces.add(trace);
                }

                List<Span> spans = spansByThread
                        .values()
                        .stream()
                        .flatMap(List::stream)
                        .toList();

                batchCreateSpansAndAssert(spans, apiKey, workspaceName);
                allTraces.addAll(threadTraces);
                tracesByThread.put(threadId, threadTraces);
            }

            traceResourceClient.batchCreateTraces(allTraces, apiKey, workspaceName);

            // Get project ID
            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            // Sort by usage.total_tokens
            String usageField = usageKey.get(PodamUtils.getIntegerInRange(0, usageKey.size() - 1));
            var sortingField = new SortingField(
                    "usage.%s".formatted(usageField),
                    direction);

            // Create comparator for sorting by usage.total_tokens
            Comparator<TraceThread> comparator = Comparator.comparing(
                    (TraceThread thread) -> thread.usage().get(usageField),
                    direction == Direction.ASC
                            ? Comparator.nullsFirst(Comparator.naturalOrder())
                            : Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(TraceThread::lastUpdatedAt).reversed());

            // Build expected threads from our created traces
            List<TraceThread> expectedThreads = threadIds.stream()
                    .map(threadId -> getExpectedThreads(tracesByThread.get(threadId), projectId, threadId,
                            spansByThread.get(threadId), TraceThreadStatus.ACTIVE))
                    .flatMap(List::stream)
                    .sorted(comparator)
                    .toList();

            // Get trace threads
            TraceThreadPage traceThreadPage = traceResourceClient.getTraceThreads(projectId, null, apiKey,
                    workspaceName, null, List.of(sortingField), null);

            // Verify that the sortableBy field contains the expected values
            assertSortableFields(traceThreadPage);

            // Get the actual threads
            List<TraceThread> actualThreads = traceThreadPage.content();

            // Verify that the threads are sorted correctly
            TraceAssertions.assertThreads(expectedThreads, actualThreads);
        }

        private List<Span> createSpans(Trace trace, String projectName, List<String> usageKey,
                BigDecimal totalEstimatedCost) {
            Map<String, Integer> usage;

            if (CollectionUtils.isNotEmpty(usageKey)) {
                usage = usageKey
                        .stream()
                        .map(key -> Map.entry(key, PodamUtils.getIntegerInRange(0, 10)))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            } else {
                usage = null;
            }

            return PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(usage)
                            .totalEstimatedCost(totalEstimatedCost)
                            .model(spanResourceClient.randomModel().toString())
                            .provider(spanResourceClient.provider())
                            .build())
                    .toList();
        }

        private static void assertSortableFields(TraceThreadPage traceThreadPage) {
            assertThat(traceThreadPage.sortableBy()).contains(
                    SortableFields.ID,
                    SortableFields.START_TIME,
                    SortableFields.END_TIME,
                    SortableFields.DURATION,
                    SortableFields.NUMBER_OF_MESSAGES,
                    SortableFields.LAST_UPDATED_AT,
                    SortableFields.CREATED_BY,
                    SortableFields.CREATED_AT,
                    SortableFields.TOTAL_ESTIMATED_COST,
                    SortableFields.USAGE);
        }

        @Test
        @DisplayName("when sorting by invalid field, then ignore and return success")
        void getTraceThreads__whenSortingByInvalidField__thenIgnoreAndReturnSuccess() {
            var field = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var sortingFields = List.of(SortingField.builder().field(field).direction(Direction.ASC).build());
            var actualResponse = traceResourceClient.callGetTraceThreadsWithSorting(projectId, sortingFields, API_KEY,
                    TEST_WORKSPACE);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(Trace.TracePage.class);
            assertThat(actualEntity).isNotNull();
        }

    }

    /**
     * This class is testing the creation of trace threads. The feature is not completely implemented yet,
     * so some tests are using services to check the database state. In the future, this should be do via the APIs
     * */
    @Nested
    @DisplayName("Trace Threads Creation")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TraceThreadCreation {

        @Test
        @DisplayName("when trace threads are created, then create trace thread id async")
        void createTraceThreads() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            var threadId = randomUUID().toString();

            // Create multiple trace within same thread
            List<Trace> traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> fromBuilder(trace.toBuilder()).toBuilder()
                            .projectId(projectId)
                            .projectName(projectName)
                            .threadId(threadId)
                            .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                            .build())
                    .toList();

            // When: Creating trace threads
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Then: Assert that trace thread is created using getTraceThreads API
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                var traceThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                        List.of(), List.of(), Map.of());

                var expectedThreads = getExpectedThreads(traces, projectId, threadId, List.of(),
                        TraceThreadStatus.ACTIVE);

                TraceAssertions.assertThreads(expectedThreads, traceThreadPage.content());
            });
        }

        @Test
        @DisplayName("when trace threads receive concurrent requests, then create trace thread only once")
        void createTraceThreads__whenConcurrentRequests__thenCreateTraceThreadOnlyOnce() {
            //Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            String threadId1 = randomUUID().toString();
            String threadId2 = randomUUID().toString();

            // Create multiple trace within same thread
            List<List<Trace>> traces = IntStream.range(0, 5)
                    .mapToObj(i -> PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                            .stream()
                            .map(trace -> fromBuilder(trace.toBuilder()).toBuilder()
                                    .projectId(projectId)
                                    .projectName(projectName)
                                    .threadId(PodamUtils.getIntegerInRange(0, 1) % 2 == 0 ? threadId1 : threadId2)
                                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                                    .build())
                            .toList())
                    .toList();

            // When: Creating trace thread concurrently
            traces.parallelStream()
                    .forEach(traceList -> traceResourceClient.batchCreateTraces(traceList, apiKey, workspaceName));

            // Then: Assert that trace threads are created only once using getTraceThreads API
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                var traceThreadPage = traceResourceClient.getTraceThreads(null, projectName, apiKey, workspaceName,
                        List.of(), List.of(), Map.of());

                // Get traces for each thread
                var tracesForThread1 = traces.stream()
                        .flatMap(List::stream)
                        .filter(trace -> threadId1.equals(trace.threadId()))
                        .toList();

                var tracesForThread2 = traces.stream()
                        .flatMap(List::stream)
                        .filter(trace -> threadId2.equals(trace.threadId()))
                        .toList();

                var expectedThread1 = getExpectedThreads(tracesForThread1, projectId, threadId1, List.of(),
                        TraceThreadStatus.ACTIVE).get(0);
                var expectedThread2 = getExpectedThreads(tracesForThread2, projectId, threadId2, List.of(),
                        TraceThreadStatus.ACTIVE).get(0);

                var expectedThreads = Stream.of(expectedThread1, expectedThread2)
                        .sorted(Comparator.comparing(TraceThread::lastUpdatedAt).reversed())
                        .toList();

                TraceAssertions.assertThreads(expectedThreads, traceThreadPage.content());
            });
        }
    }

    @Nested
    @DisplayName("Trace Thread Manual Open/Close")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TraceThreadManualOpenClose {

        @Test
        @DisplayName("when trace thread is manually closed then opened, then reopen the thread")
        void manualCloseAndReopeningTraceThread() {
            // Given: Ingestion of a trace with a thread
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            String threadId = randomUUID().toString();

            // Create a trace with the thread
            Trace trace = createTrace().toBuilder()
                    .threadId(threadId)
                    .projectId(projectId)
                    .projectName(projectName)
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            traceResourceClient.createTrace(trace, apiKey, workspaceName);

            // Assert that the thread is created and open using getTraceThreads API
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                var traceThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                        List.of(), List.of(), Map.of());

                var expectedActiveThreads = getExpectedThreads(List.of(trace), projectId, threadId, List.of(),
                        TraceThreadStatus.ACTIVE);

                TraceAssertions.assertThreads(expectedActiveThreads, traceThreadPage.content());
            });

            // When: Manually close the thread
            Mono.delay(Duration.ofMillis(500)).block();
            traceResourceClient.closeTraceThread(threadId, projectId, null, apiKey, workspaceName);

            // Then: Assert that the thread is closed using getTraceThreads API
            var closedThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            var expectedClosedThreads = getExpectedThreads(List.of(trace), projectId, threadId, List.of(),
                    TraceThreadStatus.INACTIVE);

            TraceAssertions.assertThreads(expectedClosedThreads, closedThreadPage.content());

            // When: Manually reopen the thread
            traceResourceClient.openTraceThread(threadId, null, projectName, apiKey, workspaceName);

            // Then: Assert that the thread is reopened using getTraceThreads API
            var reopenedThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            var expectedReopenedThreads = getExpectedThreads(List.of(trace), projectId, threadId, List.of(),
                    TraceThreadStatus.ACTIVE);

            TraceAssertions.assertThreads(expectedReopenedThreads, reopenedThreadPage.content());
        }

        @Test
        @DisplayName("when trace thread was created before the entity was introduced, then it should be created as inactive")
        void manualCloseAndReopeningTraceThread__whenThreadCreatedBeforeEntityIntroduced__thenInactive(
                TransactionTemplateAsync templateAsync) {

            // Given: Ingestion of a trace with a thread
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            String threadId = randomUUID().toString();

            // Create a trace with the thread
            Trace trace = createTrace().toBuilder()
                    .threadId(threadId)
                    .projectId(projectId)
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();

            // Create the trace via DB to simulate the scenario where the thread was created before the entity was introduced
            TraceDBUtils.createTraceViaDB(trace, workspaceId, templateAsync);

            // When: Manually close the thread
            traceResourceClient.closeTraceThread(threadId, projectId, null, apiKey, workspaceName);

            // Then: Assert that the thread is closed using getTraceThreads API
            var closedThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            var expectedClosedThreads = getExpectedThreads(List.of(trace), projectId, threadId, List.of(),
                    TraceThreadStatus.INACTIVE);

            TraceAssertions.assertThreads(expectedClosedThreads, closedThreadPage.content());
        }

        @Test
        void closeMultipleTraceThreads__happyPath() {
            // Given: Create multiple traces with different thread IDs
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            var threadId1 = randomUUID().toString();
            var threadId2 = randomUUID().toString();
            var threadId3 = randomUUID().toString();

            // Create traces with different thread IDs
            Trace trace1 = createTrace().toBuilder()
                    .threadId(threadId1)
                    .projectId(projectId)
                    .projectName(projectName)
                    .build();

            Trace trace2 = createTrace().toBuilder()
                    .threadId(threadId2)
                    .projectId(projectId)
                    .projectName(projectName)
                    .build();

            Trace trace3 = createTrace().toBuilder()
                    .threadId(threadId3)
                    .projectId(projectId)
                    .projectName(projectName)
                    .build();

            traceResourceClient.batchCreateTraces(List.of(trace1, trace2, trace3), apiKey, workspaceName);

            // Wait for threads to be created
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                var traceThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                        List.of(), List.of(), Map.of());
                assertThat(traceThreadPage.content()).hasSize(3);
            });

            // When: Close multiple trace threads using the batch endpoint
            traceResourceClient.closeTraceThreads(Set.of(threadId1, threadId2, threadId3), projectId, projectName,
                    apiKey, workspaceName);

            // Then: Assert that all threads are closed using getTraceThreads API
            var closedThreadPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName,
                    List.of(), List.of(), Map.of());

            var expectedClosedThreads = List.of(
                    getExpectedThreads(List.of(trace1), projectId, threadId1, List.of(), TraceThreadStatus.INACTIVE)
                            .getFirst(),
                    getExpectedThreads(List.of(trace2), projectId, threadId2, List.of(), TraceThreadStatus.INACTIVE)
                            .getFirst(),
                    getExpectedThreads(List.of(trace3), projectId, threadId3, List.of(), TraceThreadStatus.INACTIVE)
                            .getFirst());

            TraceAssertions.assertThreads(expectedClosedThreads, closedThreadPage.content());
        }
    }

    @Nested
    @DisplayName("Thread Comment:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadComment {

        @Test
        void createCommentForNonExistingThreadFail() {
            threadCommentResourceClient.generateAndCreateComment(generator.generate(), API_KEY, TEST_WORKSPACE, 404);
        }

        @Test
        void createAndGetComment() {
            // Create comment for existing thread
            var thread = createThread();
            Comment expectedComment = threadCommentResourceClient.generateAndCreateComment(thread.threadModelId(),
                    API_KEY, TEST_WORKSPACE,
                    201);

            // Get created comment by id and assert
            Comment actualComment = threadCommentResourceClient.getCommentById(expectedComment.id(),
                    thread.threadModelId(), API_KEY,
                    TEST_WORKSPACE, 200);
            assertComment(expectedComment, actualComment);
        }

        @Test
        void createAndUpdateComment() {
            // Create comment for existing thread
            var thread = createThread();
            Comment expectedComment = threadCommentResourceClient.generateAndCreateComment(thread.threadModelId(),
                    API_KEY, TEST_WORKSPACE,
                    201);

            // Get created comment by id and assert
            Comment actualComment = threadCommentResourceClient.getCommentById(expectedComment.id(),
                    thread.threadModelId(), API_KEY,
                    TEST_WORKSPACE, 200);

            // Update existing comment
            String updatedText = factory.manufacturePojo(String.class);
            threadCommentResourceClient.updateComment(updatedText, expectedComment.id(), API_KEY,
                    TEST_WORKSPACE, 204);

            // Get comment by id and assert it was updated
            Comment updatedComment = threadCommentResourceClient.getCommentById(expectedComment.id(),
                    thread.threadModelId(), API_KEY,
                    TEST_WORKSPACE, 200);
            assertUpdatedComment(actualComment, updatedComment, updatedText);
        }

        @Test
        void deleteComments() {
            // Create comments for existing thread
            var thread = createThread();

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> threadCommentResourceClient.generateAndCreateComment(thread.threadModelId(), API_KEY,
                            TEST_WORKSPACE, 201))
                    .toList().reversed();

            // Check it was created
            expectedComments.forEach(
                    comment -> threadCommentResourceClient.getCommentById(comment.id(), thread.threadModelId(), API_KEY,
                            TEST_WORKSPACE, 200));

            // Delete comment
            BatchDelete request = BatchDelete.builder()
                    .ids(expectedComments.stream().map(Comment::id).collect(Collectors.toSet())).build();
            threadCommentResourceClient.deleteComments(request, API_KEY, TEST_WORKSPACE);

            // Verify comments were actually deleted via get and update endpoints
            expectedComments.forEach(
                    comment -> threadCommentResourceClient.getCommentById(comment.id(), thread.threadModelId(), API_KEY,
                            TEST_WORKSPACE, 404));
            expectedComments
                    .forEach(comment -> threadCommentResourceClient.updateComment(factory.manufacturePojo(String.class),
                            comment.id(), API_KEY, TEST_WORKSPACE, 404));
        }

        @Test
        void getThreadWithComments() {
            // Create comments for existing thread
            var thread = createThread();

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> threadCommentResourceClient.generateAndCreateComment(thread.threadModelId(), API_KEY,
                            TEST_WORKSPACE, 201))
                    .toList().reversed();

            TraceThread actualThread = traceResourceClient.getTraceThread(thread.id(), thread.projectId(), API_KEY,
                    TEST_WORKSPACE);

            assertComments(expectedComments, actualThread.comments().reversed());
        }

        @Test
        void getThreadsWithComments() {
            // Create comments for existing thread
            var thread = createThread();

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> threadCommentResourceClient.generateAndCreateComment(thread.threadModelId(), API_KEY,
                            TEST_WORKSPACE, 201))
                    .toList().reversed();

            var threadsPage = traceResourceClient.getTraceThreads(thread.projectId(), null, API_KEY, TEST_WORKSPACE,
                    null, null, null);

            assertComments(expectedComments, threadsPage.content().getFirst().comments().reversed());
        }
    }

    @Nested
    @DisplayName("Thread Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadUpdate {

        @Test
        void updateNonExistingThreadFail() {
            traceResourceClient.updateThread(factory.manufacturePojo(TraceThreadUpdate.class), generator.generate(),
                    API_KEY, TEST_WORKSPACE, 404);
        }

        @Test
        void createAndUpdateThread() {
            // Create comment for existing thread
            var thread = createThread();

            // Check that it doesn't have tags
            assertThat(thread.tags()).isNull();

            // Update thread
            var update = factory.manufacturePojo(TraceThreadUpdate.class);
            traceResourceClient.updateThread(update, thread.threadModelId(), API_KEY, TEST_WORKSPACE, 204);

            // Check that update was applied
            var actualThread = traceResourceClient.getTraceThread(thread.id(), thread.projectId(), API_KEY,
                    TEST_WORKSPACE);
            TraceAssertions.assertThreads(List.of(thread.toBuilder()
                    .tags(update.tags())
                    .build()), List.of(actualThread));

        }
    }

    private TraceThread createThread() {
        var threadId = UUID.randomUUID().toString();
        var projectName = UUID.randomUUID().toString();

        var traces = IntStream.range(0, 5)
                .mapToObj(i -> createTrace().toBuilder()
                        .threadId(threadId)
                        .projectName(projectName)
                        .build())
                .toList();

        traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);
        var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

        //Wait for the thread to be created
        Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            TraceThread traceThread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, TEST_WORKSPACE);
            Assertions.assertThat(traceThread.threadModelId()).isNotNull();
        });

        return traceResourceClient.getTraceThread(threadId, projectId, API_KEY, TEST_WORKSPACE);
    }

    private void assertErrorResponse(Response actualResponse, String message, int expected) {
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expected);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(message);
    }

    private void fetchAndAssertResponse(List<String> expectedNames, UUID projectId, String apiKey,
            String workspaceName) {

        try (var actualResponse = traceResourceClient.callGetFeedbackScoresToNames(projectId, apiKey, workspaceName)) {

            // then
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            var actualEntity = actualResponse.readEntity(FeedbackScoreNames.class);

            assertFeedbackScoreNames(actualEntity, expectedNames);
        }
    }

    private void createAndAssertForSpan(FeedbackScoreBatchContainer request, String workspaceName, String apiKey) {
        try (var actualResponse = spanResourceClient.callFeedbackScoresWithContainer(request, apiKey, workspaceName)) {

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
        if (CollectionUtils.isEmpty(spans)) {
            return null;
        }
        return spans.stream()
                .filter(span -> span.usage() != null)
                .flatMap(span -> span.usage().entrySet().stream())
                .map(entry -> Map.entry(entry.getKey(), Long.valueOf(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    private void mockGetWorkspaceIdByName(String workspaceName, String workspaceId) {
        AuthTestUtils.mockGetWorkspaceIdByName(wireMock.server(), workspaceName, workspaceId);
    }

    private List<List<FeedbackScoreBatchItemThread>> createMultiValueTraceThreadScores(
            List<String> multipleValuesFeedbackScores,
            Project project, String apiKey, String workspaceName) {
        return IntStream.range(0, multipleValuesFeedbackScores.size())
                .mapToObj(i -> {

                    String threadId = UUID.randomUUID().toString();

                    Trace trace1 = createTrace().toBuilder()
                            .projectName(project.name())
                            .threadId(threadId)
                            .build();

                    Trace trace2 = createTrace().toBuilder()
                            .projectName(project.name())
                            .threadId(threadId)
                            .build();

                    traceResourceClient.batchCreateTraces(List.of(trace1, trace2), apiKey, workspaceName);

                    // Close thread
                    Mono.delay(Duration.ofMillis(500)).block();
                    traceResourceClient.closeTraceThread(threadId, project.id(), null, apiKey, workspaceName);

                    List<FeedbackScoreBatchItemThread> scores = multipleValuesFeedbackScores.stream()
                            .map(name -> factory.manufacturePojo(FeedbackScoreBatchItemThread.class).toBuilder()
                                    .name(name)
                                    .projectName(project.name())
                                    .threadId(threadId)
                                    .build())
                            .collect(Collectors.toList());

                    traceResourceClient.threadFeedbackScores(scores, apiKey, workspaceName);

                    return scores;
                }).toList();
    }

    @Nested
    @DisplayName("Thread Reopening and Manual Score Deletion")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadReopeningManualScoreDeletion {

        @Test
        @DisplayName("When thread is closed, manually scored, and reopened, then manual scores are deleted")
        void whenThreadIsClosedManuallyScored_andReopened_thenManualScoresAreDeleted() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            var threadId = UUID.randomUUID().toString();

            // Create initial traces for the thread
            List<Trace> initialTraces = IntStream.range(0, 3)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectName(projectName)
                            .threadId(threadId)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(initialTraces, apiKey, workspaceName);

            // Wait for thread processing
            Mono.delay(Duration.ofMillis(500)).block();

            // Close the thread
            traceResourceClient.closeTraceThread(threadId, projectId, null, apiKey, workspaceName);

            // Wait for thread to be closed

            // Add manual scores to the closed thread
            List<FeedbackScoreBatchItemThread> manualScores = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItemThread.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .source(ScoreSource.UI)
                            .build())
                    .collect(Collectors.toList());

            manualScores.set(0, manualScores.get(0)
                    .toBuilder()
                    .source(ScoreSource.SDK)
                    .build());

            traceResourceClient.threadFeedbackScores(manualScores, apiKey, workspaceName);

            // Create new traces to reopen the thread
            List<Trace> newTraces = IntStream.range(0, 2)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectName(projectName)
                            .threadId(threadId)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(newTraces, apiKey, workspaceName);

            // Wait for thread to be reopened and manual scores to be deleted
            Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        var actualThreads = traceResourceClient.getTraceThreads(projectId, null, apiKey, workspaceName,
                                null, null, null);

                        List<Trace> allTraces = Stream.concat(initialTraces.stream(), newTraces.stream()).toList();

                        var expectedReopenedThreads = getExpectedThreads(allTraces, projectId, threadId, List.of(),
                                TraceThreadStatus.ACTIVE);

                        // Verify manual scores have been deleted
                        TraceAssertions.assertThreads(expectedReopenedThreads, actualThreads.content());
                    });
        }

        @Test
        @DisplayName("When thread is closed, manually scored, and reopened, then only manual scores are deleted (not automatic scores)")
        void whenThreadIsClosedWithMixedScores_andReopened_thenOnlyManualScoresAreDeleted() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            var threadId = UUID.randomUUID().toString();

            // Create initial traces for the thread
            List<Trace> initialTraces = IntStream.range(0, 2)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectName(projectName)
                            .threadId(threadId)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(initialTraces, apiKey, workspaceName);

            // Wait for thread processing
            Mono.delay(Duration.ofMillis(500)).block();

            // Close the thread
            traceResourceClient.closeTraceThread(threadId, projectId, null, apiKey, workspaceName);

            // Wait for thread to be closed

            // Add mixed scores to the closed thread (manual and automatic)
            List<FeedbackScoreBatchItemThread> mixedScores = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItemThread.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .source(ScoreSource.SDK)
                            .build())
                    .collect(Collectors.toList());

            mixedScores.set(0, mixedScores.getFirst()
                    .toBuilder()
                    .source(ScoreSource.ONLINE_SCORING)
                    .build());

            Instant createdAt = Instant.now();
            traceResourceClient.threadFeedbackScores(mixedScores, apiKey, workspaceName);

            // Create new traces to reopen the thread
            List<Trace> newTraces = IntStream.range(0, 2)
                    .mapToObj(i -> createTrace().toBuilder()
                            .projectName(projectName)
                            .threadId(threadId)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(newTraces, apiKey, workspaceName);

            // Wait for thread to be reopened and manual scores to be deleted
            Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .untilAsserted(() -> {

                        var actualThreads = traceResourceClient.getTraceThreads(projectId, null, apiKey, workspaceName,
                                null, null, null);

                        List<Trace> allTraces = Stream.concat(initialTraces.stream(), newTraces.stream()).toList();

                        var expectedReopenedThreads = getExpectedThreads(allTraces, projectId, threadId, List.of(),
                                TraceThreadStatus.ACTIVE,
                                List.of(createExpectedFeedbackScore(mixedScores.getFirst(), createdAt)));

                        // Verify manual scores have been deleted, but automatic scores remain
                        TraceAssertions.assertThreads(expectedReopenedThreads, actualThreads.content());
                    });
        }
    }

    @Nested
    @DisplayName("Batch Update Threads Tags:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchUpdateThreads {

        Stream<Arguments> mergeTagsTestCases() {
            return Stream.of(
                    Arguments.of(true, "merge", 3),
                    Arguments.of(false, "replace", 2));
        }

        @ParameterizedTest(name = "Success: batch update tags with {1} mode")
        @MethodSource("mergeTagsTestCases")
        @DisplayName("Success: batch update tags for multiple threads")
        void batchUpdate__success(boolean mergeTags, String mode, int threadCount) {
            // Create thread IDs
            var threadId1 = UUID.randomUUID().toString();
            var threadId2 = UUID.randomUUID().toString();
            var threadId3 = mergeTags ? UUID.randomUUID().toString() : null;

            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);
            var projectId = projectResourceClient.getByName(projectName, API_KEY, TEST_WORKSPACE).id();

            // Create traces to create threads
            create(createTrace().toBuilder().projectName(projectName).threadId(threadId1).build(), API_KEY,
                    TEST_WORKSPACE);
            create(createTrace().toBuilder().projectName(projectName).threadId(threadId2).build(), API_KEY,
                    TEST_WORKSPACE);
            if (mergeTags) {
                create(createTrace().toBuilder().projectName(projectName).threadId(threadId3).build(), API_KEY,
                        TEST_WORKSPACE);
            }

            // Wait for threads to be created
            Awaitility.await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        var threads = traceResourceClient.getTraceThreads(projectId, null, API_KEY, TEST_WORKSPACE,
                                null, null, null);
                        assertThat(threads.content()).hasSize(threadCount);
                    });

            var threads = traceResourceClient.getTraceThreads(projectId, null, API_KEY, TEST_WORKSPACE,
                    null, null, null);
            var threadModelId1 = threads.content().stream().filter(t -> t.id().equals(threadId1)).findFirst()
                    .get().threadModelId();
            var threadModelId2 = threads.content().stream().filter(t -> t.id().equals(threadId2)).findFirst()
                    .get().threadModelId();
            var threadModelId3 = mergeTags
                    ? threads.content().stream().filter(t -> t.id().equals(threadId3)).findFirst()
                            .get().threadModelId()
                    : null;

            // Update threads with existing tags
            if (mergeTags) {
                traceResourceClient.updateThread(TraceThreadUpdate.builder().tags(Set.of("existing-tag-1")).build(),
                        threadModelId1, API_KEY, TEST_WORKSPACE, 204);
                traceResourceClient.updateThread(TraceThreadUpdate.builder().tags(Set.of("existing-tag-2")).build(),
                        threadModelId2, API_KEY, TEST_WORKSPACE, 204);
            } else {
                traceResourceClient.updateThread(
                        TraceThreadUpdate.builder().tags(Set.of("old-tag-1", "old-tag-2")).build(),
                        threadModelId1, API_KEY, TEST_WORKSPACE, 204);
                traceResourceClient.updateThread(TraceThreadUpdate.builder().tags(Set.of("old-tag-3")).build(),
                        threadModelId2, API_KEY, TEST_WORKSPACE, 204);
            }

            // Batch update with new tags
            var newTags = mergeTags ? Set.of("new-tag-1", "new-tag-2") : Set.of("new-tag");
            var ids = mergeTags
                    ? Set.of(threadModelId1, threadModelId2, threadModelId3)
                    : Set.of(threadModelId1, threadModelId2);
            var batchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(ids)
                    .update(TraceThreadUpdate.builder()
                            .tags(newTags)
                            .build())
                    .mergeTags(mergeTags)
                    .build();

            traceResourceClient.batchUpdateThreads(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Verify threads were updated
            var thread1 = traceResourceClient.getTraceThread(threadId1, projectId, API_KEY, TEST_WORKSPACE);
            if (mergeTags) {
                assertThat(thread1.tags()).containsExactlyInAnyOrder("existing-tag-1", "new-tag-1", "new-tag-2");
            } else {
                assertThat(thread1.tags()).containsExactly("new-tag");
            }

            var thread2 = traceResourceClient.getTraceThread(threadId2, projectId, API_KEY, TEST_WORKSPACE);
            if (mergeTags) {
                assertThat(thread2.tags()).containsExactlyInAnyOrder("existing-tag-2", "new-tag-1", "new-tag-2");
            } else {
                assertThat(thread2.tags()).containsExactly("new-tag");
            }

            if (mergeTags) {
                var thread3 = traceResourceClient.getTraceThread(threadId3, projectId, API_KEY, TEST_WORKSPACE);
                assertThat(thread3.tags()).containsExactlyInAnyOrder("new-tag-1", "new-tag-2");
            }
        }

        @Test
        @DisplayName("when batch updating threads multiple times, then latest values are preserved")
        void batchUpdate__whenMultiplePartialUpdates__thenLatestValuesPreserved() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);
            var projectId = projectResourceClient.getByName(projectName, API_KEY, TEST_WORKSPACE).id();

            // Create a thread by creating a trace
            var threadId = UUID.randomUUID().toString();
            create(createTrace().toBuilder()
                    .projectName(projectName)
                    .threadId(threadId)
                    .build(),
                    API_KEY, TEST_WORKSPACE);

            // Wait for thread to be created
            Awaitility.await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        var threads = traceResourceClient.getTraceThreads(
                                projectId, null, API_KEY, TEST_WORKSPACE, null, null, null);
                        assertThat(threads.content()).hasSize(1);
                    });

            var threads = traceResourceClient.getTraceThreads(
                    projectId, null, API_KEY, TEST_WORKSPACE, null, null, null);
            var threadModelId = threads.content().getFirst().threadModelId();

            // First batch update: Set original tags
            var originalTags = Set.of("tag-" + RandomStringUtils.secure().nextAlphanumeric(8));
            var firstBatchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(Set.of(threadModelId))
                    .update(TraceThreadUpdate.builder()
                            .tags(originalTags)
                            .build())
                    .mergeTags(false)
                    .build();
            traceResourceClient.batchUpdateThreads(firstBatchUpdate, API_KEY, TEST_WORKSPACE);

            // Second batch update: Update with new tags
            var secondTags = Set.of("updated-" + RandomStringUtils.secure().nextAlphanumeric(8));
            var secondBatchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(Set.of(threadModelId))
                    .update(TraceThreadUpdate.builder()
                            .tags(secondTags)
                            .build())
                    .mergeTags(false)
                    .build();
            traceResourceClient.batchUpdateThreads(secondBatchUpdate, API_KEY, TEST_WORKSPACE);

            // Third batch update: Update with final tags
            var thirdTags = Set.of("final-" + RandomStringUtils.secure().nextAlphanumeric(8));
            var thirdBatchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(Set.of(threadModelId))
                    .update(TraceThreadUpdate.builder()
                            .tags(thirdTags)
                            .build())
                    .mergeTags(false)
                    .build();
            traceResourceClient.batchUpdateThreads(thirdBatchUpdate, API_KEY, TEST_WORKSPACE);

            // Verify that thread has the latest tags (not from first or second update)
            var finalThread = traceResourceClient.getTraceThread(
                    threadId, projectId, API_KEY, TEST_WORKSPACE);
            assertThat(finalThread.tags()).containsExactlyInAnyOrderElementsOf(thirdTags);
        }

        @Test
        @DisplayName("when batch update with empty IDs, then return 400")
        void batchUpdate__whenEmptyIds__thenReturn400() {
            var batchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(Set.of())
                    .update(TraceThreadUpdate.builder()
                            .tags(Set.of("tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            try (var actualResponse = traceResourceClient.callBatchUpdateThreads(batchUpdate, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                var error = actualResponse.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anySatisfy(msg -> assertThat(msg).contains("ids"));
            }
        }

        @Test
        @DisplayName("when batch update with too many IDs, then return 400")
        void batchUpdate__whenTooManyIds__thenReturn400() {
            // Create 1001 IDs (exceeds max of 1000)
            var ids = new HashSet<UUID>();
            for (int i = 0; i < 1001; i++) {
                ids.add(generator.generate());
            }

            var batchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(ids)
                    .update(TraceThreadUpdate.builder()
                            .tags(Set.of("tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            try (var actualResponse = traceResourceClient.callBatchUpdateThreads(batchUpdate, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                var error = actualResponse.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anySatisfy(msg -> assertThat(msg).contains("ids"));
            }
        }

        @Test
        @DisplayName("when batch update with null update, then return 400")
        void batchUpdate__whenNullUpdate__thenReturn400() {
            var batchUpdate = TraceThreadBatchUpdate.builder()
                    .ids(Set.of(generator.generate()))
                    .update(null)
                    .mergeTags(true)
                    .build();

            try (var actualResponse = traceResourceClient.callBatchUpdateThreads(batchUpdate, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
            }
        }
    }

}