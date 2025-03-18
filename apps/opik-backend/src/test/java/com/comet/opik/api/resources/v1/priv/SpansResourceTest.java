package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
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
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.SpanAssertions;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.SpanMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jdbi.v3.core.Jdbi;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.com.google.common.collect.Lists;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertTraceComment;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertUpdatedComment;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.StatsUtils.getProjectSpanStatItems;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.domain.SpanService.PARENT_SPAN_IS_MISMATCH;
import static com.comet.opik.domain.SpanService.PROJECT_AND_WORKSPACE_NAME_MISMATCH;
import static com.comet.opik.domain.SpanService.TRACE_ID_MISMATCH;
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
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/spans";
    public static final String[] IGNORED_FIELDS = SpanAssertions.IGNORED_FIELDS;
    public static final String[] IGNORED_FIELDS_SCORES = SpanAssertions.IGNORED_FIELDS_SCORES;

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
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
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
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

            var spanId = createAndAssert(span, okApikey, workspaceName);

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

            var spanId = createAndAssert(span, okApikey, workspaceName);

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
        @MethodSource("credentials")
        void get__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var expectedSpans = actualResponse.readEntity(Span.SpanPage.class);
                    assertThat(expectedSpans.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
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

            var spanId = createAndAssert(span, okApikey, workspaceName);

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

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT, errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void search__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var span = podamFactory.manufacturePojo(Span.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            createAndAssert(span, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/search")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(SpanSearchStreamRequest.builder().projectName(span.projectName()).build()))) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var expectedSpans = spanResourceClient.getStreamedItems(actualResponse);
                    assertThat(expectedSpans).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
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

            var spanId = createAndAssert(span, API_KEY, workspaceName);

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

            var spanId = createAndAssert(span, API_KEY, workspaceName);

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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var expectedSpans = actualResponse.readEntity(Span.SpanPage.class);
                    assertThat(expectedSpans.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
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

                assertExpectedResponseWithoutBody(expected, actualResponse, HttpStatus.SC_NO_CONTENT,
                        UNAUTHORIZED_RESPONSE);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void search__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean expected,
                String workspaceName) {

            var span = podamFactory.manufacturePojo(Span.class);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            createAndAssert(span, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("/search")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(SpanSearchStreamRequest.builder().projectName(span.projectName()).build()))) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var expectedSpans = spanResourceClient.getStreamedItems(actualResponse);
                    assertThat(expectedSpans).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
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

    private void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindSpans {

        interface TestAssertion {
            void assertTest(String projectName,
                    UUID projectId,
                    String apiKey,
                    String workspaceName,
                    List<?> expected,
                    List<?> unexpected,
                    List<?> spans,
                    List<? extends SpanFilter> filters,
                    Map<String, String> queryParams);
        }

        interface TestAssertionArgs<T> {
            Tuple3<List<?>, List<?>, List<?>> get(List<T> spans, List<T> expected, List<T> unexpected);
        }

        private TestAssertion getStatsAssertionMethod() {
            return (projectName, projectId, apiKey, workspaceName, expected, unexpected, spans, filters,
                    queryParams) -> getStatsAndAssert(projectName, projectId, filters, apiKey, workspaceName,
                            (List<ProjectStatItem<?>>) expected, queryParams);
        }

        private TestAssertionArgs<Span> getStatsAssertionMethodArgs() {
            return (traces, expected, unexpected) -> Tuples.of(List.of(), getProjectSpanStatItems(expected),
                    List.of());
        }

        private TestAssertion getSpansAssertionMethod() {
            return (projectName, projectId, apiKey, workspaceName, expected, unexpected, spans, filters,
                    queryParams) -> getAndAssertPage(
                            workspaceName,
                            projectName,
                            projectId,
                            Optional.ofNullable(queryParams.get("trace_id")).map(UUID::fromString).orElse(null),
                            Optional.ofNullable(queryParams.get("type")).map(SpanType::valueOf).orElse(null),
                            filters,
                            Integer.parseInt(queryParams.getOrDefault("page", "1")),
                            Integer.parseInt(queryParams.getOrDefault("size",
                                    spans.size() + expected.size() + unexpected.size() + "")),
                            (List<Span>) expected,
                            expected.size(),
                            (List<Span>) unexpected,
                            apiKey,
                            List.of());
        }

        private TestAssertion streamSpansAssertionMethod() {
            return (projectName, projectId, apiKey, workspaceName, expected, unexpected, spans, filters,
                    queryParams) -> {

                var streamRequest = SpanSearchStreamRequest.builder().projectName(projectName)
                        .filters(List.copyOf(filters)).build();
                var actualSpans = spanResourceClient.getStreamAndAssertContent(apiKey, workspaceName, streamRequest);

                SpanAssertions.assertSpan(actualSpans, (List<Span>) expected, USER);
            };
        }

        private TestAssertionArgs<Span> getSpansAssertionMethodArgs() {
            return (traces, expected, unexpected) -> Tuples.of(traces, expected, unexpected);
        }

        private Stream<Arguments> getFilterTestArguments() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()));
        }

        private Stream<Arguments> equalAndNotEqualFilters() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()));
        }

        private Stream<Arguments> getUsageKeyArgs() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs(),
                            "completion_tokens",
                            SpanField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/spans/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs(),
                            "prompt_tokens",
                            SpanField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/spans/stats",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs(),
                            "total_tokens",
                            SpanField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/spans",
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs(),
                            "completion_tokens",
                            SpanField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/spans",
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs(),
                            "prompt_tokens",
                            SpanField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/spans",
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs(),
                            "total_tokens",
                            SpanField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/spans/search",
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs(),
                            "completion_tokens",
                            SpanField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/spans/search",
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs(),
                            "prompt_tokens",
                            SpanField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/spans/search",
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs(),
                            "total_tokens",
                            SpanField.USAGE_TOTAL_TOKENS));
        }

        private Stream<Arguments> getFeedbackScoresArgs() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(2, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(0, 2),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(2, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(0, 2),
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(2, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(0, 2),
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()));
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
                    arguments("/spans/stats", getStatsAssertionMethod(), getStatsAssertionMethodArgs(), arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/spans", getSpansAssertionMethod(), getSpansAssertionMethodArgs(), arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/spans/search", streamSpansAssertionMethod(), getSpansAssertionMethodArgs(),
                            arg.get()[0],
                            arg.get()[1], arg.get()[2])));
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

        private Stream<Arguments> getFilterInvalidOperatorForFieldTypeArgs() {
            return filterQueryBuilder.getUnSupportedOperators(SpanField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> Stream.of(
                                    Arguments.of("/stats", SpanFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("", SpanFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("/search", SpanFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()))));
        }

        private Stream<Arguments> getFilterInvalidValueOrKeyForFieldTypeArgs() {

            Stream<SpanFilter> filters = filterQueryBuilder.getSupportedOperators(SpanField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> switch (filter.getKey().getType()) {
                                case DICTIONARY, FEEDBACK_SCORES_NUMBER -> Stream.of(
                                        SpanFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(null)
                                                .value(getValidValue(filter.getKey()))
                                                .build(),
                                        SpanFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                // if no value is expected, create an invalid filter by an empty key
                                                .key(Operator.NO_VALUE_OPERATORS.contains(operator)
                                                        ? ""
                                                        : getKey(filter.getKey()))
                                                .value(getInvalidValue(filter.getKey()))
                                                .build());
                                default -> Stream.of(SpanFilter.builder()
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

        private Stream<Arguments> whenFilterByCorrespondingField__thenReturnSpansFiltered() {

            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            SpanField.TOTAL_ESTIMATED_COST,
                            Operator.GREATER_THAN,
                            "0",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            SpanField.TOTAL_ESTIMATED_COST,
                            Operator.GREATER_THAN,
                            "0",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            SpanField.TOTAL_ESTIMATED_COST,
                            Operator.GREATER_THAN,
                            "0",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/stats",
                            SpanField.MODEL,
                            Operator.EQUAL,
                            "gpt-3.5-turbo-1106",
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            SpanField.MODEL,
                            Operator.EQUAL,
                            "gpt-3.5-turbo-1106",
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            SpanField.MODEL,
                            Operator.EQUAL,
                            "gpt-3.5-turbo-1106",
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/stats",
                            SpanField.PROVIDER,
                            Operator.EQUAL,
                            null,
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    Arguments.of(
                            "/spans/search",
                            SpanField.PROVIDER,
                            Operator.EQUAL,
                            null,
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    Arguments.of(
                            "/spans",
                            SpanField.PROVIDER,
                            Operator.EQUAL,
                            null,
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()));
        }

        @Test
        void createAndGetByProjectName() {
            String projectName = RandomStringUtils.secure().nextAlphanumeric(10);

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

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

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
                    apiKey,
                    List.of());
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
                    apiKey,
                    List.of());
        }

        @Test
        void createAndGetByWorkspace() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

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

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

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
                    apiKey,
                    List.of());

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
                    apiKey,
                    List.of());
        }

        @Test
        void createAndGetByProjectNameAndTraceId() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
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

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

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
                    unexpectedSpans,
                    apiKey,
                    List.of());
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
                    unexpectedSpans,
                    apiKey,
                    List.of());
        }

        @Test
        void createAndGetByProjectIdAndTraceIdAndType() {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
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

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

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
                    unexpectedSpans,
                    apiKey,
                    List.of());
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
                    unexpectedSpans,
                    apiKey,
                    List.of());
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void whenUsingPagination__thenReturnTracesPaginated(boolean stream) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .comments(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = spans.stream()
                    .sorted(stream
                            ? Comparator.comparing(Span::id).reversed()
                            : Comparator.comparing(Span::traceId)
                                    .thenComparing(Span::parentSpanId)
                                    .thenComparing(Span::id)
                                    .reversed())
                    .toList();

            int pageSize = 2;

            if (stream) {
                AtomicReference<UUID> lastId = new AtomicReference<>(null);
                Lists.partition(expectedSpans, pageSize)
                        .forEach(trace -> {
                            var actualSpans = spanResourceClient.getStreamAndAssertContent(apiKey, workspaceName,
                                    SpanSearchStreamRequest.builder()
                                            .projectName(projectName)
                                            .lastRetrievedId(lastId.get())
                                            .limit(pageSize)
                                            .build());

                            SpanAssertions.assertSpan(actualSpans, trace, USER);

                            lastId.set(actualSpans.getLast().id());
                        });
            } else {

                for (int i = 0; i < expectedSpans.size() / pageSize; i++) {
                    int page = i + 1;
                    getAndAssertPage(
                            workspaceName,
                            projectName,
                            null,
                            null,
                            null,
                            List.of(),
                            page,
                            pageSize,
                            expectedSpans.subList(i * pageSize, Math.min((i + 1) * pageSize, expectedSpans.size())),
                            spans.size(),
                            List.of(),
                            apiKey,
                            List.of());
                }
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = Stream.of(podamFactory.manufacturePojo(Span.class))
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("page", 1)
                    .queryParam("size", 5)
                    .queryParam("project_name", projectName)
                    .queryParam("truncate", truncate)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {
                var actualPage = actualResponse.readEntity(Span.SpanPage.class);
                var actualSpans = actualPage.content();

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                assertThat(actualSpans).hasSize(1);

                var expectedSpans = spans.stream()
                        .map(span -> span.toBuilder()
                                .input(expected)
                                .output(expected)
                                .metadata(expected)
                                .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                        span.endTime()))
                                .build())
                        .toList();

                assertThat(actualSpans)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                        .containsExactlyElementsOf(expectedSpans);
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ImageTruncationArgProvider#provideTestArguments")
        void searchWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = Stream.of(podamFactory.manufacturePojo(Span.class))
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var streamRequest = SpanSearchStreamRequest.builder().projectName(projectName).truncate(truncate)
                    .limit(5).build();

            var expectedSpans = spans.stream()
                    .map(span -> span.toBuilder()
                            .input(expected)
                            .output(expected)
                            .metadata(expected)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                    span.endTime()))
                            .build())
                    .toList();

            List<Span> actualSpans = spanResourceClient.getStreamAndAssertContent(apiKey, workspaceName, streamRequest);

            SpanAssertions.assertSpan(actualSpans, expectedSpans, USER);
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterIdAndNameEqual__thenReturnSpansFiltered(String endpoint, TestAssertion testAssertion,
                TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

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

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource
        void whenFilterByCorrespondingField__thenReturnSpansFiltered(
                String endpoint, SpanField filterField, Operator filterOperator, String filterValue,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String model = "gpt-3.5-turbo-1106";
            String provider = "openai";

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var unexpectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var expectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .endTime(Instant.now().plusMillis(randomNumber()))
                    .provider(provider)
                    .model(model)
                    .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                    .feedbackScores(null)
                    .totalEstimatedCost(null)
                    .build());

            spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);

            // Check that it's filtered by cost
            List<SpanFilter> filters = List.of(
                    SpanFilter.builder()
                            .field(filterField)
                            .operator(filterOperator)
                            .value(filterField == SpanField.PROVIDER
                                    ? expectedSpans.getFirst().provider()
                                    : filterValue)
                            .build());

            var values = testAssertionArgs.get(expectedSpans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterTotalEstimatedCostEqual_NotEqual__thenReturnSpansFiltered(
                String endpoint, Operator operator, Function<List<Span>, List<Span>> getUnexpectedSpans,
                Function<List<Span>, List<Span>> getExpectedSpans, TestAssertion testAssertion,
                TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .model("gpt-3.5-turbo-1106")
                    .provider("openai")
                    .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            List<SpanFilter> filters = List.of(SpanFilter.builder()
                    .field(SpanField.TOTAL_ESTIMATED_COST)
                    .operator(operator)
                    .value("0")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterNameEqual_NotEqual__thenReturnSpansFiltered(
                String endpoint, Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                TestAssertion testAssertion,
                TestAssertionArgs<Span> testAssertionArgs) {

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
                            .metadata(null)
                            .feedbackScores(null)
                            .usage(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            List<SpanFilter> filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(operator)
                    .value(spans.getFirst().name().toUpperCase())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameStartsWith__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.STARTS_WITH)
                    .value(spans.getFirst().name().substring(0, spans.getFirst().name().length() - 4).toUpperCase())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEndsWith__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.ENDS_WITH)
                    .value(spans.getFirst().name().substring(3).toUpperCase())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameContains__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.CONTAINS)
                    .value(spans.getFirst().name().substring(2, spans.getFirst().name().length() - 3).toUpperCase())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameNotContains__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .name(generator.generate().toString())
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.NOT_CONTAINS)
                    .value(spanName.toUpperCase())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterStartTimeEqual_NotEqual__thenReturnSpansFiltered(String endpoint,
                Operator operator, Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans, TestAssertion testAssertion,
                TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var values = testAssertionArgs.get(spans, expectedSpans.reversed(), unexpectedSpans);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(operator)
                    .value(spans.getFirst().startTime().toString())
                    .build());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThan__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThanEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThan__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThanEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterEndTimeEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.END_TIME)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().endTime().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterInputEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.INPUT)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().input().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterOutputEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.OUTPUT)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().output().toString())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterMetadataEqualString__thenReturnSpansFiltered(String endpoint,
                Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(operator)
                    .key("$.model[0].version")
                    .value("OPENAI, CHAT-GPT 4.0")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNumber__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualBoolean__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("TRUE")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNull__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("NULL")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsString__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].version")
                    .value("CHAT-GPT")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNumber__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":\"two thousand twenty " +
                                    "four\",\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("02")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsBoolean__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .totalEstimatedCost(null)
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("TRU")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNull__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("NUL")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNumber__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanString__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].version")
                    .value("a")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanBoolean__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNull__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertionArgs.get(expectedSpans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNumber__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("2025")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanString__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].version")
                    .value("z")
                    .build());

            var values = testAssertionArgs.get(expectedSpans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanBoolean__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNull__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertionArgs.get(expectedSpans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTagsContains__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(spans.getFirst().tags().stream()
                            .toList()
                            .get(2)
                            .substring(0, spans.getFirst().name().length() - 4)
                            .toUpperCase())
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs, String usageKey, Field field) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            int firstUsage = randomNumber(1, 8);

            var spans = new ArrayList<Span>();

            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .usage(Map.of(usageKey, firstUsage))
                    .feedbackScores(null)
                    .totalEstimatedCost(BigDecimal.ZERO)
                    .build();
            spans.add(span);

            PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(it -> it.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, randomNumber()))
                            .feedbackScores(null)
                            .build())
                    .forEach(spans::add);

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThan__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs, String usageKey, Field field) {
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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.GREATER_THAN)
                            .value("123")
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThanEqual__thenReturnSpansFiltered(
                String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs,
                String usageKey, Field field) {

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
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThan__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs,
                String usageKey, Field field) {

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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.LESS_THAN)
                            .value("456")
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThanEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs,
                String usageKey, Field field) {
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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoresArgs")
        void whenFilterFeedbackScoresEqual_NotEqual__thenReturnSpansFiltered(String endpoint,
                Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

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
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .feedbackScores(
                                    PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScore.class).stream()
                                            .map(feedbackScore -> feedbackScore.toBuilder()
                                                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                                                    .build())
                                            .collect(toList()))
                            .build())
                    .collect(toCollection(ArrayList::new));

            spans.set(1, spans.get(1).toBuilder()
                    .feedbackScores(
                            updateFeedbackScore(spans.get(1).feedbackScores(), spans.getFirst().feedbackScores(), 2))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(spans.getFirst().feedbackScores().get(1).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(1).value().toString())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThan__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .totalEstimatedCost(null)
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(
                                    span.feedbackScores().stream()
                                            .map(feedbackScore -> feedbackScore.toBuilder()
                                                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                                                    .build())
                                            .collect(toList()),
                                    2, 1234.5678))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

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

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThanEqual__thenReturnSpansFiltered(
                String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(toList()), 2, 1234.5678))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

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

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThan__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(toList()), 2, 2345.6789))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

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

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThanEqual__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {
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
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(toList()), 2, 2345.6789))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

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

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getDurationArgs")
        void whenFilterByDuration__thenReturnSpansFiltered(String endpoint,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs,
                Operator operator, long end, double duration) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> {
                        Instant now = Instant.now();
                        return span.toBuilder()
                                .projectId(null)
                                .projectName(projectName)
                                .feedbackScores(null)
                                .totalEstimatedCost(null)
                                .startTime(now)
                                .endTime(Set.of(Operator.LESS_THAN, Operator.LESS_THAN_EQUAL).contains(operator)
                                        ? Instant.now().plusSeconds(2)
                                        : now.plusNanos(1000))
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            var start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(start)
                    .endTime(start.plus(end, ChronoUnit.MICROS))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());

            var unexpectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.DURATION)
                            .operator(operator)
                            .value(String.valueOf(duration))
                            .build());

            var values = testAssertionArgs.get(spans, expectedSpans, unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        Stream<Arguments> whenFilterByIsEmpty__thenReturnSpansFiltered() {
            return Stream.of(
                    arguments(
                            "/spans/search",
                            Operator.IS_NOT_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    arguments(
                            "/spans",
                            Operator.IS_NOT_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    arguments(
                            "/spans/stats",
                            Operator.IS_NOT_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()),
                    arguments(
                            "/spans/search",
                            Operator.IS_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            streamSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    arguments(
                            "/spans",
                            Operator.IS_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            getSpansAssertionMethod(),
                            getSpansAssertionMethodArgs()),
                    arguments(
                            "/spans/stats",
                            Operator.IS_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            getStatsAssertionMethod(),
                            getStatsAssertionMethodArgs()));
        }

        @ParameterizedTest
        @MethodSource
        void whenFilterByIsEmpty__thenReturnSpansFiltered(
                String endpoint,
                Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                TestAssertion testAssertion, TestAssertionArgs<Span> testAssertionArgs) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> {
                        Instant now = Instant.now();
                        return span.toBuilder()
                                .projectId(null)
                                .projectName(projectName)
                                .totalEstimatedCost(null)
                                .feedbackScores(span.feedbackScores().stream()
                                        .map(feedbackScore -> feedbackScore.toBuilder()
                                                .value(podamFactory.manufacturePojo(BigDecimal.class))
                                                .build())
                                        .collect(Collectors.toList()))
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            spans.set(spans.size() - 1, spans.getLast().toBuilder().feedbackScores(null).build());
            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
            spans.subList(0, spans.size() - 1).forEach(span -> span.feedbackScores()
                    .forEach(feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.FEEDBACK_SCORES)
                    .operator(operator)
                    .key(spans.getFirst().feedbackScores().getFirst().name())
                    .value("")
                    .build());

            var values = testAssertionArgs.get(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.getT2(), values.getT3(),
                    values.getT1(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidOperatorForFieldTypeArgs")
        void whenFilterInvalidOperatorForFieldType__thenReturn400(String path, SpanFilter filter) {
            int expectedStatus = HttpStatus.SC_BAD_REQUEST;
            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    expectedStatus,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = generator.generate().toString();
            List<SpanFilter> filters = List.of(filter);

            Response actualResponse;

            if ("/search".equals(path)) {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .post(Entity.json(SpanSearchStreamRequest.builder()
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
                assertThat(actualResponse.getStatus()).isEqualTo(expectedStatus);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(String path, SpanFilter filter) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedStatus = HttpStatus.SC_BAD_REQUEST;

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    expectedStatus,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = generator.generate().toString();
            var filters = List.of(filter);

            Response actualResponse;

            if ("/search".equals(path)) {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .post(Entity.json(SpanSearchStreamRequest.builder()
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
                assertThat(actualResponse.getStatus()).isEqualTo(expectedStatus);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
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

        @ParameterizedTest
        @MethodSource
        void whenSortingByValidFields__thenReturnTracesSorted(Comparator<Span> comparator,
                SortingField sorting) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            AtomicInteger index = new AtomicInteger(0);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .feedbackScores(null)
                            .comments(null)
                            .projectName(projectName)
                            .endTime(span.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .totalEstimatedCost(Objects.equals(sorting.field(), SortableFields.TOTAL_ESTIMATED_COST)
                                    ? BigDecimal.valueOf(randomNumber())
                                    : null)
                            .createdAt(Instant.now().plusMillis(index.getAndIncrement()))
                            .lastUpdatedAt(Instant.now().plusMillis(index.getAndIncrement()))
                            .build())
                    .map(span -> span.toBuilder()
                            .duration(span.startTime().until(span.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            if (Set.of(SortableFields.CREATED_AT, SortableFields.LAST_UPDATED_AT).contains(sorting.field())) {
                spans.forEach(span -> spanResourceClient.createSpan(span, apiKey, workspaceName));
            } else {
                spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
            }

            var expectedSpans = spans.stream()
                    .sorted(comparator)
                    .toList();

            getAndAssertPage(workspaceName, projectName, List.of(), spans, expectedSpans, List.of(), apiKey,
                    List.of(sorting));
        }

        static Stream<Arguments> whenSortingByValidFields__thenReturnTracesSorted() {

            Comparator<Span> inputComparator = Comparator.comparing(span -> span.input().toString());
            Comparator<Span> outputComparator = Comparator.comparing(span -> span.output().toString());
            Comparator<Span> metadataComparator = Comparator.comparing(span -> span.metadata().toString());
            Comparator<Span> tagsComparator = Comparator.comparing(span -> span.tags().toString());
            Comparator<Span> errorInfoComparator = Comparator.comparing(span -> span.errorInfo().toString());
            Comparator<Span> usageComparator = Comparator.comparing(span -> StringUtils.join(span.usage()));

            return Stream.of(
                    Arguments.of(Comparator.comparing(Span::id),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::id).reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::traceId),
                            SortingField.builder().field(SortableFields.TRACE_ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::traceId).reversed(),
                            SortingField.builder().field(SortableFields.TRACE_ID).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::parentSpanId),
                            SortingField.builder().field(SortableFields.PARENT_SPAN_ID).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::parentSpanId).reversed(),
                            SortingField.builder().field(SortableFields.PARENT_SPAN_ID).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::name),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::name).reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::startTime),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::startTime).reversed(),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::endTime),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::endTime).reversed(),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.DESC).build()),
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
                    Arguments.of(usageComparator,
                            SortingField.builder().field(SortableFields.USAGE).direction(Direction.ASC).build()),
                    Arguments.of(usageComparator.reversed(),
                            SortingField.builder().field(SortableFields.USAGE).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::createdAt)
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::createdAt).reversed()
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::lastUpdatedAt)
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::lastUpdatedAt).reversed()
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(Span::totalEstimatedCost)
                                    .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::totalEstimatedCost).reversed()
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(Span::duration)
                                    .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(Span::duration).reversed()
                                    .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.DESC).build()),
                    Arguments.of(errorInfoComparator,
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.ASC).build()),
                    Arguments.of(errorInfoComparator.reversed(),
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.DESC).build()));
        }

        @Test
        void whenSortingByInvalidField__thenReturn400() {
            var field = RandomStringUtils.secure().nextAlphanumeric(10);
            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_BAD_REQUEST,
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

    private void getAndAssertPage(
            String workspaceName,
            String projectName,
            List<? extends Filter> filters,
            List<Span> spans,
            List<Span> expectedSpans,
            List<Span> unexpectedSpans,
            String apiKey,
            List<SortingField> sortingFields) {
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
                sortingFields);
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
            List<Span> unexpectedSpans,
            String apiKey,
            List<SortingField> sortingFields) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParam("project_name", projectName)
                .queryParam("project_id", projectId)
                .queryParam("trace_id", traceId)
                .queryParam("type", type)
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .queryParam("sorting", toURLEncodedQueryParam(sortingFields))
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

            assertThat(actualSpans).hasSize(expectedSpans.size());
            assertThat(actualSpans)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedSpans);

            SpanAssertions.assertIgnoredFields(actualSpans, expectedSpans, USER);

            if (!unexpectedSpans.isEmpty()) {
                assertThat(actualSpans)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedSpans);
            }
        }
    }

    private String toURLEncodedQueryParam(List<?> filters) {
        return CollectionUtils.isEmpty(filters)
                ? null
                : URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
    }

    private UUID createAndAssert(Span expectedSpan, String apiKey, String workspaceName) {
        return spanResourceClient.createSpan(expectedSpan, apiKey, workspaceName);
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

    private void createAndAssertErrorMessage(Span span, String apiKey, String workspaceName, int status,
            String errorMessage) {
        try (var response = spanResourceClient.createSpan(span, apiKey, workspaceName, status)) {
            assertThat(response.hasEntity()).isTrue();
            assertThat(response.readEntity(ErrorMessage.class).errors().getFirst()).isEqualTo(errorMessage);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateSpan {

        @Test
        void createAndGetById() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createSpanWithNonNumericNumbers() throws JsonProcessingException {
            var expectedSpan = podamFactory.manufacturePojo(Span.class);
            var span = (ObjectNode) JsonUtils.readTree(expectedSpan);
            var input = JsonUtils.MAPPER.createObjectNode();
            input.put("value", Double.POSITIVE_INFINITY);
            span.replace("input", input);
            var customObjectMapper = new ObjectMapper()
                    .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
                    .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature());
            var spanStr = customObjectMapper.writeValueAsString(span);
            spanResourceClient.createSpan(spanStr, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            getAndAssert(
                    expectedSpan.toBuilder().input(JsonUtils.getJsonNodeFromString("{\"value\": \"Infinity\"}"))
                            .build(),
                    API_KEY, TEST_WORKSPACE);
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
                            usage);
            if (MapUtils.isNotEmpty(usage)) {
                assertThat(expectedCost.compareTo(BigDecimal.ZERO) > 0).isTrue();
            }
            var span = getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
            assertThat(span.totalEstimatedCost())
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedCost.compareTo(BigDecimal.ZERO) == 0 ? null : expectedCost);
        }

        Stream<Arguments> createAndGetCost() {
            var metadata = JsonUtils
                    .getJsonNodeFromString(
                            "{\"created_from\":\"openai\",\"type\":\"openai_chat\",\"model\":\"gpt-3.5-turbo\"}");
            return Stream.of(
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gpt-3.5-turbo-1106", "openai",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "gemini-1.5-pro-preview-0514", "google_vertexai",
                            null, null),
                    Arguments.of(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))),
                            "claude-3-sonnet-20240229", "anthropic",
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
                    .build();
            spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createOnlyRequiredFieldsAndGetById() {
            var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder()
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
            var expectedSpanId = spanResourceClient.createSpan(expectedSpan, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedSpan.toBuilder().id(expectedSpanId).build(), API_KEY, TEST_WORKSPACE);
        }

        @Test
        void createSpansWithSameIdIdempotent() {
            var span1 = podamFactory.manufacturePojo(Span.class);
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

            var expectedSpanBuilder = span.toBuilder();
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

        @ParameterizedTest
        @MethodSource
        void batch__whenCreateSpans__thenReturnNoContent(Function<String, String> projectNameModifier) {

            String projectName = UUID.randomUUID().toString();
            createProject(projectName, TEST_WORKSPACE, API_KEY);

            var expectedSpans = IntStream.range(0, 1000)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectId(null)
                            .projectName(projectNameModifier.apply(projectName))
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateAndAssert(expectedSpans, API_KEY, TEST_WORKSPACE);

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), List.of(), expectedSpans.reversed(), List.of(),
                    API_KEY, List.of());
        }

        Stream<Arguments> batch__whenCreateSpans__thenReturnNoContent() {
            return getProjectNameModifierArg();
        }

        @Test
        void batch__whenSpansProjectNameIsNull__thenUserDefaultProjectAndReturnNoContent() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .projectName(null)
                            .endTime(null)
                            .usage(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateAndAssert(expectedSpans, apiKey, workspaceName);

            getAndAssertPage(workspaceName, DEFAULT_PROJECT, List.of(), List.of(), expectedSpans.reversed(), List.of(),
                    apiKey, List.of());
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
                    API_KEY, List.of());
        }

    }

    private void batchCreateAndAssert(List<Span> expectedSpans, String apiKey, String workspaceName) {
        spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);
    }

    private Span getAndAssert(Span expectedSpan, String apiKey, String workspaceName) {
        var actualSpan = spanResourceClient.getById(expectedSpan.id(), workspaceName, apiKey);
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
        var expected = DurationUtils.getDurationInMillisWithSubMilliPrecision(
                expectedSpan.startTime(), expectedSpan.endTime());
        if (actualSpan.duration() == null || expected == null) {
            assertThat(actualSpan.duration()).isEqualTo(expected);
        } else {
            assertThat(actualSpan.duration()).isEqualTo(expected, within(0.001));
        }
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
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class);
            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var spanUpdate = expectedSpanUpdate.toBuilder()
                    .projectId(null)
                    .projectName(expectedSpan.projectName())
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .build();
            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder();
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);
        }

        static Stream<SpanUpdate> update__whenFieldIsNotNull__thenAcceptUpdate() {
            return Stream.of(
                    SpanUpdate.builder().endTime(Instant.now()).build(),
                    SpanUpdate.builder().input(JsonUtils.getJsonNodeFromString("{ \"input\": \"data\"}")).build(),
                    SpanUpdate.builder().output(JsonUtils.getJsonNodeFromString("{ \"output\": \"data\"}")).build(),
                    SpanUpdate.builder().metadata(JsonUtils.getJsonNodeFromString("{ \"metadata\": \"data\"}")).build(),
                    SpanUpdate.builder().tags(Set.of(
                            RandomStringUtils.secure().nextAlphanumeric(10),
                            RandomStringUtils.secure().nextAlphanumeric(10),
                            RandomStringUtils.secure().nextAlphanumeric(10),
                            RandomStringUtils.secure().nextAlphanumeric(10),
                            RandomStringUtils.secure().nextAlphanumeric(10))).build(),
                    SpanUpdate.builder().usage(Map.of(
                            RandomStringUtils.secure().nextAlphanumeric(10), randomNumber(),
                            RandomStringUtils.secure().nextAlphanumeric(10), randomNumber(),
                            RandomStringUtils.secure().nextAlphanumeric(10), randomNumber(),
                            RandomStringUtils.secure().nextAlphanumeric(10), randomNumber(),
                            RandomStringUtils.secure().nextAlphanumeric(10), randomNumber())).build());
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

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder();
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
                    .model("gpt-4o-2024-08-06")
                    .provider("openai")
                    .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                    .totalEstimatedCost(initialManualCost)
                    .build();

            createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            SpanUpdate spanUpdate = expectedSpanUpdate.toBuilder()
                    .parentSpanId(expectedSpan.parentSpanId())
                    .traceId(expectedSpan.traceId())
                    .projectName(expectedSpan.projectName())
                    .build();

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            var expectedSpanBuilder = expectedSpan.toBuilder();
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, expectedSpanUpdate);
            var actualSpan = getAndAssert(expectedSpanBuilder.build(), API_KEY, TEST_WORKSPACE);

            BigDecimal expectedCost;
            if (expectedSpanUpdate.totalEstimatedCost() != null) {
                expectedCost = expectedSpanUpdate.totalEstimatedCost();
            } else if (initialManualCost != null) {
                expectedCost = initialManualCost;
            } else {
                expectedCost = CostService.calculateCost(
                        expectedSpanUpdate.model() != null ? expectedSpanUpdate.model() : expectedSpan.model(),
                        expectedSpanUpdate.provider() != null ? expectedSpanUpdate.provider() : expectedSpan.provider(),
                        expectedSpanUpdate.usage() != null ? expectedSpanUpdate.usage() : expectedSpan.usage());
            }

            assertThat(actualSpan.totalEstimatedCost())
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedCost);
        }

        Stream<Arguments> update__whenCostIsChanged__thenAcceptUpdate() {
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
            var expectedSpanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();
            spanResourceClient.updateSpan(id, expectedSpanUpdate, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when span does not exist, then return create it")
        void when__spanDoesNotExist__thenReturnCreateIt() {
            var id = generator.generate();

            var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .build();

            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

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
            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);
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

            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

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

            spanResourceClient.updateSpan(id, spanUpdate, API_KEY, TEST_WORKSPACE);

            SpanUpdate newSpan = mapper.apply(spanUpdate, id);

            try (var actualResponse = spanResourceClient.updateSpan(
                    id, newSpan, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CONFLICT)) {
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

            var update1 = Mono
                    .fromRunnable(() -> spanResourceClient.updateSpan(id, spanUpdate3, API_KEY, TEST_WORKSPACE));
            var create = Mono.fromRunnable(() -> createAndAssert(newSpan, API_KEY, TEST_WORKSPACE));
            var update2 = Mono
                    .fromRunnable(() -> spanResourceClient.updateSpan(id, spanUpdate2, API_KEY, TEST_WORKSPACE));
            var update3 = Mono
                    .fromRunnable(() -> spanResourceClient.updateSpan(id, spanUpdate1, API_KEY, TEST_WORKSPACE));

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

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .tags(spanUpdate.tags())
                    .projectId(projectId)
                    .build();

            Span actualSpan = getAndAssert(updatedSpan.toBuilder().tags(null).build(), API_KEY, TEST_WORKSPACE);

            assertThat(actualSpan.tags()).isNull();
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

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .metadata(metadata)
                    .projectId(projectId)
                    .build();

            Span actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actualSpan.metadata()).isEqualTo(metadata);
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

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .input(input)
                    .projectId(projectId)
                    .build();

            Span actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actualSpan.input()).isEqualTo(input);
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

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            UUID projectId = getProjectId(spanUpdate.projectName(), TEST_WORKSPACE, API_KEY);

            Span updatedSpan = expectedSpan.toBuilder()
                    .output(output)
                    .projectId(projectId)
                    .build();

            Span actualSpan = getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);

            assertThat(actualSpan.output()).isEqualTo(output);
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

            spanResourceClient.updateSpan(expectedSpan.id(), spanUpdate, API_KEY, TEST_WORKSPACE);

            Span updatedSpan = expectedSpan.toBuilder()
                    .projectId(projectId)
                    .metadata(spanUpdate.metadata())
                    .model(spanUpdate.model())
                    .provider(spanUpdate.provider())
                    .input(spanUpdate.input())
                    .output(spanUpdate.output())
                    .endTime(spanUpdate.endTime())
                    .tags(spanUpdate.tags())
                    .usage(spanUpdate.usage())
                    .errorInfo(spanUpdate.errorInfo())
                    .build();

            getAndAssert(updatedSpan, API_KEY, TEST_WORKSPACE);
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

        Stream<Arguments> invalidRequestBodyParams() {
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

            var id = createAndAssert(expectedSpan, API_KEY, TEST_WORKSPACE);

            var scores = IntStream.range(0, 1000)
                    .mapToObj(__ -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .id(id)
                            .build())
                    .toList();

            spanResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource
        void feedback__whenLinkingByProjectName__thenReturnNoContent(Function<String, String> projectNameModifier) {

            String projectName = UUID.randomUUID().toString();

            createProject(projectName.toUpperCase(), TEST_WORKSPACE, API_KEY);

            var spans = IntStream.range(0, 10)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectId(null)
                            .projectName(projectNameModifier.apply(projectName))
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            batchCreateAndAssert(spans, API_KEY, TEST_WORKSPACE);

            var scores = spans.stream()
                    .map(span -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(span.id())
                            .projectName(projectNameModifier.apply(projectName))
                            .build())
                    .toList();

            spanResourceClient.feedbackScores(scores, API_KEY, TEST_WORKSPACE);

            var expectedSpansWithScores = spans.stream()
                    .map(span -> {
                        FeedbackScoreBatchItem feedbackScoreBatchItem = scores.stream()
                                .filter(score -> score.id().equals(span.id()))
                                .findFirst()
                                .orElseThrow();
                        return span.toBuilder()
                                .feedbackScores(List.of(mapFeedbackScore(feedbackScoreBatchItem)))
                                .build();
                    })
                    .toList();

            getAndAssertPage(TEST_WORKSPACE, projectName, List.of(), expectedSpansWithScores.reversed(),
                    expectedSpansWithScores.reversed(), List.of(), API_KEY, List.of());
        }

        Stream<Arguments> feedback__whenLinkingByProjectName__thenReturnNoContent() {
            return getProjectNameModifierArg();
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
            assertTraceComment(expectedComment, actualComment);
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
                    API_KEY, List.of());
        }
    }

    private void getStatsAndAssert(String projectName,
            UUID projectId,
            List<? extends SpanFilter> filters,
            String apiKey,
            String workspaceName,
            List<ProjectStatItem<?>> expectedStats,
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
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                .isEqualTo(expectedStats);
    }

    private static int randomNumber() {
        return randomNumber(10, 99);
    }

    private static int randomNumber(int minValue, int maxValue) {
        return PodamUtils.getIntegerInRange(minValue, maxValue);
    }

    @Nested
    @DisplayName("Get Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackScoreNames {

        Stream<Arguments> getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames() {
            return Stream.of(
                    arguments(Optional.empty()),
                    arguments(Optional.of(SpanType.llm)),
                    arguments(Optional.of(SpanType.general)),
                    arguments(Optional.of(SpanType.tool)));
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
                            .toList();

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
