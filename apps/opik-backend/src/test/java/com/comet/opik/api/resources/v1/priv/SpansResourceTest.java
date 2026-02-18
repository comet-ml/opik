package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchContainer;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RandomTestUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AttachmentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AttachmentPayloadUtilsTest;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
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
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.domain.SpanService.PARENT_SPAN_IS_MISMATCH;
import static com.comet.opik.domain.SpanService.PROJECT_AND_WORKSPACE_NAME_MISMATCH;
import static com.comet.opik.domain.SpanService.TRACE_ID_MISMATCH;
import static com.comet.opik.domain.cost.CostService.getCostFromMetadata;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.utils.ValidationUtils.MAX_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.MIN_FEEDBACK_SCORE_VALUE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/spans";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mySqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final GenericContainer<?> minIOContainer = MinIOContainerUtils.newMinIOContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mySqlContainer, clickHouseContainer, zookeeperContainer, minIOContainer)
                .join();
        String minioUrl = "http://%s:%d".formatted(minIOContainer.getHost(), minIOContainer.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mySqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mySqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .isMinIO(true)
                        .minioUrl(minioUrl)
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private AttachmentResourceClient attachmentResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) throws SQLException {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.attachmentResourceClient = new AttachmentResourceClient(this.client);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void mockGetWorkspaceIdByName(String workspaceName, String workspaceId) {
        AuthTestUtils.mockGetWorkspaceIdByName(wireMock.server(), workspaceName, workspaceId);
    }

    private void createAndAssert(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        spanResourceClient.feedbackScore(entityId, score, workspaceName, apiKey);
    }

    private void getAndAssertPage(
            String workspaceName,
            String projectName,
            List<? extends SpanFilter> filters,
            List<Span> spans,
            List<Span> expectedSpans,
            List<Span> unexpectedSpans,
            String apiKey,
            List<SortingField> sortingFields,
            List<Span.SpanField> exclude) {
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
                unexpectedSpans,
                apiKey,
                sortingFields,
                exclude);
    }

    private void getAndAssertPage(
            String workspaceName,
            String projectName,
            UUID projectId,
            UUID traceId,
            SpanType type,
            List<? extends SpanFilter> filters,
            int page,
            int size,
            List<Span> expectedSpans,
            int expectedTotal,
            List<Span> unexpectedSpans,
            String apiKey,
            List<SortingField> sortingFields,
            List<Span.SpanField> exclude) {

        Span.SpanPage actualPage = spanResourceClient.findSpans(
                workspaceName,
                apiKey,
                projectName,
                projectId,
                page,
                size,
                traceId,
                type,
                filters,
                sortingFields,
                exclude);

        SpanAssertions.assertPage(actualPage, page, expectedSpans.size(), expectedTotal);
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, unexpectedSpans, USER);
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
                                                    HttpStatus.SC_UNAUTHORIZED)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void create__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(span))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_CREATED, errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void update__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, okApikey, workspaceName);

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

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT, errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void delete__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NOT_IMPLEMENTED,
                        errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var expectedSpan = actualResponse.readEntity(Span.class);
                    assertThat(expectedSpan.id()).isEqualTo(spanId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility, int expectedCode) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, okApikey, workspaceName);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();

            spanResourceClient.createSpan(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", span.projectName())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 200) {
                    var expectedSpans = actualResponse.readEntity(Span.SpanPage.class);
                    assertThat(expectedSpans.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(project.name()));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnSpanStats(String apiKey,
                Visibility visibility, int expectedCode) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, okApikey, workspaceName);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();
            spanResourceClient.createSpan(span, okApikey, workspaceName);

            spanResourceClient.getSpansStats(project.name(), null, List.of(), apiKey, workspaceName, Map.of(),
                    expectedCode);
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenApiKeyIsPresent__thenReturnSpanFeedbackScoresNames(String apiKey,
                Visibility visibility, int expectedCode) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, okApikey, workspaceName);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();
            spanResourceClient.createSpan(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI) + "/feedback-scores/names")
                    .queryParam("project_id", projectId)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, okApikey, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScore))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT, errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteFeedback__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, okApikey, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(spanId, feedbackScore, workspaceName, okApikey);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(DeleteFeedbackScore.builder().name(feedbackScore.name()).build()))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT, errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedbackBatch__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, okApikey, workspaceName);

            List<FeedbackScoreBatchItem> items = PodamFactoryUtils
                    .manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .projectName(span.projectName())
                            .id(spanId)
                            .build())
                    .collect(Collectors.toList());

            var feedbackScoreBatch = FeedbackScoreBatch.builder()
                    .scores(items)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScoreBatch))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT, errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void search__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility, int expectedCode) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, okApikey, workspaceName);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectId(null).projectName(project.name())
                    .build();

            spanResourceClient.createSpan(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/search")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(SpanSearchStreamRequest.builder().projectName(span.projectName()).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 200) {
                    var expectedSpans = spanResourceClient.getStreamedItems(actualResponse);
                    assertThat(expectedSpans).hasSize(1);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(span.projectName()));
                }
            }
        }

    }

    private void assertExpectedResponseWithoutBody(boolean expected, Response actualResponse, int expectedStatus,
            io.dropwizard.jersey.errors.ErrorMessage expectedErrorMessage) {
        if (expected) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            assertThat(actualResponse.hasEntity()).isFalse();
        } else {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
            assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                    .isEqualTo(expectedErrorMessage);
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
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(span))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_CREATED,
                        UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void update__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = spanResourceClient.createSpan(span, API_KEY, workspaceName);

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

                assertExpectedResponseWithoutBody(
                        expected, actualResponse, HttpStatus.SC_NO_CONTENT, UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void delete__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NOT_IMPLEMENTED,
                        UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var span = podamFactory.manufacturePojo(Span.class);

            var spanId = spanResourceClient.createSpan(span, API_KEY, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var expectedSpan = actualResponse.readEntity(Span.class);
                    assertThat(expectedSpan.id()).isEqualTo(spanId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, apiKey, workspaceName);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();
            spanResourceClient.createSpan(span, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", span.projectName())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 200) {
                    var expectedSpans = actualResponse.readEntity(Span.SpanPage.class);
                    assertThat(expectedSpans.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(project.name()));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenSessionTokenIsPresent__thenReturnSpanStats(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, apiKey, workspaceName);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();
            spanResourceClient.createSpan(span, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("stats")
                    .queryParam("project_name", span.projectName())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(project.name()));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void get__whenSessionTokenIsPresent__thenReturnFeedbackScoresNames(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();
            spanResourceClient.createSpan(span, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores/names")
                    .queryParam("project_id", projectId)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 404) {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NOT_FOUND_MESSAGE.formatted(projectId));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = spanResourceClient.createSpan(span, API_KEY, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScore))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT,
                        UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteFeedback__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean expected, String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = spanResourceClient.createSpan(span, API_KEY, workspaceName);

            var feedbackScore = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(spanId, feedbackScore, workspaceName, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(spanId.toString())
                    .path("feedback-scores")
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(DeleteFeedbackScore.builder().name(feedbackScore.name()).build()))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT,
                        UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void feedbackBatch__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var spanId = spanResourceClient.createSpan(span, API_KEY, workspaceName);

            List<FeedbackScoreBatchItem> items = PodamFactoryUtils
                    .manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(item -> item.toBuilder()
                            .projectName(span.projectName())
                            .id(spanId)
                            .build())
                    .collect(Collectors.toList());

            var feedbackScoreBatch = FeedbackScoreBatch.builder()
                    .scores(items)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScoreBatch))) {

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT,
                        UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        void search__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder().visibility(visibility).build();
            projectResourceClient.createProject(project, apiKey, workspaceName);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder().projectName(project.name()).build();
            spanResourceClient.createSpan(span, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/search")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(SpanSearchStreamRequest.builder().projectName(span.projectName()).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                if (expectedCode == 200) {
                    var expectedSpans = spanResourceClient.getStreamedItems(actualResponse);
                    assertThat(expectedSpans).hasSize(1);
                } else {
                    assertThat(actualResponse.readEntity(NotFoundException.class).getMessage())
                            .isEqualTo(PROJECT_NAME_NOT_FOUND_MESSAGE.formatted(span.projectName()));
                }
            }
        }
    }

    private void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateSpan {

        @Test
        void createAndGetById() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createSpanWithNonNumericNumbers() throws JsonProcessingException {
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var span = (ObjectNode) JsonUtils.readTree(expectedSpan);
            var input = JsonUtils.createObjectNode();
            input.put("value", Double.POSITIVE_INFINITY);
            span.replace("input", input);
            var customObjectMapper = new ObjectMapper()
                    .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
                    .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature());
            var spanStr = customObjectMapper.writeValueAsString(span);
            spanResourceClient.createSpan(spanStr, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            expectedSpan = expectedSpan.toBuilder()
                    .input(JsonUtils.getJsonNodeFromString("{\"value\": \"Infinity\"}"))
                    .feedbackScores(null)
                    .build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource
        void createAndGetCost(
                Map<String, Integer> usage, String model, String provider, JsonNode metadata, BigDecimal manualCost) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .model(model)
                    .provider(provider)
                    .metadata(metadata)
                    .usage(usage)
                    .totalEstimatedCost(manualCost)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var expectedCost = manualCost != null
                    ? manualCost
                    : CostService.calculateCost(
                            StringUtils.isNotBlank(model)
                                    ? model
                                    : Optional.ofNullable(metadata)
                                            .map(md -> md.get("model"))
                                            .map(JsonNode::asText).orElse(""),
                            provider,
                            usage,
                            metadata);
            if (MapUtils.isNotEmpty(usage) || isMetadataCost(metadata)) {
                assertThat(expectedCost.compareTo(BigDecimal.ZERO) > 0).isTrue();
            }

            // Update expected span to include provider in metadata (as the backend does on retrieval)
            JsonNode expectedMetadata = JsonUtils.prependField(
                    metadata,
                    Span.SpanField.PROVIDER.getValue(),
                    provider);
            var expectedSpanWithProviderInMetadata = expectedSpan.toBuilder()
                    .metadata(expectedMetadata)
                    .build();

            var span = getAndAssert(expectedSpanWithProviderInMetadata, API_KEY, TEST_WORKSPACE);
            assertThat(span.totalEstimatedCost())
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedCost.compareTo(BigDecimal.ZERO) == 0 ? null : expectedCost);
        }

        boolean isMetadataCost(JsonNode metadata) {
            return getCostFromMetadata(metadata).compareTo(BigDecimal.ZERO) > 0;
        }

        @Test
        void createAndGetSpan__providerInMetadata() {
            String provider = "anthropic";
            String model = "claude-3-5-sonnet-latest";

            // Create span with custom metadata
            JsonNode customMetadata = JsonUtils.getJsonNodeFromString(
                    "{\"custom_field\":\"custom_value\",\"another_field\":\"another_value\"}");

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .model(model)
                    .provider(provider)
                    .metadata(customMetadata)
                    .feedbackScores(null)
                    .build();

            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            // Retrieve span from the API
            var actualSpan = spanResourceClient.getById(expectedSpan.id(), TEST_WORKSPACE, API_KEY);

            // Verify provider appears in metadata
            assertThat(actualSpan.metadata()).isNotNull();
            assertThat(actualSpan.metadata().has("provider")).isTrue();
            assertThat(actualSpan.metadata().get("provider").asText()).isEqualTo(provider);

            // Verify custom metadata fields are still present after provider
            assertThat(actualSpan.metadata().has("custom_field")).isTrue();
            assertThat(actualSpan.metadata().get("custom_field").asText()).isEqualTo("custom_value");
            assertThat(actualSpan.metadata().has("another_field")).isTrue();
            assertThat(actualSpan.metadata().get("another_field").asText()).isEqualTo("another_value");

            // Verify top-level provider field still exists
            assertThat(actualSpan.provider()).isEqualTo(provider);
        }

        Stream<Arguments> createAndGetCost() {
            var metadata = JsonUtils
                    .getJsonNodeFromString(
                            "{\"created_from\":\"openai\",\"type\":\"openai_chat\",\"model\":\"gpt-3.5-turbo\"}");
            String metadataWithCost = """
                    {"cost": {
                        "total_tokens": %s,
                        "currency": "%s"
                      }}""";

            return Stream.of(
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gpt-3.5-turbo-1106", "openai",
                            JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("0.000339", "USD")), null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gemini-1.5-pro-preview-0514", "google_vertexai",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "claude-3-5-haiku-latest", "anthropic",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "claude-3-5-sonnet-v2@20241022", "anthropic_vertexai",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "us.anthropic.claude-3-5-sonnet-20241022-v2:0", "bedrock",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "us.anthropic.claude-sonnet-4-20250514-v1:0", "bedrock",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gpt-4o-mini-2024-07-18", "openai",
                            null, null),
                    Arguments.of(
                            Map.of("original_usage.prompt_tokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.prompt_tokens_details.cached_tokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.completion_tokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gpt-4o-mini-2024-07-18", "openai",
                            null, null),
                    Arguments.of(
                            Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "claude-3-5-sonnet-latest", "anthropic",
                            null, null),
                    Arguments.of(
                            Map.of("original_usage.input_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.output_tokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.cache_read_input_tokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.cache_creation_input_tokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "claude-3-5-sonnet-latest", "anthropic",
                            null, null),
                    Arguments.of(
                            Map.of("original_usage.inputTokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.outputTokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.cacheReadInputTokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.cacheWriteInputTokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "us.anthropic.claude-3-5-sonnet-20241022-v2:0", "bedrock",
                            null, null),
                    Arguments.of(
                            Map.of("original_usage.inputTokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.outputTokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.cacheReadInputTokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "original_usage.cacheWriteInputTokens",
                                    Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "us.anthropic.claude-sonnet-4-20250514-v1:0", "bedrock",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gpt-3.5-turbo-1106", "openai",
                            metadata,
                            null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            null, "openai",
                            metadata,
                            null),
                    Arguments.of(null, "gpt-3.5-turbo-1106", "openai", null, null),
                    Arguments.of(null, "unknown-model", "openai", null, null),
                    Arguments.of(null, null, null,
                            JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("0.000339", "USD")), null),
                    Arguments.of(null, null, null,
                            JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("0.000339", "Wrong currency")),
                            null),
                    Arguments.of(null, null, null,
                            JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("\"Invalid cost\"", "USD")),
                            null),
                    Arguments.of(null, null, null, null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gpt-3.5-turbo-1106", "openai",
                            null,
                            podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8, RoundingMode.DOWN)),
                    Arguments.of(null, null, null, null,
                            podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8, RoundingMode.DOWN)));
        }

        @Test
        void createSpanFaiWithNegativeManualCost() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .totalEstimatedCost(
                            podamFactory.manufacturePojo(BigDecimal.class).abs().negate().setScale(8,
                                    RoundingMode.DOWN))
                    .build();
            createAndAssertErrorMessage(expectedSpan, API_KEY, TEST_WORKSPACE, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "totalEstimatedCost must be greater than or equal to 0.0");
        }

        private void createAndAssertErrorMessage(Span span, String apiKey, String workspaceName, int status,
                String errorMessage) {
            try (var response = spanResourceClient.createSpan(span, apiKey, workspaceName, status)) {
                assertThat(response.hasEntity()).isTrue();
                assertThat(response.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors().getFirst())
                        .isEqualTo(errorMessage);
            }
        }

        @Test
        void createAndGet__whenSpanInputIsBig__thenReturnSpan() {
            int size = 1000;
            var jsonMap = IntStream.range(0, size)
                    .mapToObj(i -> Map.entry(RandomStringUtils.secure().nextAlphabetic(10),
                            RandomStringUtils.secure().nextAscii(size)))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(JsonUtils.readTree(jsonMap))
                    .output(JsonUtils.readTree(jsonMap))
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createOnlyRequiredFieldsAndGetById() {
            var expectedSpan = Span.builder()
                    .traceId(generator.generate())
                    .startTime(Instant.now())
                    .createdAt(Instant.now())
                    .build();
            var expectedSpanId = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            expectedSpan = expectedSpan.toBuilder().id(expectedSpanId).projectName(DEFAULT_PROJECT).build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createSpansWithSameIdIdempotent() {
            var span1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(span1, API_KEY, TEST_WORKSPACE);

            var span2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(span1.id())
                    .build();
            spanResourceClient.createSpan(span2, API_KEY, TEST_WORKSPACE);

            getAndAssert(span1, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createThrowsDeserializationError() {
            var span = podamFactory.manufacturePojo(Span.class);
            var spanStr = JsonUtils.writeValueAsString(span);
            // Make timestamps invalid
            var invalidSpanStr = spanStr.replaceAll("Z\"", "\"");
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(JsonUtils.getJsonNodeFromString(invalidSpanStr)))) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(actualResponse.hasEntity()).isTrue();
                var errorMessage = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(errorMessage.getMessage()).contains("Cannot deserialize value of type");
            }
        }

        @Test
        void createAfterUpdateAndGetById() {
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(spanUpdate.projectName())
                    .traceId(spanUpdate.traceId())
                    .parentSpanId(spanUpdate.parentSpanId())
                    .build();
            spanResourceClient.updateSpan(span.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = span.toBuilder().feedbackScores(null);
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createAfterUpdateThrowsProjectConflict() {
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .traceId(spanUpdate.traceId())
                    .parentSpanId(spanUpdate.parentSpanId())
                    .build();
            spanResourceClient.updateSpan(span.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            createAndAssertErrorMessage(
                    span, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CONFLICT, PROJECT_AND_WORKSPACE_NAME_MISMATCH);
        }

        @Test
        void createAfterUpdateThrowsParentSpanIdConflict() {
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(spanUpdate.projectName())
                    .traceId(spanUpdate.traceId())
                    .build();
            spanResourceClient.updateSpan(span.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            createAndAssertErrorMessage(span, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CONFLICT, PARENT_SPAN_IS_MISMATCH);
        }

        @Test
        void createAfterUpdateThrowsTraceIdConflict() {
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(spanUpdate.projectName())
                    .parentSpanId(spanUpdate.parentSpanId())
                    .build();
            spanResourceClient.updateSpan(span.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            createAndAssertErrorMessage(span, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CONFLICT, TRACE_ID_MISMATCH);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.QuotaLimitTestUtils#quotaLimitsTestProvider")
        void testQuotasLimit_whenLimitIsEmptyOrNotReached_thenAcceptCreation(
                List<Quota> quotas, boolean isLimitReached) {
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, workspaceName, workspaceId, USER, quotas);

            var span = podamFactory.manufacturePojo(Span.class);
            var expectedStatus = isLimitReached ? HttpStatus.SC_PAYMENT_REQUIRED : HttpStatus.SC_CREATED;

            try (var actualResponse = spanResourceClient.createSpan(span, API_KEY, workspaceName, expectedStatus)) {
                if (isLimitReached) {
                    var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                            HttpStatus.SC_PAYMENT_REQUIRED, ERR_USAGE_LIMIT_EXCEEDED);
                    var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(actualError).isEqualTo(expectedError);
                }
            }
        }

        @Test
        @DisplayName("when span contains base64 attachments, then attachments are stripped and stored")
        void create__whenSpanContainsBase64Attachments__thenAttachmentsAreStrippedAndStored() throws Exception {
            // Given a span with base64 encoded attachments in its input
            // Create longer base64 strings that exceed the 5000 character threshold using utility
            String base64Png = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Gif = AttachmentPayloadUtilsTest.createLargeGifBase64();

            String originalInputJson = String.format(
                    "{\"message\": \"Images attached:\", " +
                            "\"png_data\": \"image %s that was used for testing\", " +
                            "\"gif_data\": \"%s\", " +
                            "\"user_id\": \"user123\", " +
                            "\"session_id\": \"session456\", " +
                            "\"timestamp\": \"2024-01-15T10:30:00Z\", " +
                            "\"model_config\": {\"temperature\": 0.7, \"max_tokens\": 1000}, " +
                            "\"prompt\": \"Please analyze these images and provide a detailed description\", " +
                            "\"context\": [\"Previous conversation history\", \"User preferences\"], " +
                            "\"metadata\": {\"source\": \"web_app\", \"version\": \"1.2.3\"}}",
                    base64Png, base64Gif);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed\"}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .feedbackScores(null)
                    .build();

            // When creating the span
            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
            assertThat(spanId).isNotNull();

            // Then the span should have attachments stripped and replaced with references
            // Wait for async processing and attachment stripping
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                // Use strip_attachments=true to get attachment references instead of reinjected base64
                Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                assertThat(retrievedSpan).isNotNull();

                // Verify the base64 data is replaced by attachment references (with timestamps)
                JsonNode retrievedInput = retrievedSpan.input();
                assertThat(retrievedInput).isNotNull();
                String retrievedInputString = retrievedInput.toString();

                // References are wrapped in brackets and prefixed with the context (input)
                assertThat(retrievedInputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
                assertThat(retrievedInputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.gif\\]");
                assertThat(retrievedInputString).doesNotContain(base64Png);
                assertThat(retrievedInputString).doesNotContain(base64Gif);
            });

            // Note: Attachment verification would require proper API setup
            // For now, we just verify that the base64 data was stripped from the input
        }

        @Test
        @DisplayName("when span is fetched with different truncate and strip_attachments flags, then response varies accordingly")
        void getByList__whenFetchedWithDifferentFlags__thenResponseVariesAccordingly() throws Exception {
            // Given a span with a large text payload (20k chars) plus base64 encoded attachments at the end
            String base64Png = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Gif = AttachmentPayloadUtilsTest.createLargeGifBase64();

            // Create a 20k character text payload
            StringBuilder largeTextBuilder = new StringBuilder();
            String loremIpsum = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ";
            while (largeTextBuilder.length() < 20000) {
                largeTextBuilder.append(loremIpsum);
            }
            String largeText = largeTextBuilder.toString();

            // Create input JSON with large text + attachments at the end
            String originalInputJson = String.format(
                    "{\"message\": \"%s\", " +
                            "\"png_data\": \"image %s that was used for testing\", " +
                            "\"gif_data\": \"%s\"}",
                    largeText, base64Png, base64Gif);

            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree("{\"request\": \"process data\"}"))
                    .output(JsonUtils.readTree("{\"result\": \"done\"}"))
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .traceId(traceId)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed\"}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .build();

            // When creating the span
            var spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
            assertThat(spanId).isNotNull();

            // Wait for async attachment stripping - verify attachments are stored and replaced with references
            Awaitility.await()
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                        assertThat(retrievedSpan).isNotNull();

                        String inputString = retrievedSpan.input().toString();
                        // Ensure base64 data was stripped and replaced with references
                        assertThat(inputString).doesNotContain(base64Png);
                        assertThat(inputString).doesNotContain(base64Gif);
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
                    });

            // Verify exactly 2 attachments were created (no duplicates)
            var projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());

            var attachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.SPAN,
                    span.id(),
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);

            assertThat(attachmentPage).isNotNull();
            assertThat(attachmentPage.content()).hasSize(2); // Should have exactly 2, not duplicates

            // Test 1: truncate=true && strip_attachments=true -> checks if the input/output is much smaller than the text sent (character limit applies)
            // Note: When truncate=true, the truncation can cut off attachment references if they appear late in the JSON
            // For this test with 20k Lorem ipsum text followed by attachment fields, the references are beyond the 10KB truncation threshold
            Span.SpanPage truncatedPage = spanResourceClient.getByTraceIdAndProject(traceId, DEFAULT_PROJECT,
                    TEST_WORKSPACE, API_KEY, true, true);
            assertThat(truncatedPage).isNotNull();
            assertThat(truncatedPage.content()).isNotEmpty();
            Span truncatedSpan = truncatedPage.content().get(0);

            JsonNode truncatedInput = truncatedSpan.input();
            assertThat(truncatedInput).isNotNull();
            String truncatedInputString = truncatedInput.toString();

            // Verify text is truncated (much smaller than 20k chars)
            assertThat(truncatedInputString.length()).isLessThan(12000); // Should be truncated
            // Verify base64 data is not in the truncated response (either stripped or cut off by truncation)
            assertThat(truncatedInputString).doesNotContain(base64Png);
            assertThat(truncatedInputString).doesNotContain(base64Gif);
            // Note: We can't reliably test for attachment references here because truncation may cut them off

            // Test 2: truncate=false && strip_attachments=true -> checks if we have the full text, but stripped attachments
            Span.SpanPage strippedPage = spanResourceClient.getByTraceIdAndProject(traceId, DEFAULT_PROJECT,
                    TEST_WORKSPACE, API_KEY, false, true);
            assertThat(strippedPage).isNotNull();
            assertThat(strippedPage.content()).isNotEmpty();
            Span strippedSpan = strippedPage.content().get(0);

            JsonNode strippedInput = strippedSpan.input();
            assertThat(strippedInput).isNotNull();
            String strippedInputString = strippedInput.toString();

            // Verify full text (NOT truncated) AND attachments are still references
            assertThat(strippedInputString).contains(largeText); // Full text preserved
            assertThat(strippedInputString).containsPattern("\\[input-attachment-1-\\d+\\.png\\]");
            assertThat(strippedInputString).containsPattern("\\[input-attachment-2-\\d+\\.gif\\]");
            assertThat(strippedInputString).doesNotContain(base64Png);
            assertThat(strippedInputString).doesNotContain(base64Gif);

            // Test 3: truncate=false && strip_attachments=false -> verifies attachment reinjection
            // Wait for MinIO uploads to complete by checking attachment availability
            Awaitility.await()
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .atMost(10, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        Span.SpanPage fullPageCheck = spanResourceClient.getByTraceIdAndProject(traceId,
                                DEFAULT_PROJECT,
                                TEST_WORKSPACE, API_KEY, false, false);
                        assertThat(fullPageCheck).isNotNull();
                        assertThat(fullPageCheck.content()).isNotEmpty();
                        Span fullSpanCheck = fullPageCheck.content().get(0);

                        JsonNode fullInputCheck = fullSpanCheck.input();
                        assertThat(fullInputCheck).isNotNull();
                        String fullInputStringCheck = fullInputCheck.toString();

                        // Verify full text is preserved (NOT truncated)
                        assertThat(fullInputStringCheck).contains(largeText); // Full 20k+ char text preserved
                        assertThat(fullInputStringCheck.length()).isGreaterThan(20000); // Much larger than truncation threshold

                        // Verify the base64 data is reinjected (whitespace formatting may differ due to Jackson read/write)
                        assertThat(fullInputStringCheck).contains(base64Png);
                        assertThat(fullInputStringCheck).contains(base64Gif);

                        // Should not contain attachment references when reinjection succeeds
                        assertThat(fullInputStringCheck).doesNotContainPattern("\\[input-attachment-1-\\d+\\.png\\]");
                        assertThat(fullInputStringCheck).doesNotContainPattern("\\[input-attachment-2-\\d+\\.gif\\]");
                    });
        }
    }

    private Stream<Arguments> getProjectNameModifierArg() {
        return Stream.of(
                arguments(Function.identity()),
                arguments((Function<String, String>) String::toUpperCase));
    }

    @Nested
    @DisplayName("Batch:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchInsert {

        Stream<Arguments> batch__whenCreateSpans__thenReturnNoContent() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            return Stream.of(
                    arguments(projectName, projectName),
                    arguments(null, DEFAULT_PROJECT));
        }

        @ParameterizedTest
        @MethodSource
        void batch__whenCreateSpans__thenReturnNoContent(String projectName, String expectedProjectName) {
            // Use dedicated workspace to avoid collisions with other tests in the default project
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(API_KEY, workspaceName, workspaceId);

            var expectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spanResourceClient.batchCreateSpans(expectedSpans, API_KEY, workspaceName);

            getAndAssertPage(
                    workspaceName,
                    expectedProjectName.toUpperCase(),
                    List.of(),
                    List.of(),
                    expectedSpans.reversed(),
                    List.of(),
                    API_KEY,
                    List.of(),
                    List.of());
        }

        @ParameterizedTest
        @MethodSource
        void batch__whenSendingMultipleSpansWithSameId__dedupeSpans__thenReturnNoContent(
                Function<Span, Span> spanModifier) {
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(API_KEY, workspaceName, workspaceId);

            var id = generator.generate();
            String projectName = UUID.randomUUID().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .id(id)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .toList();

            var modifiedSpans = IntStream.range(0, spans.size())
                    .mapToObj(i -> i == spans.size() - 1
                            ? spanModifier.apply(spans.get(i)) // modify last item
                            : spans.get(i))
                    .toList();

            try (var actualResponse = spanResourceClient.callBatchCreateSpans(modifiedSpans, API_KEY, workspaceName)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode())
                        .isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    List.of(),
                    List.of(),
                    List.of(modifiedSpans.getLast()),
                    List.of(),
                    API_KEY,
                    List.of(),
                    List.of());
        }

        Stream<Arguments> batch__whenSendingMultipleSpansWithSameId__dedupeSpans__thenReturnNoContent() {
            return Stream.of(
                    arguments(
                            (Function<Span, Span>) s -> s),
                    arguments(
                            (Function<Span, Span>) span -> span.toBuilder()
                                    .lastUpdatedAt(null).build()));
        }

        @Test
        void batch__whenMissingFields__thenReturnNoContent() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var expectedSpans = IntStream.range(0, 5)
                    .mapToObj(i -> Span.builder()
                            .projectName(projectName)
                            .traceId(generator.generate())
                            .startTime(Instant.now())
                            .build())
                    .toList();
            spanResourceClient.batchCreateSpans(expectedSpans, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void batch__whenCreateSpansUsageWithNullValue__thenReturnNoContent() {

            String projectName = UUID.randomUUID().toString();

            Map<String, Integer> usage = new LinkedHashMap<>() {
                {
                    put("firstKey", 10);
                }
            };

            var expectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .usage(usage)
                            .projectName(projectName)
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            var spanBatch = new SpanBatch(expectedSpans);

            JsonNode body = JsonUtils.readTree(spanBatch);

            body.get("spans").forEach(span -> {
                var usageNode = span.get("usage");

                if (usageNode instanceof ObjectNode usageObject) {
                    usageObject.set("secondKey", NullNode.getInstance());
                }
            });

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(body))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), List.of(), expectedSpans.reversed(), List.of(),
                    API_KEY, List.of(), List.of());
        }

        @Test
        void upsert() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            // Ingesting spans with the minimum required fields
            var expectedSpans0 = IntStream.range(0, 5)
                    .mapToObj(i -> Span.builder()
                            .projectName(projectName)
                            .id(generator.generate())
                            .traceId(generator.generate())
                            .startTime(Instant.now())
                            .createdAt(Instant.now())
                            .build())
                    .toList();
            spanResourceClient.batchCreateSpans(expectedSpans0, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    List.of(),
                    List.of(),
                    expectedSpans0.reversed(),
                    List.of(),
                    API_KEY,
                    List.of(),
                    List.of());

            // The spans are overwritten, by using a server side generated last_updated_at
            var expectedSpans1 = IntStream.range(0, 5)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .id(expectedSpans0.get(i).id())
                            .traceId(expectedSpans0.get(i).traceId())
                            .parentSpanId(expectedSpans0.get(i).parentSpanId())
                            .name("name-01-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .startTime(expectedSpans0.get(i).startTime())
                            .lastUpdatedAt(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spanResourceClient.batchCreateSpans(expectedSpans1, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    List.of(),
                    List.of(),
                    expectedSpans1.reversed(), // Finds the updated
                    expectedSpans0, // Does not find the previous
                    API_KEY,
                    List.of(),
                    List.of());

            // The spans are overwritten, by using a client side generated last_updated_at
            var expectedSpans2 = IntStream.range(0, 5)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .id(expectedSpans0.get(i).id())
                            .traceId(expectedSpans0.get(i).traceId())
                            .parentSpanId(expectedSpans0.get(i).parentSpanId())
                            .name("name-02-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .startTime(expectedSpans0.get(i).startTime())
                            .lastUpdatedAt(Instant.now().plus(1, ChronoUnit.DAYS))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spanResourceClient.batchCreateSpans(expectedSpans2, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    List.of(),
                    List.of(),
                    expectedSpans2.reversed(), // Finds the updated
                    expectedSpans1, // Does not find the previous
                    API_KEY,
                    List.of(),
                    List.of());

            // The span is not overwritten, the client side last_updated_at is older
            var unexpectedSpans = IntStream.range(0, 5)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .id(expectedSpans0.get(i).id())
                            .traceId(expectedSpans0.get(i).traceId())
                            .parentSpanId(expectedSpans0.get(i).parentSpanId())
                            .name("name-03-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .startTime(expectedSpans0.get(i).startTime())
                            .lastUpdatedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            spanResourceClient.batchCreateSpans(unexpectedSpans, API_KEY, TEST_WORKSPACE);
            getAndAssertPage(
                    TEST_WORKSPACE,
                    projectName,
                    List.of(),
                    List.of(),
                    expectedSpans2.reversed(), // finds the previous
                    unexpectedSpans, // Does not find the update attempt
                    API_KEY,
                    List.of(),
                    List.of());
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.QuotaLimitTestUtils#quotaLimitsTestProvider")
        void testQuotasLimit_whenLimitIsEmptyOrNotReached_thenAcceptCreation(
                List<Quota> quotas, boolean isLimitReached) {
            var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var workspaceId = UUID.randomUUID().toString();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, workspaceName, workspaceId, USER, quotas);

            var span = podamFactory.manufacturePojo(Span.class);
            try (var actualResponse = spanResourceClient.callBatchCreateSpans(List.of(span), API_KEY, workspaceName)) {

                if (isLimitReached) {
                    assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_PAYMENT_REQUIRED);
                    var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_PAYMENT_REQUIRED,
                            ERR_USAGE_LIMIT_EXCEEDED);
                    var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(actualError).isEqualTo(expectedError);
                } else {
                    assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                }
            }
        }
    }

    private Span getAndAssert(Span expectedSpan, String apiKey, String workspaceName) {
        return getAndAssert(expectedSpan, null, apiKey, workspaceName);
    }

    private Span getAndAssert(Span expectedSpan, UUID expectedProjectId, String apiKey, String workspaceName) {
        var actualSpan = spanResourceClient.getById(expectedSpan.id(), workspaceName, apiKey);
        if (expectedProjectId == null) {
            assertThat(actualSpan.projectId()).isNotNull();
        } else {
            assertThat(actualSpan.projectId()).isEqualTo(expectedProjectId);
        }
        SpanAssertions.assertSpan(List.of(actualSpan), List.of(expectedSpan), USER);
        return actualSpan;
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteSpan {

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
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetSpan {

        @Test
        void getNotFound() {
            UUID id = generator.generate();

            var expectedError = "Span id: %s not found".formatted(id);
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

                assertThat(actualError.getMessage()).isEqualTo(expectedError);
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
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class);
            var spanUpdate = expectedSpanUpdate.toBuilder()
                    .projectId(null)
                    .projectName(expectedSpan.projectName())
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null);
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        Stream<SpanUpdate> update__whenFieldIsNotNull__thenAcceptUpdate() {
            return Stream.of(
                    SpanUpdate.builder().name("name-" + RandomStringUtils.secure().nextAlphanumeric(32)).build(),
                    SpanUpdate.builder().type(RandomTestUtils.randomEnumValue(SpanType.class)).build(),
                    SpanUpdate.builder().endTime(Instant.now()).build(),
                    SpanUpdate.builder().input(podamFactory.manufacturePojo(JsonNode.class)).build(),
                    SpanUpdate.builder().output(podamFactory.manufacturePojo(JsonNode.class)).build(),
                    SpanUpdate.builder().metadata(podamFactory.manufacturePojo(JsonNode.class)).build(),
                    SpanUpdate.builder().model("model-" + RandomStringUtils.secure().nextAlphanumeric(32)).build(),
                    SpanUpdate.builder().provider("provider-" + RandomStringUtils.secure().nextAlphanumeric(32))
                            .build(),
                    SpanUpdate.builder().tags(PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class)).build(),
                    SpanUpdate.builder()
                            .usage(PodamFactoryUtils.manufacturePojoMap(podamFactory, String.class, Integer.class))
                            .build(),
                    SpanUpdate.builder()
                            .totalEstimatedCost(BigDecimal.valueOf(PodamUtils.getDoubleInRange(0, 1_000_000))).build(),
                    SpanUpdate.builder().errorInfo(podamFactory.manufacturePojo(ErrorInfo.class)).build());
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when only some field is not null, then accept update")
        void update__whenFieldIsNotNull__thenAcceptUpdate(SpanUpdate expectedSpanUpdate) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(null)
                    .parentSpanId(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = expectedSpanUpdate.toBuilder()
                    .parentSpanId(expectedSpan.parentSpanId())
                    .traceId(expectedSpan.traceId())
                    .projectName(expectedSpan.projectName())
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder()
                    .projectName(DEFAULT_PROJECT);
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, expectedSpanUpdate);
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("update cost related items")
        void update__whenCostIsChanged__thenAcceptUpdate(SpanUpdate expectedSpanUpdate, BigDecimal initialManualCost) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(null)
                    .parentSpanId(null)
                    .model("gpt-4")
                    .provider("openai")
                    .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                    .totalEstimatedCost(initialManualCost)
                    .feedbackScores(null)
                    .build();

            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            SpanUpdate spanUpdate = expectedSpanUpdate.toBuilder()
                    .parentSpanId(expectedSpan.parentSpanId())
                    .traceId(expectedSpan.traceId())
                    .projectName(expectedSpan.projectName())
                    .build();

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder()
                    .projectName(DEFAULT_PROJECT);
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, expectedSpanUpdate);
            var actualSpan = getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);

            BigDecimal expectedCost;
            if (expectedSpanUpdate.totalEstimatedCost() != null) {
                expectedCost = expectedSpanUpdate.totalEstimatedCost();
            } else if (initialManualCost != null) {
                expectedCost = initialManualCost;
            } else {
                if (expectedSpanUpdate.model() != null || expectedSpanUpdate.provider() != null
                        || expectedSpanUpdate.usage() != null) {
                    expectedCost = CostService.calculateCost(
                            expectedSpanUpdate.model() != null ? expectedSpanUpdate.model() : expectedSpan.model(),
                            expectedSpanUpdate.provider() != null
                                    ? expectedSpanUpdate.provider()
                                    : expectedSpan.provider(),
                            expectedSpanUpdate.usage() != null ? expectedSpanUpdate.usage() : expectedSpan.usage(),
                            expectedSpanUpdate.metadata());
                } else {
                    expectedCost = CostService.calculateCost(
                            null, null, null,
                            expectedSpanUpdate.metadata());
                }
            }

            assertThat(actualSpan.totalEstimatedCost())
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedCost);
        }

        Stream<Arguments> update__whenCostIsChanged__thenAcceptUpdate() {
            String metadataWithCost = """
                    {"cost": {
                        "total_tokens": %s,
                        "currency": "%s"
                      }}""";

            return Stream.of(
                    arguments(SpanUpdate.builder().model("gpt-4o-2024-05-13").totalEstimatedCost(null).build(), null),
                    arguments(SpanUpdate.builder().model("gemini-1.5-pro-002").provider("google_ai")
                            .totalEstimatedCost(null).build(), null),
                    arguments(SpanUpdate.builder()
                            .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                            .totalEstimatedCost(null)
                            .build(),
                            null),
                    arguments(SpanUpdate.builder().model("gpt-4o-2024-05-13")
                            .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                            .metadata(JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("0.000339", "USD")))
                            .totalEstimatedCost(null)
                            .build(),
                            null),
                    arguments(
                            SpanUpdate.builder()
                                    .totalEstimatedCost(podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8,
                                            RoundingMode.DOWN))
                                    .build(),
                            null),
                    arguments(SpanUpdate.builder().model("gpt-4o-2024-05-13")
                            .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                            .totalEstimatedCost(null)
                            .build(),
                            podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8, RoundingMode.DOWN)),
                    arguments(
                            SpanUpdate.builder()
                                    .totalEstimatedCost(podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8,
                                            RoundingMode.DOWN))
                                    .build(),
                            podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8, RoundingMode.DOWN)),
                    arguments(SpanUpdate.builder()
                            .totalEstimatedCost(null)
                            .metadata(JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("0.000339", "USD")))
                            .build(),
                            null),
                    arguments(SpanUpdate.builder()
                            .totalEstimatedCost(null)
                            .metadata(JsonUtils.getJsonNodeFromString(metadataWithCost.formatted("0.000339", "USD")))
                            .build(),
                            podamFactory.manufacturePojo(BigDecimal.class).abs().setScale(8, RoundingMode.DOWN)));
        }

        @Test
        void updateWhenSpanDoesNotExistButSpanIdIsInvalid__thenRejectUpdate() {
            var id = UUID.randomUUID();
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class);
            try (var actualResponse = spanResourceClient.updateSpan(
                    id, expectedSpanUpdate, API_KEY, TEST_WORKSPACE, HttpStatus.SC_BAD_REQUEST)) {
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                        .contains("Span id must be a version 7 UUID");
            }
        }

        @Test
        void updateWhenSpanDoesNotExist__thenAcceptUpdate() {
            var id = generator.generate();
            var expectedSpanBuilder = Span.builder().id(id).startTime(Instant.EPOCH).createdAt(Instant.now());
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            spanResourceClient.updateSpan(id, expectedSpanUpdate, API_KEY, TEST_WORKSPACE);

            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, expectedSpanUpdate);
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when span does not exist, then return create it")
        void when__spanDoesNotExist__thenReturnCreateIt() {
            var id = generator.generate();
            var expectedSpanBuilder = Span.builder().id(id).startTime(Instant.EPOCH).createdAt(Instant.now());
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .name(null)
                    .type(null)
                    .build();

            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            var projectId = projectResourceClient.getByName(spanUpdate.projectName(), API_KEY, TEST_WORKSPACE).id();
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            var expectedSpan = expectedSpanBuilder
                    .name(null)
                    .type(null)
                    .build();
            getAndAssert(expectedSpan, projectId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when span update and insert are processed out of other, then return span")
        void when__spanUpdateAndInsertAreProcessedOutOfOther__thenReturnSpan() {
            var id = generator.generate();
            var createdAt = Instant.now();
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            var newSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(spanUpdate.projectName())
                    .traceId(spanUpdate.traceId())
                    .parentSpanId(spanUpdate.parentSpanId())
                    .id(id)
                    .createdAt(createdAt)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(newSpan, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = newSpan.toBuilder()
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            newSpan.startTime(), spanUpdate.endTime()));
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            getAndAssert(expectedSpanBuilder.build(), null, API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when span update and insert conflict, then return 409")
        void when__spanUpdateAndInsertConflict__thenReturn409(
                BiFunction<SpanUpdate, UUID, Span> mapper, String errorMessage) {
            var id = generator.generate();
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            var newSpan = mapper.apply(spanUpdate, id);
            try (var actualResponse = spanResourceClient.createSpan(
                    newSpan, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CONFLICT)) {
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
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
        void when__multipleSpanUpdateConflict__thenReturn409(
                BiFunction<SpanUpdate, UUID, SpanUpdate> mapper, String errorMessage) {
            var id = generator.generate();
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            var newSpan = mapper.apply(spanUpdate, id);
            try (var actualResponse = spanResourceClient.updateSpan(
                    id, newSpan, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CONFLICT)) {
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
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
                    .metadata(podamFactory.manufacturePojo(JsonNode.class))
                    .projectName(projectName)
                    .traceId(generator.generate())
                    .parentSpanId(null)
                    .build();
            var spanUpdate2 = SpanUpdate.builder()
                    .input(podamFactory.manufacturePojo(JsonNode.class))
                    .tags(PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class))
                    .projectName(projectName)
                    .traceId(spanUpdate1.traceId())
                    .parentSpanId(spanUpdate1.parentSpanId())
                    .build();
            var spanUpdate3 = SpanUpdate.builder()
                    .output(podamFactory.manufacturePojo(JsonNode.class))
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
                    .feedbackScores(null)
                    .build();
            var update1 = Mono
                    .fromRunnable(() -> spanResourceClient.updateSpan(id, spanUpdate3, API_KEY, TEST_WORKSPACE));
            var create = Mono.fromRunnable(() -> spanResourceClient.createSpan(newSpan, API_KEY, TEST_WORKSPACE));
            var update2 = Mono
                    .fromRunnable(() -> spanResourceClient.updateSpan(id, spanUpdate2, API_KEY, TEST_WORKSPACE));
            var update3 = Mono
                    .fromRunnable(() -> spanResourceClient.updateSpan(id, spanUpdate1, API_KEY, TEST_WORKSPACE));
            Flux.merge(update1, update2, update3, create).blockLast();

            var expectedSpan = newSpan.toBuilder()
                    .endTime(spanUpdate3.endTime())
                    .input(spanUpdate2.input())
                    .output(spanUpdate3.output())
                    .metadata(spanUpdate1.metadata())
                    .tags(spanUpdate2.tags())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            newSpan.startTime(), spanUpdate3.endTime()))
                    .build();
            getAndAssert(expectedSpan, null, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when update arrives before create with different parent_span_id, then stats count matches actual spans")
        void when__updateArrivesBeforeCreate__thenStatsInputCountMatchesActualSpans() {
            var projectName = UUID.randomUUID().toString();
            var traceId = generator.generate();
            var parentSpanId = generator.generate();

            var spanWithInput = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(traceId)
                    .parentSpanId(parentSpanId)
                    .feedbackScores(null)
                    .build();

            var earlyUpdate = SpanUpdate.builder()
                    .output(podamFactory.manufacturePojo(JsonNode.class))
                    .endTime(Instant.now())
                    .projectName(projectName)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .build();
            spanResourceClient.updateSpan(spanWithInput.id(), earlyUpdate, API_KEY, TEST_WORKSPACE);

            spanResourceClient.createSpan(spanWithInput, API_KEY, TEST_WORKSPACE);

            var spanWithoutInput = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(traceId)
                    .parentSpanId(parentSpanId)
                    .input(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(spanWithoutInput, API_KEY, TEST_WORKSPACE);

            var stats = spanResourceClient.getSpansStats(projectName, null, List.of(), API_KEY, TEST_WORKSPACE,
                    Map.of());

            var inputStat = stats.stats().stream()
                    .filter(s -> "input".equals(s.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(inputStat.getValue()).isEqualTo(1L);
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
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .name(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .name(name)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var actualSpan = getAndAssert(
                    expectedSpan.toBuilder().name(expectedName).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.name()).isEqualTo(expectedName);
        }

        Stream<SpanType> updateOnlyType() {
            return Stream.of(RandomTestUtils.randomEnumValue(SpanType.class), null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyType(SpanType type) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .type(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .type(type)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var actualSpan = getAndAssert(
                    expectedSpan.toBuilder().type(type).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.type()).isEqualTo(type);
        }

        Stream<Instant> updateOnlyEndTime() {
            return Stream.of(Instant.now(), null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyEndTime(Instant endTime) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .endTime(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .endTime(endTime)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var updatedSpan = expectedSpan.toBuilder()
                    .endTime(endTime)
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(expectedSpan.startTime(), endTime))
                    .build();
            var actualSpan = getAndAssert(updatedSpan, null, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.endTime()).isEqualTo(spanUpdate.endTime());
        }

        Stream<Arguments> updateOnlyTags() {
            var tags = PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class);
            return Stream.of(
                    arguments(tags, tags),
                    arguments(Set.of(), null),
                    arguments(null, null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyTags(Set<String> tags, Set<String> expectedTags) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .tags(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .tags(tags)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var updatedSpan = expectedSpan.toBuilder()
                    .tags(expectedTags)
                    .build();
            var actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.tags()).isEqualTo(expectedTags);
        }

        Stream<JsonNode> updateOnlyMetadata() {
            return Stream.of(
                    podamFactory.manufacturePojo(JsonNode.class),
                    JsonUtils.getJsonNodeFromString("{}"),
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyMetadata(JsonNode metadata) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .metadata(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .metadata(metadata)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);
            var updatedSpan = expectedSpan.toBuilder().metadata(metadata).build();
            var actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            // Prepare expected metadata with provider injected (if span has provider)
            var expectedMetadata = JsonUtils.prependField(
                    metadata, Span.SpanField.PROVIDER.getValue(), expectedSpan.provider());
            assertThat(actualSpan.metadata()).isEqualTo(expectedMetadata);
        }

        Stream<Arguments> updateOnlyModel() {
            var model = RandomStringUtils.secure().nextAlphanumeric(32);
            return Stream.of(
                    arguments(model, model),
                    arguments(null, null),
                    arguments("", null),
                    arguments("   ", null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyModel(String model, String expectedModel) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .model(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .model(model)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var actualSpan = getAndAssert(
                    expectedSpan.toBuilder().model(expectedModel).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.model()).isEqualTo(expectedModel);
        }

        Stream<Arguments> updateOnlyProvider() {
            var provider = RandomStringUtils.secure().nextAlphanumeric(32);
            return Stream.of(
                    arguments(provider, provider),
                    arguments(null, null),
                    arguments("", null),
                    arguments("   ", null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyProvider(String provider, String expectedProvider) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .provider(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .provider(provider)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var actualSpan = getAndAssert(
                    expectedSpan.toBuilder().provider(expectedProvider).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.provider()).isEqualTo(expectedProvider);
        }

        Stream<JsonNode> updateOnlyInput() {
            return Stream.of(
                    podamFactory.manufacturePojo(JsonNode.class),
                    JsonUtils.getJsonNodeFromString("{}"),
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyInput(JsonNode input) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .input(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .input(input)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var updatedSpan = expectedSpan.toBuilder().input(input).build();
            var actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.input()).isEqualTo(input);
        }

        Stream<JsonNode> updateOnlyOutput() {
            return Stream.of(
                    podamFactory.manufacturePojo(JsonNode.class),
                    JsonUtils.getJsonNodeFromString("{}"),
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyOutput(JsonNode output) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .output(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .output(output)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);
            var updatedSpan = expectedSpan.toBuilder().output(output).build();
            var actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.output()).isEqualTo(output);
        }

        Stream<Arguments> updateOnlyUsage() {
            var usage = PodamFactoryUtils.manufacturePojoMap(podamFactory, String.class, Integer.class);
            return Stream.of(
                    arguments(usage, usage),
                    arguments(Map.of(), null),
                    arguments(null, null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyUsage(Map<String, Integer> usage, Map<String, Integer> expectedUsage) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .usage(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .usage(usage)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var updatedSpan = expectedSpan.toBuilder()
                    .usage(expectedUsage)
                    .build();
            var actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.usage()).isEqualTo(expectedUsage);
        }

        Stream<Arguments> updateOnlyTotalEstimatedCost() {
            var totalEstimatedCost = BigDecimal.valueOf(PodamUtils.getDoubleInRange(0, 1_000_000));
            return Stream.of(
                    arguments(totalEstimatedCost, totalEstimatedCost),
                    arguments(BigDecimal.ZERO, null),
                    arguments(null, null));
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyTotalEstimatedCost(BigDecimal totalEstimatedCost, BigDecimal expectedTotalEstimatedCost) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .totalEstimatedCost(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .totalEstimatedCost(totalEstimatedCost)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var updatedSpan = expectedSpan.toBuilder()
                    .totalEstimatedCost(expectedTotalEstimatedCost)
                    .build();
            var actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
            if (expectedTotalEstimatedCost != null) {
                assertThat(actualSpan.totalEstimatedCost()).isEqualByComparingTo(expectedTotalEstimatedCost);
            } else {
                assertThat(actualSpan.totalEstimatedCost()).isNull();
            }
        }

        Stream<ErrorInfo> updateOnlyErrorInfo() {
            return Stream.of(podamFactory.manufacturePojo(ErrorInfo.class), null);
        }

        @ParameterizedTest
        @MethodSource
        void updateOnlyErrorInfo(ErrorInfo errorInfo) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .errorInfo(null)
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectName(expectedSpan.projectName())
                    .errorInfo(errorInfo)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var actualSpan = getAndAssert(
                    expectedSpan.toBuilder().errorInfo(errorInfo).build(), null, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.errorInfo()).isEqualTo(spanUpdate.errorInfo());
        }

        @Test
        @DisplayName("when updating using projectId, then accept update")
        void update__whenUpdatingUsingProjectId__thenAcceptUpdate() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .feedbackScores(null)
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var projectId = projectResourceClient.getByName(expectedSpan.projectName(), API_KEY, TEST_WORKSPACE).id();
            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .projectId(projectId)
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder();
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            expectedSpan = (expectedSpanBuilder.projectName(expectedSpan.projectName()).build());
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when updating span with different attachments, then old attachments are deleted and new ones are stored")
        void update__whenUpdatingSpanWithDifferentAttachments__thenOldAttachmentsAreDeletedAndNewOnesAreStored()
                throws Exception {
            // Step 1: Create a span with 3 JPG attachments
            String base64Jpg1 = AttachmentPayloadUtilsTest.createLargeJpegBase64();
            String base64Jpg2 = AttachmentPayloadUtilsTest.createLargeJpegBase64();
            String base64Jpg3 = AttachmentPayloadUtilsTest.createLargeJpegBase64();

            String originalInputJson = String.format(
                    "{\"message\": \"Original images:\", " +
                            "\"jpg1_data\": \"%s\", " +
                            "\"jpg2_data\": \"heres my image %s\", " +
                            "\"jpg3_data\": \"%s this is a payload\", " +
                            "\"analysis\": \"Please analyze these images\"}",
                    base64Jpg1, base64Jpg2, base64Jpg3);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed\"}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .build();

            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
            assertThat(spanId).isNotNull();

            // Step 2: Wait for async processing and attachment stripping
            AtomicReference<UUID> traceIdRef = new AtomicReference<>();
            AtomicReference<UUID> parentSpanIdRef = new AtomicReference<>();
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                assertThat(retrievedSpan).isNotNull();
                // Ensure the span is fully processed
                String inputString = retrievedSpan.input().toString();
                assertThat(inputString).doesNotContain(base64Jpg1);
                assertThat(inputString).doesNotContain(base64Jpg2);
                assertThat(inputString).doesNotContain(base64Jpg3);

                traceIdRef.set(retrievedSpan.traceId());
                parentSpanIdRef.set(retrievedSpan.parentSpanId());
            });
            UUID traceId = traceIdRef.get();
            UUID parentSpanId = parentSpanIdRef.get();

            // Verify we have 3 JPG attachments initially
            UUID projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());
            var initialAttachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.SPAN,
                    spanId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);
            assertThat(initialAttachmentPage.content()).hasSize(3); // Verify exactly 3 JPG attachments

            // Step 3: Update the span with 2 PNG attachments (different type and count)
            String base64Png1 = AttachmentPayloadUtilsTest.createLargePngBase64();
            String base64Png2 = AttachmentPayloadUtilsTest.createLargePngBase64();

            String updatedInputJson = String.format(
                    "{\"message\": \"Updated images:\", " +
                            "\"png1_data\": \"%s\", " +
                            "\"png2_data\": \"%s my data\", " +
                            "\"analysis\": \"Please analyze these new images\"}",
                    base64Png1, base64Png2);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(traceId)
                    .parentSpanId(parentSpanId)
                    .input(JsonUtils.readTree(updatedInputJson))
                    .build();

            spanResourceClient.updateSpan(spanId, spanUpdate, API_KEY, TEST_WORKSPACE);

            // Step 4: Wait for async processing and attachment stripping for the update
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                // Verify the updated span exists and was updated
                Span finalSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                assertThat(finalSpan).isNotNull();

                String updatedInputString = finalSpan.input().toString();
                assertThat(updatedInputString).contains("Updated images:");

                // Verify neither original JPG base64 data is not present new PNG base64 data is not present (should be replaced by references)
                assertThat(updatedInputString).doesNotContain(base64Jpg1);
                assertThat(updatedInputString).doesNotContain(base64Jpg2);
                assertThat(updatedInputString).doesNotContain(base64Jpg3);
                assertThat(updatedInputString).doesNotContain(base64Png1);
                assertThat(updatedInputString).doesNotContain(base64Png2);

                // Verify PNG attachment references are present (with timestamps)
                assertThat(updatedInputString).containsPattern("input-attachment-1-\\d+\\.png");
                assertThat(updatedInputString).containsPattern("input-attachment-2-\\d+\\.png");
            });

            // Step 5: Verify we now have 2 PNG attachments (old JPGs should be deleted)
            var finalAttachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.SPAN,
                    spanId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);
            assertThat(finalAttachmentPage.content()).hasSize(2); // Verify exactly 2 PNG attachments
        }

        @Test
        @DisplayName("update: when updating span with auto-stripped and user-uploaded attachments, then only auto-stripped attachments are replaced")
        void update__whenUpdatingSpanWithAutoStrippedAndUserUploadedAttachments__thenOnlyAutoStrippedAttachmentsAreReplaced()
                throws Exception {
            // Step 1: Create a span with 2 auto-stripped attachments (base64 in payload)
            String base64Jpg1 = AttachmentPayloadUtilsTest.createLargeJpegBase64();
            String base64Jpg2 = AttachmentPayloadUtilsTest.createLargeJpegBase64();

            String originalInputJson = String.format(
                    "{\"message\": \"Original images:\", " +
                            "\"jpg1_data\": \"%s\", " +
                            "\"jpg2_data\": \"%s\"}",
                    base64Jpg1, base64Jpg2);

            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{\"result\": \"processed\"}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .build();

            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
            assertThat(spanId).isNotNull();

            // Step 2: Wait for async processing and attachment stripping
            AtomicReference<UUID> traceIdRef = new AtomicReference<>();
            AtomicReference<UUID> parentSpanIdRef = new AtomicReference<>();
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                assertThat(retrievedSpan).isNotNull();
                String inputString = retrievedSpan.input().toString();
                assertThat(inputString).doesNotContain(base64Jpg1);
                assertThat(inputString).doesNotContain(base64Jpg2);

                traceIdRef.set(retrievedSpan.traceId());
                parentSpanIdRef.set(retrievedSpan.parentSpanId());
            });
            UUID traceId = traceIdRef.get();
            UUID parentSpanId = parentSpanIdRef.get();

            // Verify we have 2 auto-stripped JPG attachments initially
            UUID projectId = projectResourceClient.getByName(DEFAULT_PROJECT, API_KEY, TEST_WORKSPACE).id();
            String baseUrl = Base64.getUrlEncoder().encodeToString(baseURI.getBytes());
            var initialAttachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.SPAN,
                    spanId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);
            assertThat(initialAttachmentPage.content()).hasSize(2); // 2 auto-stripped JPG attachments

            // Step 3: Manually upload a user attachment (simulating SDK behavior)
            String userFileName = "user-uploaded-doc.pdf";
            byte[] userFileData = "This is a PDF document uploaded by the user via SDK".getBytes();
            var userAttachmentInfo = new AttachmentInfo(
                    userFileName,
                    DEFAULT_PROJECT,
                    EntityType.SPAN,
                    spanId,
                    null, // containerId
                    "application/pdf");

            attachmentResourceClient.uploadAttachment(userAttachmentInfo, userFileData, API_KEY, TEST_WORKSPACE, 204);

            // Verify we now have 3 attachments total (2 auto-stripped + 1 user-uploaded)
            var afterUserUploadPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.SPAN,
                    spanId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);
            assertThat(afterUserUploadPage.content()).hasSize(3);

            // Step 4: Update the span with new base64 data (which will create new auto-stripped attachments)
            String base64Png1 = AttachmentPayloadUtilsTest.createLargePngBase64();

            String updatedInputJson = String.format(
                    "{\"message\": \"Updated with PNG:\", " +
                            "\"plain_png_data\": \"%s\", " +
                            "\"png_data\": \"image %s that was used for testing\"}",
                    base64Png1, base64Png1);

            var spanUpdate = SpanUpdate.builder()
                    .traceId(traceId)
                    .parentSpanId(parentSpanId)
                    .input(JsonUtils.readTree(updatedInputJson))
                    .build();

            spanResourceClient.updateSpan(spanId, spanUpdate, API_KEY, TEST_WORKSPACE);

            // Step 5: Wait for async processing of the update
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                Span finalSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                assertThat(finalSpan).isNotNull();

                String updatedInputString = finalSpan.input().toString();
                assertThat(updatedInputString).contains("Updated with PNG:");

                // Verify old JPG base64 and new PNG base64 are not present (replaced by references)
                assertThat(updatedInputString).doesNotContain(base64Jpg1);
                assertThat(updatedInputString).doesNotContain(base64Jpg2);
                assertThat(updatedInputString).doesNotContain(base64Png1);

                // Verify PNG attachments references is present
                assertThat(updatedInputString).containsPattern("input-attachment-1-\\d+\\.png");
                assertThat(updatedInputString).containsPattern("input-attachment-2-\\d+\\.png");
            });

            // Step 6: Verify attachments - should have 1 new PNG + 1 user-uploaded PDF
            // Old auto-stripped JPGs should be deleted, but user-uploaded PDF should remain
            var finalAttachmentPage = attachmentResourceClient.attachmentList(
                    projectId,
                    EntityType.SPAN,
                    spanId,
                    baseUrl,
                    API_KEY,
                    TEST_WORKSPACE,
                    200);

            assertThat(finalAttachmentPage.content()).hasSize(3); // 2 PNG + 1 user PDF

            // Verify the user-uploaded PDF is still there
            boolean userPdfExists = finalAttachmentPage.content().stream()
                    .anyMatch(att -> att.fileName().equals(userFileName));
            assertThat(userPdfExists).isTrue();

            // Verify we have a PNG attachment (auto-stripped from update)
            boolean pngExists = finalAttachmentPage.content().stream()
                    .anyMatch(att -> att.fileName().endsWith(".png"));
            assertThat(pngExists).isTrue();

            // Verify old JPG attachments are gone
            boolean jpgExists = finalAttachmentPage.content().stream()
                    .anyMatch(att -> att.fileName().endsWith(".jpg"));
            assertThat(jpgExists).isFalse();
        }
    }

    @Nested
    @DisplayName("Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanFeedback {

        Stream<Arguments> invalidRequestBodyParams() {
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
            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

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
            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .categoryName(null)
                    .reason(null)
                    .build();

            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            expectedSpan = expectedSpan.toBuilder().feedbackScores(List.of(score)).build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            expectedSpan = expectedSpan.toBuilder().feedbackScores(List.of(score)).build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when overriding feedback value, then return no content")
        void feedback__whenOverridingFeedbackValue__thenReturnNoContent() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScore.class);

            createAndAssert(id, score, TEST_WORKSPACE, API_KEY);

            var newScore = score.toBuilder().value(BigDecimal.valueOf(2)).build();
            createAndAssert(id, newScore, TEST_WORKSPACE, API_KEY);

            expectedSpan = expectedSpan.toBuilder().feedbackScores(List.of(newScore)).build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
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
            var deleteFeedbackScore = podamFactory.manufacturePojo(DeleteFeedbackScore.class);
            spanResourceClient.deleteSpanFeedbackScore(deleteFeedbackScore, id, API_KEY, TEST_WORKSPACE);
        }

        Stream<String> deleteFeedback() {
            return Stream.of(USER, null, "", "   ");
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success")
        void deleteFeedback(String author) {
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var spanId = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);
            var score = podamFactory.manufacturePojo(FeedbackScore.class);
            createAndAssert(spanId, score, TEST_WORKSPACE, API_KEY);
            expectedSpan = expectedSpan.toBuilder().feedbackScores(List.of(score)).build();
            var actualSpan = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(actualSpan.feedbackScores()).hasSize(1);

            var deleteFeedbackScore = DeleteFeedbackScore.builder()
                    .name(score.name())
                    .author(author)
                    .build();
            spanResourceClient.deleteSpanFeedbackScore(deleteFeedbackScore, spanId, API_KEY, TEST_WORKSPACE);

            expectedSpan = expectedSpan.toBuilder().feedbackScores(null).build();
            var actualEntity = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(actualEntity.feedbackScores()).isNull();
        }
    }

    @Nested
    @DisplayName("Batch Feedback:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchSpansFeedback {

        Stream<Arguments> invalidRequestBodyParams() {
            return Stream.of(
                    arguments(FeedbackScoreBatch.builder().build(), "scores must not be null"),
                    arguments(FeedbackScoreBatch.builder().scores(List.of()).build(),
                            "scores size must be between 1 and 1000"),
                    arguments(FeedbackScoreBatch.builder().scores(
                            IntStream.range(0, 1001)
                                    .mapToObj(
                                            __ -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                                                    .toBuilder()
                                                    .projectName(DEFAULT_PROJECT).build())
                                    .collect(Collectors.toList()))
                            .build(), "scores size must be between 1 and 1000"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                                                    .toBuilder()
                                                    .projectName(DEFAULT_PROJECT).name(null).build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                                                    .toBuilder()
                                                    .projectName(DEFAULT_PROJECT).name("").build()))
                                    .build(),
                            "scores[0].name must not be blank"),
                    arguments(
                            FeedbackScoreBatch.builder()
                                    .scores(List
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                                                    .toBuilder()
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
                                            .of(podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                                                    .toBuilder()
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
            var id = spanResourceClient.createSpan(expectedSpan1, API_KEY, TEST_WORKSPACE);

            Span expectedSpan2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(UUID.randomUUID().toString())
                    .build();

            var id2 = spanResourceClient.createSpan(expectedSpan2, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                    .toBuilder()
                    .id(id)
                    .projectName(expectedSpan1.projectName())
                    .build();

            var score2 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id2)
                    .name("hallucination")
                    .projectName(expectedSpan2.projectName())
                    .build();

            var score3 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .name("hallucination")
                    .projectName(expectedSpan1.projectName())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(
                            FeedbackScoreBatch.builder().scores(List.of(score, score2, score3)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            expectedSpan1 = expectedSpan1.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score, score3)))
                    .build();
            expectedSpan2 = expectedSpan2.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score2)))
                    .build();

            getAndAssert(expectedSpan1, API_KEY, TEST_WORKSPACE);
            getAndAssert(expectedSpan2, API_KEY, TEST_WORKSPACE);
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
            var id = spanResourceClient.createSpan(expectedSpan1, apiKey, workspaceName);

            Span expectedSpan2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .build();

            var id2 = spanResourceClient.createSpan(expectedSpan2, apiKey, workspaceName);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class)
                    .toBuilder()
                    .id(id)
                    .projectName(expectedSpan1.projectName())
                    .build();

            var score2 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id2)
                    .name("hallucination")
                    .projectName(expectedSpan2.projectName())
                    .build();

            var score3 = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .name("hallucination")
                    .projectName(expectedSpan1.projectName())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(
                            FeedbackScoreBatch.builder().scores(List.of(score, score2, score3)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            expectedSpan1 = expectedSpan1.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score, score3)))
                    .build();
            expectedSpan2 = expectedSpan2.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score2)))
                    .build();

            getAndAssert(expectedSpan1, apiKey, workspaceName);
            getAndAssert(expectedSpan2, apiKey, workspaceName);
        }

        @ParameterizedTest
        @MethodSource("invalidRequestBodyParams")
        @DisplayName("when batch request is invalid, then return bad request")
        void feedback__whenBatchRequestIsInvalid__thenReturnBadRequest(FeedbackScoreBatchContainer batch,
                String errorMessage) {

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

            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedSpan.projectName())
                    .categoryName(null)
                    .reason(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(FeedbackScoreBatch.builder().scores(List.of(score)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            expectedSpan = expectedSpan.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score)))
                    .build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when feedback with category name or reason, then return no content")
        void feedback__whenFeedbackWithCategoryNameOrReason__thenReturnNoContent() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .parentSpanId(null)
                    .build();

            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedSpan.projectName())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(FeedbackScoreBatch.builder().scores(List.of(score)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            expectedSpan = expectedSpan.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(score)))
                    .build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when overriding feedback value, then return no content")
        void feedback__whenOverridingFeedbackValue__thenReturnNoContent() {

            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .parentSpanId(null)
                    .build();

            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            var score = podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                    .id(id)
                    .projectName(expectedSpan.projectName())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(FeedbackScoreBatch.builder().scores(List.of(score)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var newScore = score.toBuilder().value(podamFactory.manufacturePojo(BigDecimal.class)).build();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(FeedbackScoreBatch.builder().scores(List.of(newScore)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            expectedSpan = expectedSpan.toBuilder()
                    .feedbackScores(FeedbackScoreMapper.INSTANCE.toFeedbackScores(List.of(newScore)))
                    .build();
            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
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
                    .put(Entity.json(FeedbackScoreBatch.builder().scores(List.of(score)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
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
                    .put(Entity.json(FeedbackScoreBatch.builder().scores(List.of(score)).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
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

            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            List<FeedbackScoreBatchItem> scores = IntStream.range(0, 1000)
                    .mapToObj(__ -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .id(id)
                            .build())
                    .collect(Collectors.toList());

            spanResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource
        void feedback__whenLinkingByProjectName__thenReturnNoContent(Function<String, String> projectNameModifier) {

            String projectName = UUID.randomUUID().toString();

            projectResourceClient.createProject(projectName.toUpperCase(), API_KEY, TEST_WORKSPACE);

            var spans = IntStream.range(0, 10)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectId(null)
                            .projectName(projectNameModifier.apply(projectName))
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, API_KEY, TEST_WORKSPACE);

            List<FeedbackScoreBatchItem> scores = spans.stream()
                    .map(span -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(span.id())
                            .projectName(projectNameModifier.apply(projectName))
                            .build())
                    .collect(Collectors.toList());

            spanResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);

            var expectedSpansWithScores = spans.stream()
                    .map(span -> {
                        FeedbackScoreItem feedbackScoreBatchItem = scores.stream()
                                .filter(score -> score.id().equals(span.id()))
                                .findFirst()
                                .orElseThrow();
                        return span.toBuilder()
                                .feedbackScores(List.of(mapFeedbackScore(feedbackScoreBatchItem)))
                                .build();
                    })
                    .toList();

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), expectedSpansWithScores.reversed(),
                    expectedSpansWithScores.reversed(), List.of(), API_KEY, List.of(), List.of());
        }

        Stream<Arguments> feedback__whenLinkingByProjectName__thenReturnNoContent() {
            return getProjectNameModifierArg();
        }
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
    @DisplayName("Comment:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanComment {

        @Test
        void createCommentForNonExistingTraceFail() {
            spanResourceClient.generateAndCreateComment(generator.generate(), API_KEY, TEST_WORKSPACE, 404);
        }

        @Test
        void createAndGetComment() {
            // Create comment for existing span
            UUID spanId = spanResourceClient.createSpan(podamFactory.manufacturePojo(Span.class), API_KEY,
                    TEST_WORKSPACE);
            Comment expectedComment = spanResourceClient.generateAndCreateComment(spanId, API_KEY, TEST_WORKSPACE,
                    201);

            // Get created comment by id and assert
            Comment actualComment = spanResourceClient.getCommentById(expectedComment.id(), spanId, API_KEY,
                    TEST_WORKSPACE, 200);
            assertComment(expectedComment, actualComment);
        }

        @Test
        void createAndUpdateComment() {
            // Create comment for existing span
            UUID spanId = spanResourceClient.createSpan(podamFactory.manufacturePojo(Span.class), API_KEY,
                    TEST_WORKSPACE);
            Comment expectedComment = spanResourceClient.generateAndCreateComment(spanId, API_KEY, TEST_WORKSPACE,
                    201);

            // Get created comment by id and assert
            Comment actualComment = spanResourceClient.getCommentById(expectedComment.id(), spanId, API_KEY,
                    TEST_WORKSPACE, 200);

            // Update existing comment
            String updatedText = podamFactory.manufacturePojo(String.class);
            spanResourceClient.updateComment(updatedText, expectedComment.id(), API_KEY,
                    TEST_WORKSPACE, 204);

            // Get comment by id and assert it was updated
            Comment updatedComment = traceResourceClient.getCommentById(expectedComment.id(), spanId, API_KEY,
                    TEST_WORKSPACE, 200);
            assertUpdatedComment(actualComment, updatedComment, updatedText);
        }

        @Test
        void deleteComments() {
            // Create comments for existing span
            UUID spanId = spanResourceClient.createSpan(podamFactory.manufacturePojo(Span.class), API_KEY,
                    TEST_WORKSPACE);

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> spanResourceClient.generateAndCreateComment(spanId, API_KEY, TEST_WORKSPACE, 201))
                    .toList().reversed();

            // Check it was created
            expectedComments.forEach(
                    comment -> spanResourceClient.getCommentById(comment.id(), spanId, API_KEY, TEST_WORKSPACE, 200));

            // Delete comment
            BatchDelete request = BatchDelete.builder()
                    .ids(expectedComments.stream().map(Comment::id).collect(Collectors.toSet())).build();
            spanResourceClient.deleteComments(request, API_KEY, TEST_WORKSPACE);

            // Verify comments were actually deleted via get and update endpoints
            expectedComments.forEach(
                    comment -> spanResourceClient.getCommentById(comment.id(), spanId, API_KEY, TEST_WORKSPACE, 404));
            expectedComments
                    .forEach(comment -> spanResourceClient.updateComment(podamFactory.manufacturePojo(String.class),
                            comment.id(), API_KEY, TEST_WORKSPACE, 404));
        }

        @Test
        void getSpanWithComments() {
            UUID spanId = spanResourceClient.createSpan(podamFactory.manufacturePojo(Span.class), API_KEY,
                    TEST_WORKSPACE);
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> spanResourceClient.generateAndCreateComment(spanId, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            Span actualSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY);
            assertComments(expectedComments, actualSpan.comments());
        }

        @Test
        void getSpanPageWithComments() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();
            var spanId = spans.getFirst().id();

            spanResourceClient.batchCreateSpans(spans, API_KEY, TEST_WORKSPACE);

            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> spanResourceClient.generateAndCreateComment(spanId, API_KEY, TEST_WORKSPACE, 201))
                    .toList();

            spans = spans.stream()
                    .map(span -> span.id() != spanId ? span : span.toBuilder().comments(expectedComments).build())
                    .toList();

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), spans.reversed(), spans.reversed(), List.of(),
                    API_KEY, List.of(), List.of());
        }
    }

    @Nested
    @DisplayName("Get Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackScoreNames {

        Stream<Arguments> getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames() {
            return Stream.concat(
                    Stream.of(arguments(Optional.empty())),
                    Arrays.stream(SpanType.values()).map(type -> arguments(Optional.of(type))));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when get feedback score names, then return feedback score names")
        void getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames(
                Optional<SpanType> spanType) {

            // given
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // when
            String projectName = UUID.randomUUID().toString();

            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            Project project = projectResourceClient.getProject(projectId, apiKey, workspaceName);

            List<String> names = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            List<String> otherNames = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);

            // Create multiple values feedback scores
            List<String> multipleValuesFeedbackScores = names.subList(0, names.size() - 1);

            createMultiValueScores(multipleValuesFeedbackScores, project, apiKey, workspaceName, true, spanType);

            createMultiValueScores(List.of(names.getLast()), project, apiKey, workspaceName, true, spanType);

            createMultiValueScores(otherNames, project, apiKey, workspaceName, false, spanType);

            fetchAndAssertResponse(names, spanType, otherNames, projectId, apiKey, workspaceName);
        }
    }

    private void fetchAndAssertResponse(List<String> names, Optional<SpanType> spanType, List<String> otherNames,
            UUID projectId, String apiKey, String workspaceName) {

        WebTarget webTarget = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("feedback-scores")
                .path("names");

        webTarget = webTarget.queryParam("project_id", projectId);

        if (spanType.isPresent()) {
            webTarget = webTarget.queryParam("type", spanType.get().name());
        }

        List<String> expectedNames = spanType.isPresent()
                ? names
                : Stream.concat(names.stream(), otherNames.stream()).toList();

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

    private List<List<FeedbackScoreBatchItem>> createMultiValueScores(List<String> multipleValuesFeedbackScores,
            Project project, String apiKey, String workspaceName, boolean shouldBeFound, Optional<SpanType> spanType) {
        return IntStream.range(0, multipleValuesFeedbackScores.size())
                .mapToObj(i -> {

                    Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                            .name(project.name())
                            .build();

                    traceResourceClient.createTrace(trace, apiKey, workspaceName);

                    Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                            .traceId(trace.id())
                            .projectName(project.name())
                            .type(shouldBeFound ? spanType.orElse(SpanType.general) : getSpanType(spanType))
                            .build();

                    spanResourceClient.createSpan(span, apiKey, workspaceName);

                    List<FeedbackScoreBatchItem> scores = multipleValuesFeedbackScores.stream()
                            .map(name -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(project.name())
                                    .id(span.id())
                                    .build())
                            .collect(Collectors.toList());

                    spanResourceClient.feedbackScores(scores, apiKey, workspaceName);

                    return scores;
                }).toList();
    }

    private SpanType getSpanType(Optional<SpanType> spanType) {
        SpanType currentSpanType;

        do {
            currentSpanType = podamFactory.manufacturePojo(SpanType.class);
        } while (currentSpanType.equals(spanType.orElse(null)));

        return currentSpanType;
    }

    @Nested
    @DisplayName("Large Payload Tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class LargePayloadTests {

        @Test
        @DisplayName("Create span with two 35MB base64 encoded videos - should succeed with attachment stripping")
        void createSpan__whenTwoLargeVideoAttachments__thenSucceedWithStripping() throws Exception {
            // Given: Create two 35MB video attachments (simulating large video uploads)
            // 35MB raw * 4/3 (base64 overhead) = ~46.6MB base64 string each
            // Total JSON payload: ~93.2MB (well under 100MB cloud / 250MB self-hosted limit)
            int videoSizeBytes = 35 * 1024 * 1024; // 35MB each
            String base64Video1 = AttachmentPayloadUtilsTest.createValidPngBase64(videoSizeBytes); // Using PNG for simplicity
            String base64Video2 = AttachmentPayloadUtilsTest.createValidJpegBase64(videoSizeBytes);

            // Create input JSON with first large video
            String originalInputJson = String.format(
                    "{\"message\": \"Processing video upload\", " +
                            "\"video_data\": \"%s\", " +
                            "\"user_id\": \"user123\", " +
                            "\"session_id\": \"session456\"}",
                    base64Video1);

            // Create output JSON with second large video (result)
            String originalOutputJson = String.format(
                    "{\"result\": \"Video processed successfully\", " +
                            "\"processed_video\": \"%s\", " +
                            "\"duration_ms\": 5230}",
                    base64Video2);

            // Create span with PODAM factory following existing patterns
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree(originalOutputJson))
                    .metadata(JsonUtils.readTree("{}"))
                    .feedbackScores(null)
                    .build();

            // When: Create the span
            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

            // Then: Verify span was created
            assertThat(spanId).isNotNull();

            // Verify attachments were stripped and replaced with references (async operation)
            Awaitility.await()
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                        assertThat(retrievedSpan).isNotNull();

                        String inputString = retrievedSpan.input().toString();
                        String outputString = retrievedSpan.output().toString();

                        // Original base64 videos should be stripped
                        assertThat(inputString).doesNotContain(base64Video1);
                        assertThat(outputString).doesNotContain(base64Video2);

                        // Should contain attachment references like [input-attachment-1-12345.png]
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
                        assertThat(outputString).containsPattern("\\[output-attachment-\\d+-\\d+\\.(jpg|jpeg)\\]");
                    });
        }

        @Test
        @DisplayName("Create span with attachment exceeding limit - should return 400 Bad Request")
        void createSpan__whenSingleAttachmentExceedsLimit__thenReject() throws Exception {
            // Given: Create a single attachment that exceeds the 250MB test limit
            // In test environment: maxStringLength = 250MB (262,144,000 bytes)
            // We'll create a 190MB raw attachment, which becomes ~253MB as base64 (190 * 4/3 = 253.3MB)
            // This exceeds the 250MB limit and should be rejected during deserialization
            int videoSizeBytes = 190 * 1024 * 1024; // 190MB raw -> ~253MB base64
            String base64Video = AttachmentPayloadUtilsTest.createValidPngBase64(videoSizeBytes);

            // Create input JSON with the oversized video
            String originalInputJson = String.format(
                    "{\"message\": \"Processing large video\", " +
                            "\"video_data\": \"%s\", " +
                            "\"user_id\": \"user123\"}",
                    base64Video);

            // Create span with PODAM factory
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree("{}"))
                    .metadata(JsonUtils.readTree("{}"))
                    .feedbackScores(null)
                    .build();

            // When: Attempt to create the span
            // Then: Should fail with 400 Bad Request
            try (Response response = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE, 400)) {
                // Assert error message mentions the limit
                var errorResponse = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(errorResponse).isNotNull();
                assertThat(errorResponse.getMessage())
                        .containsIgnoringCase("String value length")
                        .containsIgnoringCase("exceeds the maximum allowed")
                        .containsIgnoringCase("StreamReadConstraints");
            }
        }

        @Test
        @DisplayName("Create span with multiple large attachments under individual limit - should succeed")
        void createSpan__whenMultipleLargeAttachmentsUnderIndividualLimit__thenSucceed() throws Exception {
            // Given: Create THREE 70MB attachments (each ~93MB as base64)
            // Total payload: ~280MB, but each individual string is under 250MB limit
            // This verifies the limit is per-string, not per total payload
            int videoSizeBytes = 70 * 1024 * 1024; // 70MB each -> ~93MB base64 each
            String base64Video1 = AttachmentPayloadUtilsTest.createValidPngBase64(videoSizeBytes);
            String base64Video2 = AttachmentPayloadUtilsTest.createValidJpegBase64(videoSizeBytes);
            String base64Video3 = AttachmentPayloadUtilsTest.createValidPngBase64(videoSizeBytes);

            // Create JSONs with large videos
            String originalInputJson = String.format(
                    "{\"video1\": \"%s\", \"video2\": \"%s\"}",
                    base64Video1, base64Video2);

            String originalOutputJson = String.format(
                    "{\"result_video\": \"%s\"}",
                    base64Video3);

            // Create span
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .input(JsonUtils.readTree(originalInputJson))
                    .output(JsonUtils.readTree(originalOutputJson))
                    .metadata(JsonUtils.readTree("{}"))
                    .feedbackScores(null)
                    .build();

            // When: Create the span
            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

            // Then: Should succeed and attachments should be stripped
            assertThat(spanId).isNotNull();

            // Verify attachments were stripped
            Awaitility.await()
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY, true);
                        assertThat(retrievedSpan).isNotNull();

                        String inputString = retrievedSpan.input().toString();
                        String outputString = retrievedSpan.output().toString();

                        // All three videos should be stripped
                        assertThat(inputString).doesNotContain(base64Video1);
                        assertThat(inputString).doesNotContain(base64Video2);
                        assertThat(outputString).doesNotContain(base64Video3);

                        // Should have attachment references
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.png\\]");
                        assertThat(inputString).containsPattern("\\[input-attachment-\\d+-\\d+\\.(jpg|jpeg)\\]");
                        assertThat(outputString).containsPattern("\\[output-attachment-\\d+-\\d+\\.png\\]");
                    });
        }
    }
}
