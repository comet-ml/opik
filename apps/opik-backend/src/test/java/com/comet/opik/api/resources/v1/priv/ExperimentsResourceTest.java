package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Comment;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentItemsDelete;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.CommentAssertionUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DatasetEventInfoHolder;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.Pair;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoresIgnoredFieldsAndSetThemToNull;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.QuotaLimitTestUtils.ERR_USAGE_LIMIT_EXCEEDED;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.api.resources.utils.TestUtils.getIdFromLocation;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.utils.ValidationUtils.SCALE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentsResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String ITEMS_PATH = "/items";

    private static final String API_KEY = UUID.randomUUID().toString();

    private static final String[] EXPERIMENT_IGNORED_FIELDS = new String[]{
            "id", "datasetId", "name", "feedbackScores", "traceCount", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "comments"};
    public static final String[] ITEM_IGNORED_FIELDS = {"input", "output", "feedbackScores", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy", "comments"};

    public static final String[] EXPERIMENT_ITEMS_IGNORED_FIELDS = {"id", "experimentId", "createdAt", "lastUpdatedAt",
            "feedbackScores.createdAt", "feedbackScores.lastUpdatedAt"};

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String TEST_WORKSPACE = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);

    private static final TypeReference<ExperimentItem> EXPERIMENT_ITEM_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;
    private final AppContextConfig contextConfig;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        contextConfig = AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .mockEventBus(Mockito.mock(EventBus.class))
                .build();

        app = newTestDropwizardAppExtension(contextConfig);
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private EventBus defaultEventBus;
    private PromptResourceClient promptResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
        defaultEventBus = contextConfig.mockEventBus();

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.promptResourceClient = new PromptResourceClient(client, baseURI, podamFactory);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.experimentResourceClient = new ExperimentResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.datasetResourceClient = new DatasetResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void mockSessionCookieTargetWorkspace(
            String sessionToken, String workspaceName, String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(
                wireMock.server(), sessionToken, workspaceName, workspaceId, USER);
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
        void getById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var expectedExperiment = generateExperiment().toBuilder().optimizationId(null).build();

            createAndAssert(expectedExperiment, okApikey, workspaceName);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path(expectedExperiment.id().toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var actualEntity = actualResponse.readEntity(Experiment.class);
                    assertThat(actualEntity.id()).isEqualTo(expectedExperiment.id());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void create__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var expectedExperiment = generateExperiment();

            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(expectedExperiment))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void find__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            var expectedExperiment = generateExperiment();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = createAndAssert(expectedExperiment, okApikey, workspaceName);

            Experiment experiment = getAndAssert(id, expectedExperiment, workspaceName, okApikey);

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", 1)
                    .queryParam("size", 1)
                    .queryParam("datasetId", experiment.datasetId())
                    .queryParam("name", expectedExperiment.name())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var actualEntity = actualResponse.readEntity(ExperimentPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteExperimentsById_whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var experiments = experimentResourceClient.generateExperimentList();

            experiments.forEach(experiment -> createAndAssert(experiment, okApikey, workspaceName));

            Set<UUID> ids = experiments.stream().map(Experiment::id).collect(toSet());

            var deleteRequest = new DeleteIdsHolder(ids);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteRequest))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteExperimentItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var workspaceName = UUID.randomUUID().toString();

            var createRequest = getExperimentItemsBatch();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            createAndAssert(createRequest, okApikey, workspaceName);

            createRequest.experimentItems().forEach(item -> getAndAssert(item, workspaceName, okApikey));

            var ids = createRequest.experimentItems().stream().map(ExperimentItem::id).collect(toSet());
            var deleteRequest = ExperimentItemsDelete.builder().ids(ids).build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteRequest))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void createExperimentItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class).toBuilder()
                    .build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getExperimentItemById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var workspaceName = UUID.randomUUID().toString();
            var expectedExperimentItem = podamFactory.manufacturePojo(ExperimentItem.class);
            var id = expectedExperimentItem.id();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            createAndAssert(ExperimentItemsBatch.builder()
                    .experimentItems(Set.of(expectedExperimentItem))
                    .build(), okApikey, workspaceName);

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                    var actualEntity = actualResponse.readEntity(ExperimentItem.class);
                    assertThat(actualEntity.id()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
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

        @BeforeEach
        void setUp() {
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, WORKSPACE_ID))));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(fakeSessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    HttpStatus.SC_UNAUTHORIZED)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getById__whenSessionTokenIsPresent__thenReturnProperResponse(
                String currentSessionToken, boolean success, String workspaceName) {
            var expectedExperiment = generateExperiment();

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);
            createAndAssert(expectedExperiment, API_KEY, workspaceName);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path(expectedExperiment.id().toString())
                    .request()
                    .cookie(SESSION_COOKIE, currentSessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {
            var expectedExperiment = generateExperiment();

            mockTargetWorkspace(API_KEY, sessionToken, WORKSPACE_ID);

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(expectedExperiment))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void find__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            var expectedExperiment = generateExperiment();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            UUID id = createAndAssert(expectedExperiment, apiKey, workspaceName);

            Experiment experiment = getAndAssert(id, expectedExperiment, workspaceName, apiKey);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", 1)
                    .queryParam("size", 1)
                    .queryParam("datasetId", experiment.datasetId())
                    .queryParam("name", expectedExperiment.name())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var actualEntity = actualResponse.readEntity(ExperimentPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteExperimentsById_whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var experiments = experimentResourceClient.generateExperimentList();

            experiments.forEach(experiment -> createAndAssert(experiment, API_KEY, workspaceName));

            Set<UUID> ids = experiments.stream().map(Experiment::id).collect(toSet());

            var deleteRequest = new DeleteIdsHolder(ids);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteRequest))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteExperimentItems__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var createRequest = getExperimentItemsBatch();
            createAndAssert(createRequest, API_KEY, workspaceName);
            createRequest.experimentItems().forEach(item -> getAndAssert(item, workspaceName, API_KEY));

            var ids = createRequest.experimentItems().stream().map(ExperimentItem::id).collect(toSet());
            var deleteRequest = ExperimentItemsDelete.builder().ids(ids).build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteRequest))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void createExperimentItems__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {

            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getExperimentItemById__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean success, String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var expectedExperiment = podamFactory.manufacturePojo(ExperimentItem.class);

            ExperimentItemsBatch batch = ExperimentItemsBatch.builder()
                    .experimentItems(Set.of(expectedExperiment))
                    .build();

            createAndAssert(batch, API_KEY, workspaceName);

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path(expectedExperiment.id().toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var actualEntity = actualResponse.readEntity(ExperimentItem.class);
                    assertThat(actualEntity.id()).isEqualTo(expectedExperiment.id());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }
    }

    private ExperimentItemsBatch getExperimentItemsBatch() {
        ExperimentItemsBatch itemsBatch = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

        return itemsBatch.toBuilder()
                .experimentItems(itemsBatch.experimentItems().stream()
                        .map(item -> item.toBuilder()
                                .usage(null)
                                .duration(null)
                                .totalEstimatedCost(null)
                                .build())
                        .collect(toSet()))
                .build();
    }

    private Experiment generateExperiment() {
        return experimentResourceClient.createPartialExperiment().build();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindExperiments {

        @BeforeEach
        void setUp() {
            Mockito.reset(defaultEventBus);
        }

        @Test
        void findByDatasetId() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);
            var experiments = experimentResourceClient.generateExperimentList()
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .datasetName(datasetName)
                            .build())
                    .toList();
            experiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment());

            unexpectedExperiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                    .datasetId();
            String name = null;
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, datasetId, name, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null);
            findAndAssert(workspaceName, 2, pageSize, datasetId, name, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null);
        }

        Stream<Arguments> findByName() {
            var exactName = RandomStringUtils.secure().nextAlphanumeric(10);
            var exactNameIgnoreCase = RandomStringUtils.secure().nextAlphanumeric(10);
            var partialName = RandomStringUtils.secure().nextAlphanumeric(10);
            var partialNameIgnoreCase = RandomStringUtils.secure().nextAlphanumeric(10);
            return Stream.of(
                    arguments(exactName, exactName),
                    arguments(exactNameIgnoreCase, exactNameIgnoreCase.toLowerCase()),
                    arguments(partialName, partialName.substring(1, partialName.length() - 1)),
                    arguments(partialNameIgnoreCase,
                            partialNameIgnoreCase.substring(1, partialNameIgnoreCase.length() - 1).toLowerCase()));
        }

        @MethodSource
        @ParameterizedTest
        void findByName(String name, String nameQueryParam) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiments = experimentResourceClient.generateExperimentList()
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .name(name)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment());

            unexpectedExperiments
                    .forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            UUID datasetId = null;
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, datasetId, nameQueryParam, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null);
            findAndAssert(workspaceName, 2, pageSize, datasetId, nameQueryParam, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null);
        }

        @Test
        void findByDatasetIdAndName() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);
            var name = RandomStringUtils.secure().nextAlphanumeric(10);

            var experiments = experimentResourceClient.generateExperimentList()
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .datasetName(datasetName)
                            .name(name)
                            .metadata(null)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment());

            unexpectedExperiments
                    .forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                    .datasetId();
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, datasetId, name, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null);
            findAndAssert(workspaceName, 2, pageSize, datasetId, name, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null);
        }

        @ParameterizedTest
        @MethodSource
        void findByOptimizationIdAndType(ExperimentType type) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            UUID optimizationId = UUID.randomUUID();

            var experiments = experimentResourceClient.generateExperimentList()
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .optimizationId(optimizationId)
                            .type(type)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment());

            unexpectedExperiments
                    .forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, null, null, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, optimizationId, Set.of(type));
            findAndAssert(workspaceName, 2, pageSize, null, null, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, optimizationId, Set.of(type));
        }

        private Stream<ExperimentType> findByOptimizationIdAndType() {
            return Stream.of(ExperimentType.TRIAL, ExperimentType.MINI_BATCH);
        }

        @Test
        void findAll() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiments = experimentResourceClient.generateExperimentList();

            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var page = 1;
            var pageSize = experiments.size();

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", page)
                    .queryParam("size", pageSize)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {
                var actualPage = actualResponse.readEntity(ExperimentPage.class);
                var actualExperiments = actualPage.content();

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                assertThat(actualPage.page()).isEqualTo(page);
                assertThat(actualPage.size()).isEqualTo(pageSize);
                assertThat(actualPage.total()).isEqualTo(pageSize);

                assertThat(actualExperiments).hasSize(pageSize);
            }
        }

        @Test
        void findAllAndCalculateFeedbackAvg() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiments = experimentResourceClient.generateExperimentList().stream()
                    .map(experiment -> experiment.toBuilder()
                            .feedbackScores(null)
                            .build())
                    .toList();

            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var noScoreExperiment = generateExperiment();

            createAndAssert(noScoreExperiment, apiKey, workspaceName);

            var noItemExperiment = generateExperiment();

            createAndAssert(noItemExperiment, apiKey, workspaceName);

            // Creating three traces with input, output and scores
            var trace1 = podamFactory.manufacturePojo(Trace.class);

            var trace2 = podamFactory.manufacturePojo(Trace.class);

            var trace3 = podamFactory.manufacturePojo(Trace.class);

            var trace4 = podamFactory.manufacturePojo(Trace.class);

            var trace5 = podamFactory.manufacturePojo(Trace.class);

            var trace6 = podamFactory.manufacturePojo(Trace.class);

            var traces = List.of(trace1, trace2, trace3, trace4, trace5);

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Creating 5 scores peach each of the three traces above
            var scoreForTrace1 = makeTraceScores(trace1);

            var scoreForTrace2 = makeTraceScores(trace2);

            var scoreForTrace3 = makeTraceScores(trace3);

            var scoreForTrace4 = copyScoresFrom(scoreForTrace1, trace4);

            var scoreForTrace5 = copyScoresFrom(scoreForTrace2, trace5);

            var traceIdToScoresMap = Stream
                    .of(scoreForTrace1.stream(), scoreForTrace2.stream(), scoreForTrace3.stream(),
                            scoreForTrace4.stream(), scoreForTrace5.stream())
                    .flatMap(Function.identity())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.size();
            int totalNumberOfScoresPerTrace = totalNumberOfScores / traces.size(); // This will be 3 if traces.size() == 5

            var experimentItems = IntStream.range(0, totalNumberOfScores)
                    .mapToObj(i -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experiments.get(i % totalNumberOfScoresPerTrace).id())
                            .traceId(traces.get(i % traces.size()).id())
                            .feedbackScores(
                                    traceIdToScoresMap.get(traces.get(i % traces.size()).id()).stream()
                                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                            .toList())
                            .build())
                    .toList();

            var noScoreItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(noScoreExperiment.id())
                    .traceId(trace6.id())
                    .feedbackScores(null)
                    .build();

            createAndAssert(new ExperimentItemsBatch(Set.of(noScoreItem)), apiKey, workspaceName);

            var experimentItemsBatch = addRandomExperiments(experimentItems);

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(experiments,
                    experimentItems);

            // Add comments to trace
            List<Comment> comments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(
                            trace1.id(), apiKey, workspaceName, HttpStatus.SC_CREATED))
                    .toList();

            Set<UUID> expectedExperimentIdsWithComments = getExpectedExperimentIdsWithComments(experiments,
                    experimentItems, trace1.id());

            var page = 1;
            var pageSize = experiments.size() + 2; // +2 for the noScoreExperiment and noItemExperiment

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", page)
                    .queryParam("size", pageSize)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualPage = actualResponse.readEntity(ExperimentPage.class);
                var actualExperiments = actualPage.content();

                assertThat(actualPage.page()).isEqualTo(page);
                assertThat(actualPage.size()).isEqualTo(pageSize);
                assertThat(actualPage.total()).isEqualTo(pageSize);
                assertThat(actualExperiments).hasSize(pageSize);

                for (Experiment experiment : actualExperiments) {
                    var expectedScores = expectedScoresPerExperiment.get(experiment.id());
                    var actualScores = getScoresMap(experiment);

                    assertThat(actualScores)
                            .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(StatsUtils::bigDecimalComparator,
                                            BigDecimal.class)
                                    .build())
                            .isEqualTo(expectedScores);

                    var expectedComments = expectedExperimentIdsWithComments.contains(experiment.id())
                            ? comments
                            : null;

                    CommentAssertionUtils.assertComments(expectedComments, experiment.comments());
                }
            }
        }

        @Test
        void findAllAndTraceDeleted() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .feedbackScores(null)
                    .build();

            createAndAssert(expectedExperiment, apiKey, workspaceName);

            // Creating three traces with input, output and scores
            var trace1 = podamFactory.manufacturePojo(Trace.class);

            var trace2 = podamFactory.manufacturePojo(Trace.class);

            var trace3 = podamFactory.manufacturePojo(Trace.class);

            var trace4 = podamFactory.manufacturePojo(Trace.class);

            var trace5 = podamFactory.manufacturePojo(Trace.class);

            var trace6 = podamFactory.manufacturePojo(Trace.class);

            var traces = List.of(trace1, trace2, trace3, trace4, trace5, trace6);

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Creating 5 scores peach each of the three traces above
            var scoreForTrace1 = makeTraceScores(trace1);

            var scoreForTrace2 = makeTraceScores(trace2);

            var scoreForTrace3 = makeTraceScores(trace3);

            var scoreForTrace4 = copyScoresFrom(scoreForTrace1, trace4);

            var scoreForTrace5 = copyScoresFrom(scoreForTrace2, trace5);

            var scoreForTrace6 = copyScoresFrom(scoreForTrace1, trace6);

            var traceIdToScoresMap = Stream
                    .of(scoreForTrace1.stream(), scoreForTrace2.stream(), scoreForTrace3.stream(),
                            scoreForTrace4.stream(), scoreForTrace5.stream(), scoreForTrace6.stream())
                    .flatMap(Function.identity())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.size();

            var experimentItems = IntStream.range(0, totalNumberOfScores)
                    .mapToObj(i -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(expectedExperiment.id())
                            .traceId(traces.get(i % traces.size()).id())
                            .feedbackScores(
                                    traceIdToScoresMap.get(traces.get(i % traces.size()).id()).stream()
                                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                            .toList())
                            .build())
                    .toList();

            var experimentItemsBatch = addRandomExperiments(experimentItems);

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            deleteTrace(trace6.id(), apiKey, workspaceName);

            List<ExperimentItem> experimentExpected = experimentItems
                    .stream()
                    .filter(item -> !item.traceId().equals(trace6.id()))
                    .toList();

            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(
                    List.of(expectedExperiment), experimentExpected);

            var page = 1;
            var pageSize = 1;

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", page)
                    .queryParam("size", pageSize)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualPage = actualResponse.readEntity(ExperimentPage.class);
                var actualExperiments = actualPage.content();

                assertThat(actualPage.page()).isEqualTo(page);
                assertThat(actualPage.size()).isEqualTo(pageSize);
                assertThat(actualPage.total()).isEqualTo(pageSize);
                assertThat(actualExperiments).hasSize(pageSize);

                for (Experiment experiment : actualExperiments) {
                    var expectedScores = expectedScoresPerExperiment.get(experiment.id());
                    var actualScores = getScoresMap(experiment);

                    assertThat(actualScores)
                            .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                    .build())
                            .isEqualTo(expectedScores);
                }
            }
        }

        @Test
        @DisplayName("when searching by dataset deleted when there is none, then return empty page")
        void find__whenSearchingByDatasetDeletedWhenThereIsNone__thenReturnEmptyPage() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasets = PodamFactoryUtils.manufacturePojoList(podamFactory, Dataset.class);
            datasets.forEach(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                createAndAssert(experimentResourceClient.createPartialExperiment()
                        .datasetName(dataset.name())
                        .build(), apiKey, workspaceName);
            });

            findAndAssert(workspaceName, 1, 10, null, null, List.of(), 0, List.of(), apiKey, true, Map.of(), null);
        }

        @Test
        void find__whenSearchingByDatasetDeletedAndResultHavingExperiments__thenReturnPage() {
            var workspaceName = "workspace-" + UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var datasets = new CopyOnWriteArrayList<Dataset>();
            var experiments = new CopyOnWriteArrayList<Experiment>();
            var experimentCount = 11;
            IntStream.range(0, experimentCount)
                    .parallel()
                    .forEach(i -> {
                        var dataset = podamFactory.manufacturePojo(Dataset.class);
                        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                        datasets.add(dataset);
                        var experimentId = createAndAssert(
                                experimentResourceClient.createPartialExperiment().datasetName(dataset.name()).build(),
                                apiKey,
                                workspaceName);
                        experiments.add(getExperiment(experimentId, workspaceName, apiKey).toBuilder()
                                .datasetName(null)
                                .build());
                    });

            datasetResourceClient.deleteDatasets(datasets, apiKey, workspaceName);

            experiments.sort(Comparator.comparing(Experiment::id).reversed());
            findAndAssert(
                    workspaceName,
                    1,
                    experimentCount,
                    null,
                    null,
                    experiments,
                    experimentCount,
                    List.of(),
                    apiKey,
                    true,
                    Map.of(),
                    null);
        }

        @Test
        void find__whenSearchingByDatasetDeletedHavingFeedbackScoresAndResultHavingDatasets__thenReturnPage() {
            var workspaceName = "workspace-" + UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experimentCount = 11;
            var expectedMatchCount = 5;
            var unexpectedDatasetCount = experimentCount - expectedMatchCount;

            IntStream.range(0, unexpectedDatasetCount)
                    .parallel()
                    .forEach(i -> {
                        var dataset = podamFactory.manufacturePojo(Dataset.class);
                        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                        createAndAssert(
                                experimentResourceClient.createPartialExperiment().datasetName(dataset.name()).build(),
                                apiKey,
                                workspaceName);
                    });
            var datasets = new CopyOnWriteArrayList<Dataset>();
            var experiments = new CopyOnWriteArrayList<Experiment>();
            IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .forEach(experiment -> {
                        var dataset = podamFactory.manufacturePojo(Dataset.class);
                        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                        datasets.add(dataset);
                        var expectedExperiment = createExperimentWithFeedbackScores(
                                apiKey, workspaceName, dataset.name());
                        experiments.add(expectedExperiment);
                    });

            datasetResourceClient.deleteDatasets(datasets, apiKey, workspaceName);

            experiments.sort(Comparator.comparing(Experiment::id).reversed());
            var expectedScoresPerExperiment = experiments.stream()
                    .collect(toMap(Experiment::id, experiment -> experiment.feedbackScores().stream()
                            .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))));
            findAndAssert(
                    workspaceName,
                    1,
                    expectedMatchCount,
                    null,
                    null,
                    experiments,
                    expectedMatchCount,
                    List.of(),
                    apiKey,
                    true,
                    expectedScoresPerExperiment,
                    null);
        }

        @Test
        void find__whenExperimentsHaveSpanData__thenReturnPage() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = podamFactory.manufacturePojo(Dataset.class);
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .usage(null)
                    .build();

            experimentResourceClient.create(experiment, apiKey, workspaceName);

            List<Span> spans = new ArrayList<>();
            List<Trace> traces = new ArrayList<>();

            IntStream.range(0, 5).forEach(i -> {
                var trace = podamFactory.manufacturePojo(Trace.class);
                traceResourceClient.createTrace(trace, apiKey, workspaceName);

                var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                        .traceId(trace.id())
                        .projectName(trace.projectName())
                        .type(SpanType.llm)
                        .totalEstimatedCost(BigDecimal.valueOf(PodamUtils.getIntegerInRange(0, 10)))
                        .build();

                spanResourceClient.createSpan(span, apiKey, workspaceName);

                var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experiment.id())
                        .usage(null)
                        .traceId(trace.id())
                        .feedbackScores(null)
                        .build();

                experimentResourceClient.createExperimentItem(Set.of(experimentItem), apiKey, workspaceName);

                traces.add(trace);
                spans.add(span);
            });

            List<BigDecimal> quantiles = getQuantities(traces.stream());

            var expectedExperiment = experiment.toBuilder()
                    .duration(new PercentageValues(quantiles.get(0), quantiles.get(1), quantiles.get(2)))
                    .totalEstimatedCost(getTotalEstimatedCost(spans))
                    .usage(getUsage(spans))
                    .build();

            findAndAssert(workspaceName, 1, 1, null, null, List.of(expectedExperiment), 1,
                    List.of(), apiKey, false, Map.of(), null);
        }

        private Map<String, Double> getUsage(List<Span> spans) {
            return spans.stream()
                    .map(Span::usage)
                    .map(Map::entrySet)
                    .flatMap(Collection::stream)
                    .collect(groupingBy(Map.Entry::getKey, Collectors.averagingLong(e -> e.getValue())));
        }

        private BigDecimal getTotalEstimatedCost(List<Span> spans) {

            BigDecimal accumulated = spans.stream()
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal::add)
                    .orElse(BigDecimal.ZERO);

            return accumulated.divide(BigDecimal.valueOf(spans.size()), ValidationUtils.SCALE, RoundingMode.HALF_UP);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void find__whenDatasetDeleted__shouldReturnNullDatasetIdAndName(boolean datasetDeleted) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = podamFactory.manufacturePojo(Dataset.class);
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .build();
            var expectedExperimentId = createAndAssert(experiment, apiKey, workspaceName);
            var expectedExperiment = experiment.toBuilder()
                    .datasetName(null)
                    .datasetId(null)
                    .build();
            datasetResourceClient.deleteDatasets(List.of(dataset), apiKey, workspaceName);

            getAndAssert(expectedExperimentId, expectedExperiment, workspaceName, apiKey);

            findAndAssert(workspaceName, 1, 1, null, null, List.of(expectedExperiment),
                    1, List.of(), apiKey, datasetDeleted, Map.of(), null);
        }

        private Experiment createExperimentWithFeedbackScores(String apiKey, String workspaceName, String datasetName) {
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(datasetName)
                    .build();

            createAndAssert(experiment, apiKey, workspaceName);

            var expectedExperiment = getExperiment(experiment.id(), workspaceName, apiKey).toBuilder()
                    .datasetName(null)
                    .build();

            var trace = podamFactory.manufacturePojo(Trace.class);

            var traces = List.of(trace);

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var scoreForTrace1 = makeTraceScores(trace);

            var traceIdToScoresMap = Stream
                    .of(scoreForTrace1.stream())
                    .flatMap(Function.identity())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.size();

            var experimentItems = IntStream.range(0, totalNumberOfScores)
                    .mapToObj(i -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(expectedExperiment.id())
                            .traceId(traces.getFirst().id())
                            .feedbackScores(
                                    traceIdToScoresMap.get(traces.getFirst().id()).stream()
                                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                            .toList())
                            .build())
                    .toList();

            var experimentItemsBatch = addRandomExperiments(experimentItems);

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(
                    List.of(expectedExperiment), experimentItems);

            List<BigDecimal> quantiles = getQuantities(traces.stream());

            return expectedExperiment.toBuilder()
                    .duration(new PercentageValues(quantiles.get(0), quantiles.get(1), quantiles.get(2)))
                    .feedbackScores(expectedScoresPerExperiment.get(expectedExperiment.id()).entrySet().stream()
                            .map(e -> FeedbackScoreAverage.builder()
                                    .name(e.getKey())
                                    .value(avgFromList(List.of(e.getValue())))
                                    .build())
                            .toList())
                    .build();
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when searching by prompt id and result having {} experiments, then return page")
        void find__whenSearchingByPromptIdAndResultHavingXExperiments__thenReturnPage(int experimentCount,
                int expectedMatchCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = initDataset(experimentCount, expectedMatchCount, apiKey, workspaceName);

            var prompt = podamFactory.manufacturePojo(Prompt.class);

            PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);

            List<Experiment> expectedExperiments = IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .mapToObj(i -> {
                        PromptVersionLink versionLink = buildVersionLink(promptVersion);
                        var experiment = experimentResourceClient.createPartialExperiment()
                                .datasetName(dataset.name())
                                .promptVersion(versionLink)
                                .promptVersions(List.of(versionLink))
                                .feedbackScores(null)
                                .build();

                        createAndAssert(experiment, apiKey, workspaceName);

                        return getExperiment(experiment.id(), workspaceName, apiKey);
                    })
                    .sorted(Comparator.comparing(Experiment::id).reversed())
                    .toList();

            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, Map.of(), promptVersion.promptId());
        }

        @ParameterizedTest
        @MethodSource("find__whenSearchingByPromptIdAndResultHavingXExperiments__thenReturnPage")
        @DisplayName("when searching by prompt id using new prompt version field and result having {} experiments, then return page")
        void find__whenSearchingByPromptIdUsingNewPromptVersionFieldAndResultHavingXExperiments__thenReturnPage(
                int experimentCount,
                int expectedMatchCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = initDataset(experimentCount, expectedMatchCount, apiKey, workspaceName);

            var prompt = podamFactory.manufacturePojo(Prompt.class);
            var prompt2 = podamFactory.manufacturePojo(Prompt.class);

            PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
            PromptVersion promptVersion2 = promptResourceClient.createPromptVersion(prompt2, apiKey, workspaceName);

            List<Experiment> expectedExperiments = IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .mapToObj(i -> {
                        PromptVersionLink versionLink = buildVersionLink(promptVersion);
                        PromptVersionLink versionLink2 = buildVersionLink(promptVersion2);

                        var experiment = experimentResourceClient.createPartialExperiment()
                                .datasetName(dataset.name())
                                .promptVersion(versionLink)
                                .promptVersions(List.of(versionLink, versionLink2))
                                .feedbackScores(null)
                                .build();

                        createAndAssert(experiment, apiKey, workspaceName);

                        return getExperiment(experiment.id(), workspaceName, apiKey);
                    })
                    .sorted(Comparator.comparing(Experiment::id).reversed())
                    .toList();

            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, Map.of(), promptVersion.promptId());

            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, Map.of(), promptVersion2.promptId());
        }

        Stream<Arguments> find__whenSearchingByPromptIdAndResultHavingXExperiments__thenReturnPage() {
            return Stream.of(
                    arguments(10, 0),
                    arguments(10, 5));
        }

        Stream<Arguments> whenSortingByFieldAndDirection__thenReturnPage() {
            return Stream.of(
                    arguments(
                            Comparator.comparing(Experiment::name),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(Experiment::name).reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(Experiment::createdAt)
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(Experiment::createdAt).reversed()
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(Experiment::lastUpdatedAt)
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    arguments(
                            Comparator.comparing(Experiment::lastUpdatedAt).reversed()
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()),
                    arguments(
                            Comparator.comparing(Experiment::createdBy)
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(Experiment::createdBy).reversed()
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(Experiment::traceCount)
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.TRACE_COUNT).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(Experiment::traceCount).reversed()
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.TRACE_COUNT).direction(Direction.DESC)
                                    .build()));
        }

        @ParameterizedTest
        @MethodSource("whenSortingByFieldAndDirection__thenReturnPage")
        @DisplayName("when sorting by field and direction, then return page")
        void whenSortingByFieldAndDirection__thenReturnPage(
                Comparator<Experiment> comparator, SortingField sortingField) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var scoreForTrace = PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class);
            var experiments = IntStream.range(0, 5)
                    .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build())
                    .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace))
                    .toList();

            var expectedExperiments = experiments
                    .stream()
                    .sorted(comparator)
                    .toList();
            var expectedScores = expectedExperiments
                    .stream()
                    .map(experiment -> Map.entry(experiment.id(), experiment.feedbackScores()
                            .stream()
                            .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, expectedScores, null, List.of(sortingField),
                    null, null);
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        @DisplayName("when sorting by feedback scores, then return page")
        void whenSortingByFeedbackScores__thenReturnPage(Direction direction) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var scoreForTrace = PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class);
            var experiments = IntStream.range(0, 5)
                    .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build())
                    .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace))
                    .toList();

            var sortingField = new SortingField(
                    "feedback_scores.%s".formatted(scoreForTrace.getFirst().name()),
                    direction);

            Comparator<Experiment> comparing = Comparator.comparing(
                    (Experiment experiment) -> experiment.feedbackScores()
                            .stream()
                            .filter(score -> score.name().equals(scoreForTrace.getFirst().name()))
                            .findFirst()
                            .map(FeedbackScoreAverage::value)
                            .orElse(null),
                    direction == Direction.ASC
                            ? Comparator.nullsFirst(Comparator.naturalOrder())
                            : Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(Experiment::id).reversed());

            var expectedExperiments = experiments
                    .stream()
                    .sorted(comparing)
                    .toList();

            var expectedScores = expectedExperiments
                    .stream()
                    .map(experiment -> Map.entry(experiment.id(), experiment.feedbackScores()
                            .stream()
                            .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, expectedScores, null, List.of(sortingField),
                    null, null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"feedback_scores", "feedback_score.dsfsdfd", "feedback_scores."})
        void whenSortingByFeedbackScores__thenReturnPage(String field) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            try (Response response = findExperiment(
                    workspaceName,
                    apiKey,
                    1,
                    10,
                    UUID.randomUUID(),
                    null,
                    false,
                    UUID.randomUUID(),
                    List.of(new SortingField(field, Direction.ASC)),
                    null,
                    null)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

    }

    private Experiment generateFullExperiment(
            String apiKey,
            String workspaceName,
            Experiment expectedExperiment,
            List<FeedbackScoreBatchItem> scoreForTrace) {

        createAndAssert(expectedExperiment, apiKey, workspaceName);

        int tracesNumber = PodamUtils.getIntegerInRange(1, 10);

        List<Trace> traces = IntStream.range(0, tracesNumber)
                .mapToObj(i -> podamFactory.manufacturePojo(Trace.class))
                .toList();

        traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

        Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap = new HashMap<>();
        for (Trace trace : traces) {
            List<FeedbackScoreBatchItem> scores = copyScoresFrom(scoreForTrace, trace);
            for (FeedbackScoreBatchItem item : scores) {

                if (traces.getLast().equals(trace) && scores.getFirst().equals(item)) {
                    continue;
                }

                traceIdToScoresMap.computeIfAbsent(item.id(), k -> new ArrayList<>()).add(item);
            }
        }

        var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
        feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                .scores(Stream.concat(
                        feedbackScoreBatch.scores().stream(),
                        traceIdToScoresMap.values().stream().flatMap(List::stream))
                        .toList())
                .build();

        createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

        int totalNumberOfScores = traceIdToScoresMap.size();

        var experimentItems = IntStream.range(0, totalNumberOfScores)
                .mapToObj(i -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(expectedExperiment.id())
                        .traceId(traces.get(i % traces.size()).id())
                        .feedbackScores(
                                traceIdToScoresMap.get(traces.get(i % traces.size()).id()).stream()
                                        .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                        .toList())
                        .build())
                .toList();

        var experimentItemsBatch = addRandomExperiments(experimentItems);

        createAndAssert(experimentItemsBatch, apiKey, workspaceName);

        Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(
                List.of(expectedExperiment), experimentItems);

        List<BigDecimal> quantities = getQuantities(traces.stream());

        return expectedExperiment.toBuilder()
                .traceCount((long) traces.size())
                .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                .totalEstimatedCost(null)
                .usage(null)
                .feedbackScores(
                        expectedScoresPerExperiment.get(expectedExperiment.id())
                                .entrySet()
                                .stream()
                                .map(e -> FeedbackScoreAverage.builder()
                                        .name(e.getKey())
                                        .value(avgFromList(List.of(e.getValue())))
                                        .build())
                                .toList())
                .build();
    }

    private static PromptVersionLink buildVersionLink(PromptVersion promptVersion) {
        return PromptVersionLink.builder()
                .id(promptVersion.id())
                .commit(promptVersion.commit())
                .promptId(promptVersion.promptId())
                .build();
    }

    private Dataset initDataset(int experimentCount, int expectedMatchCount, String apiKey, String workspaceName) {
        var dataset = podamFactory.manufacturePojo(Dataset.class);
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        IntStream.range(0, experimentCount - expectedMatchCount)
                .parallel()
                .forEach(i -> {
                    var experiment = experimentResourceClient.createPartialExperiment()
                            .datasetName(dataset.name())
                            .build();

                    createAndAssert(experiment, apiKey, workspaceName);
                });
        return dataset;
    }

    private void deleteTrace(UUID id, String apiKey, String workspaceName) {
        traceResourceClient.deleteTrace(id, workspaceName, apiKey);
    }

    private Map<UUID, Map<String, BigDecimal>> getExpectedScoresPerExperiment(
            List<Experiment> experiments, List<ExperimentItem> experimentItems) {
        return experiments.stream()
                .map(experiment -> Map.entry(experiment.id(), experimentItems
                        .stream()
                        .filter(item -> item.experimentId().equals(experiment.id()))
                        .map(ExperimentItem::feedbackScores)
                        .flatMap(Collection::stream)
                        .collect(groupingBy(
                                FeedbackScore::name,
                                mapping(FeedbackScore::value, toList())))
                        .entrySet()
                        .stream()
                        .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Set<UUID> getExpectedExperimentIdsWithComments(
            List<Experiment> experiments, List<ExperimentItem> experimentItems, UUID traceId) {
        return experiments.stream()
                .map(Experiment::id)
                .filter(id -> experimentItems.stream()
                        .filter(experimentItem -> experimentItem.experimentId().equals(id))
                        .map(ExperimentItem::traceId)
                        .collect(toSet())
                        .contains(traceId))
                .collect(toSet());
    }

    private void findAndAssert(
            String workspaceName,
            int page,
            int pageSize,
            UUID datasetId,
            String name,
            List<Experiment> expectedExperiments,
            long expectedTotal,
            List<Experiment> unexpectedExperiments, String apiKey,
            boolean datasetDeleted,
            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment,
            UUID promptId) {
        findAndAssert(workspaceName, page, pageSize, datasetId, name, expectedExperiments, expectedTotal,
                unexpectedExperiments, apiKey, datasetDeleted, expectedScoresPerExperiment, promptId, null, null, null);
    }

    private void findAndAssert(
            String workspaceName,
            int page,
            int pageSize,
            UUID datasetId,
            String name,
            List<Experiment> expectedExperiments,
            long expectedTotal,
            List<Experiment> unexpectedExperiments, String apiKey,
            boolean datasetDeleted,
            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment,
            UUID promptId,
            UUID optimizationId,
            Set<ExperimentType> types) {
        findAndAssert(workspaceName, page, pageSize, datasetId, name, expectedExperiments, expectedTotal,
                unexpectedExperiments, apiKey, datasetDeleted, expectedScoresPerExperiment, promptId, null,
                optimizationId, types);
    }

    public Response findExperiment(String workspaceName,
            String apiKey,
            int page,
            int pageSize,
            UUID datasetId,
            String name,
            boolean datasetDeleted,
            UUID promptId,
            List<SortingField> sortingFields,
            UUID optimizationId,
            Set<ExperimentType> types) {

        WebTarget webTarget = client.target(getExperimentsPath())
                .queryParam("page", page)
                .queryParam("name", name)
                .queryParam("dataset_deleted", datasetDeleted);

        if (pageSize > 0) {
            webTarget = webTarget.queryParam("size", pageSize);
        }

        if (datasetId != null) {
            webTarget = webTarget.queryParam("datasetId", datasetId);
        }

        if (optimizationId != null) {
            webTarget = webTarget.queryParam("optimization_id", optimizationId);
        }

        if (CollectionUtils.isNotEmpty(types)) {
            webTarget = webTarget.queryParam("types", JsonUtils.writeValueAsString(types));
        }

        if (promptId != null) {
            webTarget = webTarget.queryParam("prompt_id", promptId);
        }

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            webTarget = webTarget.queryParam("sorting", toURLEncodedQueryParam(sortingFields));
        }

        return webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    private void findAndAssert(
            String workspaceName,
            int page,
            int pageSize,
            UUID datasetId,
            String name,
            List<Experiment> expectedExperiments,
            long expectedTotal,
            List<Experiment> unexpectedExperiments,
            String apiKey,
            boolean datasetDeleted,
            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment,
            UUID promptId,
            List<SortingField> sortingFields,
            UUID optimizationId,
            Set<ExperimentType> types) {
        try (var actualResponse = findExperiment(
                workspaceName, apiKey, page, pageSize, datasetId, name, datasetDeleted, promptId, sortingFields,
                optimizationId, types)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            var actualPage = actualResponse.readEntity(ExperimentPage.class);
            assertThat(actualPage.page()).isEqualTo(page);
            assertThat(actualPage.size()).isEqualTo(expectedExperiments.size());
            assertThat(actualPage.total()).isEqualTo(expectedTotal);
            assertExperiments(
                    datasetId,
                    expectedExperiments,
                    unexpectedExperiments,
                    expectedScoresPerExperiment,
                    actualPage.content());
        }
    }

    private void assertExperiments(
            UUID datasetId,
            List<Experiment> expectedExperiments,
            List<Experiment> unexpectedExperiments,
            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment,
            List<Experiment> actualExperiments) {

        assertThat(actualExperiments).hasSize(expectedExperiments.size());

        assertThat(actualExperiments)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                .isEqualTo(expectedExperiments);

        assertIgnoredFields(actualExperiments, expectedExperiments, datasetId);

        if (!unexpectedExperiments.isEmpty()) {
            assertThat(actualExperiments)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedExperiments);
        }

        if (MapUtils.isNotEmpty(expectedScoresPerExperiment)) {
            for (Experiment experiment : actualExperiments) {
                var expectedScores = expectedScoresPerExperiment.get(experiment.id());
                var actualScores = getScoresMap(experiment);

                assertThat(actualScores)
                        .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator,
                                        BigDecimal.class)
                                .build())
                        .isEqualTo(expectedScores);
            }
        }
    }

    private void createScoreAndAssert(FeedbackScoreBatch feedbackScoreBatch) {
        createScoreAndAssert(feedbackScoreBatch, API_KEY, TEST_WORKSPACE);
    }

    private void createScoreAndAssert(FeedbackScoreBatch batch, String apiKey, String workspaceName) {
        traceResourceClient.feedbackScores(batch.scores(), apiKey, workspaceName);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamExperiments {

        @Test
        void streamWithCursorBasedPagination() {
            var datasetName1 = "dataset1-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var datasetName2 = "dataset2-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var nameCommon = RandomStringUtils.secure().nextAlphanumeric(12);
            // Creating 6 experiments with the same common name in the middle
            var experiments = IntStream.range(0, 6)
                    .mapToObj(i -> {
                        // First 3 experiments are created with datasetName1, the next 2 with datasetName2
                        var datasetName = i < 3 ? datasetName1 : datasetName2;
                        var namePrefix = RandomStringUtils.secure().nextAlphanumeric(12);
                        var nameSuffix = RandomStringUtils.secure().nextAlphanumeric(12);
                        var experiment = experimentResourceClient.createPartialExperiment()
                                .datasetName(datasetName)
                                // Using the same common name for all experiments with random prefix and suffix
                                .name("experiment-%s-%s-%s".formatted(namePrefix, nameCommon, nameSuffix))
                                .build();
                        experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);
                        return experiment;
                    })
                    .toList();

            // As experiments are returned in most recent order, the last 3 go to the first dataset
            var expectedDatasetId1 = getExperiment(experiments.getLast().id(), TEST_WORKSPACE, API_KEY).datasetId();
            var expectedDatasetId2 = getExperiment(experiments.getFirst().id(), TEST_WORKSPACE, API_KEY).datasetId();

            var expectedExperiments1 = experiments.reversed().subList(0, 3);
            var expectedExperiments2 = experiments.reversed().subList(3, experiments.size());

            // An unrelated experiment is created to ensure that the stream only returns the expected ones
            var unexpectedExperiments = List.of(generateExperiment());
            unexpectedExperiments.forEach(experiment -> experimentResourceClient.create(
                    experiment, API_KEY, TEST_WORKSPACE));

            // Search is case-insensitive and by partial name
            var name = nameCommon.toLowerCase();

            // Retrieving the first page of 3 experiments
            var request1 = ExperimentStreamRequest.builder()
                    .name(name)
                    .limit(3)
                    .build();
            var actualExperiments1 = experimentResourceClient.streamExperiments(request1, API_KEY, TEST_WORKSPACE);
            assertExperiments(
                    expectedDatasetId1, expectedExperiments1, unexpectedExperiments, null, actualExperiments1);

            // Retrieving the next page with default limit
            var request2 = ExperimentStreamRequest.builder()
                    .name(name)
                    .lastRetrievedId(actualExperiments1.getLast().id())
                    .build();
            var actualExperiments2 = experimentResourceClient.streamExperiments(request2, API_KEY, TEST_WORKSPACE);
            assertExperiments(
                    expectedDatasetId2, expectedExperiments2, unexpectedExperiments, null, actualExperiments2);

            // Retrieving the last page which should be empty
            var request3 = ExperimentStreamRequest.builder()
                    .name(name)
                    .lastRetrievedId(actualExperiments2.getLast().id())
                    .build();
            var actualExperiments3 = experimentResourceClient.streamExperiments(request3, API_KEY, TEST_WORKSPACE);
            assertThat(actualExperiments3).isEmpty();
        }

        @Test
        void streamFullExperimentWithScoresPromptVersionsAndComments() {
            var datasetName = "dataset-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var name = "experiment-" + RandomStringUtils.secure().nextAlphanumeric(36);
            // Only 2 experiments is enough for this test
            var experiments = IntStream.range(0, 2)
                    .mapToObj(i -> {
                        // Only 2 prompt versions per experiment is enough for this test
                        var promptVersions = IntStream.range(0, 2)
                                .mapToObj(j -> {
                                    var prompt = podamFactory.manufacturePojo(Prompt.class);
                                    var promptVersion = promptResourceClient.createPromptVersion(prompt, API_KEY,
                                            TEST_WORKSPACE);
                                    return buildVersionLink(promptVersion);
                                })
                                .toList();
                        var experiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
                                .datasetName(datasetName)
                                .name(name)
                                .promptVersion(promptVersions.getFirst())
                                .promptVersions(promptVersions)
                                .usage(null)
                                .duration(null)
                                .totalEstimatedCost(null)
                                .type(ExperimentType.REGULAR)
                                .optimizationId(null)
                                .build();
                        // Only 2 scores per experiment is enough for this test
                        var scores = IntStream.range(0, 2)
                                .mapToObj(j -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class))
                                .toList();
                        return generateFullExperiment(API_KEY, TEST_WORKSPACE, experiment, scores);
                    })
                    .toList();

            // Only 1 trace per experiment is enough for this test
            var traces = IntStream.range(0, 2).mapToObj(i -> podamFactory.manufacturePojo(Trace.class)).toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            // Attaching 2 comments to each trace.
            // As experiments are later returned in most recent order, the last comments go to the first trace.
            var commentsLast = IntStream.range(0, 2)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(
                            traces.getFirst().id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED))
                    .toList();
            var commentsFirst = IntStream.range(0, 2)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(
                            traces.getLast().id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED))
                    .toList();

            // Linking an experiment item to each trace and experiment
            var experimentItemFirst = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiments.getFirst().id())
                    .traceId(traces.getFirst().id())
                    .build();
            var experimentItemLast = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiments.getLast().id())
                    .traceId(traces.getLast().id())
                    .build();
            experimentResourceClient.createExperimentItem(
                    Set.of(experimentItemFirst, experimentItemLast), API_KEY, TEST_WORKSPACE);

            // Determining the expected datasetId
            var expectedDatasetId = getExperiment(experiments.getFirst().id(), TEST_WORKSPACE, API_KEY).datasetId();

            var expectedExperiments = experiments.reversed().stream()
                    .map(experiment -> {
                        List<UUID> traceIds = experimentResourceClient
                                .getExperimentItems(experiment.name(), API_KEY, TEST_WORKSPACE)
                                .stream()
                                .map(ExperimentItem::traceId)
                                .distinct()
                                .toList();

                        List<BigDecimal> quantities = getQuantities(traceIds
                                .parallelStream()
                                .map(traceId -> traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY))
                                .sequential());

                        return experiment.toBuilder()
                                .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                                .build();
                    })
                    .toList();

            // An unrelated experiment is created to ensure that the stream only returns the expected ones
            var unexpectedExperiments = List.of(generateExperiment());
            unexpectedExperiments.forEach(experiment -> experimentResourceClient.create(
                    experiment, API_KEY, TEST_WORKSPACE));

            // The expected scores are calculated for each experiment
            var expectedScores = expectedExperiments.stream()
                    .map(experiment -> Map.entry(experiment.id(), experiment.feedbackScores()
                            .stream()
                            .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            var request = ExperimentStreamRequest.builder().name(name).build();
            var actualExperiments = experimentResourceClient.streamExperiments(request, API_KEY, TEST_WORKSPACE);

            assertExperiments(
                    expectedDatasetId, expectedExperiments, unexpectedExperiments, expectedScores, actualExperiments);
            CommentAssertionUtils.assertComments(commentsFirst, actualExperiments.getFirst().comments());
            CommentAssertionUtils.assertComments(commentsLast, actualExperiments.getLast().comments());
        }

        @Test
        void streamHandlesDatasetDeletion() {
            var name = "experiment-" + RandomStringUtils.secure().nextAlphanumeric(36);
            // Creating 2 experiments with the same name
            var experiments = IntStream.range(0, 2)
                    .mapToObj(i -> {
                        // Each experiment is created with a different dataset name
                        var datasetName = "dataset-" + RandomStringUtils.secure().nextAlphanumeric(36);
                        var experiment = experimentResourceClient.createPartialExperiment()
                                .datasetName(datasetName)
                                .name(name)
                                .build();
                        experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);
                        return experiment;
                    })
                    .toList();

            // Resolving the datasetId of the first experiment, in order to delete it later
            var datasetIdToDelete = getExperiment(experiments.getFirst().id(), TEST_WORKSPACE, API_KEY).datasetId();

            // Experiments are returned in most recent order.
            var expectedExperiments = List.of(
                    experiments.getLast(), experiments.getFirst().toBuilder().datasetName(null).build());

            // An unrelated experiment is created to ensure that the stream only returns the expected ones
            var unexpectedExperiments = List.of(generateExperiment());
            unexpectedExperiments.forEach(experiment -> experimentResourceClient.create(
                    experiment, API_KEY, TEST_WORKSPACE));

            // Deleting the dataset of the first experiment
            var datasets = List.of(Dataset.builder().id(datasetIdToDelete).build());
            datasetResourceClient.deleteDatasets(datasets, API_KEY, TEST_WORKSPACE);

            var request = ExperimentStreamRequest.builder().name(name).build();
            var actualExperiments = experimentResourceClient.streamExperiments(request, API_KEY, TEST_WORKSPACE);

            assertExperiments(
                    null, expectedExperiments, unexpectedExperiments, null, actualExperiments);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateAndGetExperiments {

        @Test
        void createAndGet() {
            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .traceCount(3L)
                    .build();

            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            // Creating three traces with input, output and scores
            var trace1 = podamFactory.manufacturePojo(Trace.class);

            var trace2 = podamFactory.manufacturePojo(Trace.class);

            var trace3 = podamFactory.manufacturePojo(Trace.class);

            var traces = List.of(trace1, trace2, trace3);

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            // Creating 5 scores peach each of the three traces above
            var scoreForTrace1 = makeTraceScores(trace1);

            var scoreForTrace2 = makeTraceScores(trace2);

            var scoreForTrace3 = makeTraceScores(trace3);

            var traceIdToScoresMap = Stream
                    .concat(Stream.concat(scoreForTrace1.stream(), scoreForTrace2.stream()), scoreForTrace3.stream())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch);

            int totalNumberOfScores = 15;
            int totalNumberOfScoresPerTrace = 5;

            var experimentItems = assignScoresAndTracesToExperimentItems(totalNumberOfScores,
                    totalNumberOfScoresPerTrace, expectedExperiment, traces, traceIdToScoresMap);

            var experimentItemsBatch = addRandomExperiments(experimentItems);

            Map<String, BigDecimal> expectedScores = getExpectedScores(traceIdToScoresMap);

            createAndAssert(experimentItemsBatch, API_KEY, TEST_WORKSPACE);

            List<BigDecimal> quantities = getQuantities(Stream.of(trace1, trace2, trace3));

            expectedExperiment = expectedExperiment.toBuilder()
                    .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                    .build();

            Experiment experiment = getAndAssert(expectedExperiment.id(), expectedExperiment, TEST_WORKSPACE, API_KEY);

            assertThat(experiment.traceCount()).isEqualTo(expectedExperiment.traceCount());
            assertThat(experiment.feedbackScores()).hasSize(totalNumberOfScores);

            Map<String, BigDecimal> actualScores = getScoresMap(experiment);

            assertThat(actualScores)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedScores);

        }

        @Test
        void createAndGetFeedbackAvgAndComments() {
            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .traceCount(3L)
                    .build();

            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            // Creating three traces with input, output, and scores
            var trace1 = podamFactory.manufacturePojo(Trace.class);

            var trace2 = podamFactory.manufacturePojo(Trace.class);

            var trace3 = podamFactory.manufacturePojo(Trace.class);

            var traces = List.of(trace1, trace2, trace3);

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            // Creating 5 scores peach each of the three traces above
            var scoreForTrace1 = makeTraceScores(trace1);

            // copying the scores for trace 1 to trace
            var scoreForTrace2 = copyScoresFrom(scoreForTrace1, trace2);

            var scoreForTrace3 = copyScoresFrom(scoreForTrace1, trace3);

            var traceIdToScoresMap = Stream
                    .concat(Stream.concat(scoreForTrace1.stream(), scoreForTrace2.stream()), scoreForTrace3.stream())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch);

            // Add comments to trace
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(trace1.id(), API_KEY, TEST_WORKSPACE,
                            HttpStatus.SC_CREATED))
                    .toList();

            int totalNumberOfScores = 15;
            int totalNumberOfScoresPerTrace = 5;

            List<BigDecimal> quantities = getQuantities(Stream.of(trace1, trace2, trace3));

            expectedExperiment = expectedExperiment.toBuilder()
                    .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                    .build();

            var experimentItems = assignScoresAndTracesToExperimentItems(totalNumberOfScores,
                    totalNumberOfScoresPerTrace, expectedExperiment, traces, traceIdToScoresMap);

            // When storing the experiment items in batch, adding some more unrelated random ones
            var experimentItemsBatch = addRandomExperiments(experimentItems);

            // Calculating expected scores average
            Map<String, BigDecimal> expectedScores = getExpectedScores(traceIdToScoresMap);

            createAndAssert(experimentItemsBatch, API_KEY, TEST_WORKSPACE);

            Experiment experiment = getAndAssert(expectedExperiment.id(), expectedExperiment, TEST_WORKSPACE, API_KEY);

            assertThat(experiment.traceCount()).isEqualTo(expectedExperiment.traceCount());

            Map<String, BigDecimal> actual = getScoresMap(experiment);

            assertThat(actual)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedScores);

            CommentAssertionUtils.assertComments(expectedComments, experiment.comments());
        }

        @Test
        void createExperimentWithPromptVersionLink() {

            String promptName = RandomStringUtils.secure().nextAlphanumeric(10);
            Prompt prompt = Prompt.builder()
                    .name(promptName)
                    .build();

            var promptVersion = promptResourceClient.createPromptVersion(prompt, API_KEY, TEST_WORKSPACE);

            var versionLink = new PromptVersionLink(promptVersion.id(), promptVersion.commit(),
                    promptVersion.promptId());

            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
                    .promptVersion(versionLink)
                    .promptVersions(List.of(versionLink))
                    .duration(null)
                    .usage(null)
                    .totalEstimatedCost(null)
                    .type(ExperimentType.REGULAR)
                    .optimizationId(null)
                    .build();

            var expectedId = createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void createWithoutOptionalFieldsAndGet(String name) {
            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .id(null)
                    .name(name)
                    .metadata(null)
                    .build();
            var expectedId = createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @ParameterizedTest
        @MethodSource
        void createWithOptimizationIdTypeAndGet(ExperimentType type) {
            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .type(type)
                    .optimizationId(UUID.randomUUID())
                    .build();
            var expectedId = createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        Stream<ExperimentType> createWithOptimizationIdTypeAndGet() {
            return Stream.of(
                    ExperimentType.REGULAR,
                    ExperimentType.TRIAL,
                    ExperimentType.MINI_BATCH);
        }

        @Test
        void createWithSameIdIsIdempotent() {
            var expectedExperiment = generateExperiment();
            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            var unexpectedExperiment = experimentResourceClient.createPartialExperiment()
                    .id(expectedExperiment.id())
                    .build();
            var actualId = experimentResourceClient.create(unexpectedExperiment, API_KEY, TEST_WORKSPACE);

            // The event isn't posted when the experiment already exists.
            Mockito.verifyNoMoreInteractions(defaultEventBus);
            assertThat(actualId).isEqualTo(expectedExperiment.id());
            var actualExperiment = getAndAssert(expectedExperiment.id(), expectedExperiment, TEST_WORKSPACE, API_KEY);
            assertThat(actualExperiment)
                    .usingRecursiveComparison()
                    .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .isNotEqualTo(unexpectedExperiment);
        }

        @Test
        void createWithInvalidPromptVersionId() {
            var experiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
                    .promptVersion(new PromptVersionLink(GENERATOR.generate(), null, GENERATOR.generate()))
                    .build();

            var expectedError = new ErrorMessage(HttpStatus.SC_CONFLICT, "Prompt version not found");

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(experiment))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                var actualError = actualResponse.readEntity(ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @Test
        void createInvalidId() {
            var experiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
                    .id(UUID.randomUUID())
                    .build();
            var expectedError = new com.comet.opik.api.error.ErrorMessage(
                    List.of("Experiment id must be a version 7 UUID"));

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(experiment))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @Test
        void getNotFound() {
            UUID id = GENERATOR.generate();
            var expectedError = new ErrorMessage(HttpStatus.SC_NOT_FOUND,
                    "Not found experiment with id '%s'".formatted(id));
            try (var actualResponse = client.target(getExperimentsPath())
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);

                var actualError = actualResponse.readEntity(ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @Test
        void createAndGetWithDeletedTrace() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiment = experimentResourceClient.createPartialExperiment()
                    .feedbackScores(null)
                    .build();

            createAndAssert(experiment, apiKey, workspaceName);

            // Creating three traces with input, output and scores
            var trace1 = podamFactory.manufacturePojo(Trace.class);

            var trace2 = podamFactory.manufacturePojo(Trace.class);

            var trace3 = podamFactory.manufacturePojo(Trace.class);

            var trace4 = podamFactory.manufacturePojo(Trace.class);

            var trace5 = podamFactory.manufacturePojo(Trace.class);

            var trace6 = podamFactory.manufacturePojo(Trace.class);

            var traces = List.of(trace1, trace2, trace3, trace4, trace5, trace6);

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Creating 5 scores peach each of the three traces above
            var scoreForTrace1 = makeTraceScores(trace1);

            var scoreForTrace2 = makeTraceScores(trace2);

            var scoreForTrace3 = makeTraceScores(trace3);

            var scoreForTrace4 = copyScoresFrom(scoreForTrace1, trace4);

            var scoreForTrace5 = copyScoresFrom(scoreForTrace2, trace5);

            var scoreForTrace6 = copyScoresFrom(scoreForTrace1, trace6);

            var traceIdToScoresMap = Stream
                    .of(scoreForTrace1.stream(), scoreForTrace2.stream(), scoreForTrace3.stream(),
                            scoreForTrace4.stream(), scoreForTrace5.stream(), scoreForTrace6.stream())
                    .flatMap(Function.identity())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.size();

            var experimentItems = IntStream.range(0, totalNumberOfScores)
                    .mapToObj(i -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experiment.id())
                            .traceId(traces.get(i % traces.size()).id())
                            .feedbackScores(
                                    traceIdToScoresMap.get(traces.get(i % traces.size()).id()).stream()
                                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                            .toList())
                            .build())
                    .toList();

            var experimentItemsBatch = addRandomExperiments(experimentItems);

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            deleteTrace(trace6.id(), apiKey, workspaceName);

            List<BigDecimal> quantities = getQuantities(Stream.of(trace1, trace2, trace3, trace4, trace5));

            var expectedExperiment = experiment.toBuilder()
                    .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                    .build();

            Map<String, BigDecimal> expectedScores = getExpectedScores(
                    traceIdToScoresMap.entrySet()
                            .stream()
                            .filter(e -> !e.getKey().equals(trace6.id()))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));

            Experiment actualExperiment = getAndAssert(experiment.id(), expectedExperiment, workspaceName, apiKey);

            assertThat(actualExperiment.traceCount()).isEqualTo(traces.size()); // decide if we should count deleted traces

            Map<String, BigDecimal> actual = getScoresMap(actualExperiment);

            assertThat(actual)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedScores);
        }

        @Test
        void getExperimentById__whenDatasetDeleted__shouldReturnNullDatasetIdAndName() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = podamFactory.manufacturePojo(Dataset.class);
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
            var experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .build();
            var expectedExperimentId = createAndAssert(experiment, apiKey, workspaceName);
            var expectedExperiment = experiment.toBuilder()
                    .datasetName(null)
                    .datasetId(null)
                    .build();
            datasetResourceClient.deleteDatasets(List.of(dataset), apiKey, workspaceName);

            getAndAssert(expectedExperimentId, expectedExperiment, workspaceName, apiKey);
        }
    }

    private static List<BigDecimal> getQuantities(Stream<Trace> traces) {
        return StatsUtils.calculateQuantiles(
                traces.filter(entity -> entity.endTime() != null)
                        .map(entity -> entity.startTime().until(entity.endTime(), ChronoUnit.MICROS))
                        .map(duration -> duration / 1_000.0)
                        .toList(),
                List.of(0.50, 0.90, 0.99));
    }

    private ExperimentItemsBatch addRandomExperiments(List<ExperimentItem> experimentItems) {
        // When storing the experiment items in batch, adding some more unrelated random ones
        var experimentItemsBatch = podamFactory.manufacturePojo(ExperimentItemsBatch.class);
        experimentItemsBatch = experimentItemsBatch.toBuilder()
                .experimentItems(Stream.concat(
                        experimentItemsBatch.experimentItems().stream(),
                        experimentItems.stream())
                        .collect(toUnmodifiableSet()))
                .build();
        return experimentItemsBatch;
    }

    private Map<String, BigDecimal> getScoresMap(Experiment experiment) {
        List<FeedbackScoreAverage> feedbackScores = experiment.feedbackScores();
        if (feedbackScores != null) {
            return feedbackScores
                    .stream()
                    .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value));
        }
        return null;
    }

    private Map<String, BigDecimal> getExpectedScores(Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap) {
        return traceIdToScoresMap
                .values()
                .stream()
                .flatMap(Collection::stream)
                .collect(groupingBy(
                        FeedbackScoreBatchItem::name,
                        mapping(FeedbackScoreBatchItem::value, toList())))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private BigDecimal avgFromList(List<BigDecimal> values) {
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_EVEN);
    }

    private List<ExperimentItem> assignScoresAndTracesToExperimentItems(
            int totalNumberOfScores, int totalNumberOfScoresPerTrace, Experiment expectedExperiment, List<Trace> traces,
            Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap) {

        return IntStream.range(0, totalNumberOfScores)
                .mapToObj(i -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(expectedExperiment.id())
                        .traceId(traces.get(i / totalNumberOfScoresPerTrace).id())
                        .feedbackScores(
                                traceIdToScoresMap.get(traces.get(i / totalNumberOfScoresPerTrace).id()).stream()
                                        .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                        .toList())
                        .build())
                .toList();
    }

    private List<FeedbackScoreBatchItem> copyScoresFrom(List<FeedbackScoreBatchItem> scoreForTrace, Trace trace) {
        return scoreForTrace
                .stream()
                .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                        .id(trace.id())
                        .projectName(trace.projectName())
                        .value(podamFactory.manufacturePojo(BigDecimal.class).abs())
                        .build())
                .toList();
    }

    private List<FeedbackScoreBatchItem> makeTraceScores(Trace trace) {
        return copyScoresFrom(
                PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class),
                trace);
    }

    private synchronized UUID createAndAssert(Experiment expectedExperiment, String apiKey, String workspaceName) {
        Mockito.reset(defaultEventBus);
        try (var actualResponse = client.target(getExperimentsPath())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(expectedExperiment))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);

            var actualId = getIdFromLocation(actualResponse.getLocation());

            assertThat(actualResponse.hasEntity()).isFalse();

            if (expectedExperiment.id() != null) {
                assertThat(actualId).isEqualTo(expectedExperiment.id());
            } else {
                assertThat(actualId).isNotNull();
            }

            Experiment actualExperiment = getAndAssert(actualId, expectedExperiment, workspaceName, apiKey);

            ArgumentCaptor<ExperimentCreated> experimentCaptor = ArgumentCaptor.forClass(ExperimentCreated.class);
            Mockito.verify(defaultEventBus).post(experimentCaptor.capture());

            assertThat(experimentCaptor.getValue().experimentId()).isEqualTo(actualId);
            assertThat(experimentCaptor.getValue().datasetId()).isEqualTo(actualExperiment.datasetId());
            assertThat(experimentCaptor.getValue().createdAt())
                    .isCloseTo(actualExperiment.createdAt(), within(2, ChronoUnit.SECONDS));

            return actualId;
        }
    }

    private Experiment getExperiment(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(getExperimentsPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            if (actualResponse.getStatus() == HttpStatus.SC_NOT_FOUND) {
                return null;
            }

            assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return actualResponse.readEntity(Experiment.class);
        }
    }

    private Experiment getAndAssert(UUID id, Experiment expectedExperiment, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(getExperimentsPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            var actualExperiment = actualResponse.readEntity(Experiment.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(actualExperiment)
                    .usingRecursiveComparison()
                    .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(expectedExperiment);

            if (expectedExperiment.promptVersion() != null) {
                assertThat(actualExperiment.promptVersion()).isEqualTo(expectedExperiment.promptVersion());
            }

            if (expectedExperiment.promptVersions() != null) {
                assertThat(actualExperiment.promptVersions())
                        .usingRecursiveComparison()
                        .ignoringCollectionOrder()
                        .isEqualTo(expectedExperiment.promptVersions());
            }

            UUID expectedDatasetId = null;
            assertIgnoredFields(actualExperiment, expectedExperiment.toBuilder().id(id).build(), expectedDatasetId);

            return actualExperiment;
        }
    }

    private void assertIgnoredFields(
            List<Experiment> actualExperiments, List<Experiment> expectedExperiments, UUID expectedDatasetId) {
        for (int i = 0; i < actualExperiments.size(); i++) {
            var actualExperiment = actualExperiments.get(i);
            var expectedExperiment = expectedExperiments.get(i);
            assertIgnoredFields(actualExperiment, expectedExperiment, expectedDatasetId);
        }
    }

    private void assertIgnoredFields(
            Experiment actualExperiment, Experiment expectedExperiment, UUID expectedDatasetId) {
        assertThat(actualExperiment.id()).isEqualTo(expectedExperiment.id());
        if (null != expectedDatasetId) {
            assertThat(actualExperiment.datasetId()).isEqualTo(expectedDatasetId);
        } else {
            assertThat(actualExperiment.datasetId()).isNotNull();
        }
        if (StringUtils.isNotBlank(expectedExperiment.name())) {
            assertThat(actualExperiment.name()).isEqualTo(expectedExperiment.name());
        } else {
            assertThat(actualExperiment.name()).matches("[a-zA-Z]+_[a-zA-Z]+_\\d+");
        }
        assertThat(actualExperiment.traceCount()).isNotNull();

        assertThat(actualExperiment.createdAt()).isBetween(expectedExperiment.createdAt(), Instant.now());
        assertThat(actualExperiment.lastUpdatedAt()).isBetween(expectedExperiment.createdAt(), Instant.now());
        assertThat(actualExperiment.createdBy()).isEqualTo(USER);
        assertThat(actualExperiment.lastUpdatedBy()).isEqualTo(USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetExperimentItems {

        @Test
        void getById() {
            var id = GENERATOR.generate();
            getAndAssertNotFound(id, API_KEY, TEST_WORKSPACE);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteExperimentItems {

        @Test
        @DisplayName("Success")
        void delete() {
            var createRequest = getExperimentItemsBatch();
            createAndAssert(createRequest, API_KEY, TEST_WORKSPACE);
            createRequest.experimentItems().forEach(item -> getAndAssert(item, TEST_WORKSPACE, API_KEY));

            var ids = createRequest.experimentItems().stream().map(ExperimentItem::id).collect(toSet());
            var deleteRequest = ExperimentItemsDelete.builder().ids(ids).build();
            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(deleteRequest))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            ids.forEach(id -> getAndAssertNotFound(id, API_KEY, TEST_WORKSPACE));
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteExperiments {

        private void deleteExperimentAndAssert(Set<UUID> ids, String apiKey, String workspaceName) {

            Set<UUID> datasetIds = ids.parallelStream()
                    .map(id -> getExperiment(id, workspaceName, apiKey))
                    .filter(Objects::nonNull)
                    .map(Experiment::datasetId)
                    .collect(toSet());

            Mockito.reset(defaultEventBus);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new DeleteIdsHolder(ids)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);

                if (datasetIds.isEmpty()) {

                    ArgumentCaptor<ExperimentsDeleted> experimentCaptor = ArgumentCaptor
                            .forClass(ExperimentsDeleted.class);
                    Mockito.verify(defaultEventBus).post(experimentCaptor.capture());

                    assertThat(experimentCaptor.getValue().datasetInfo().stream().map(DatasetEventInfoHolder::datasetId)
                            .collect(toSet())).isEqualTo(datasetIds);
                }
            }

            ids.parallelStream().forEach(id -> getExperimentAndAssertNotFound(id, apiKey, workspaceName));
        }

        private void getExperimentAndAssertNotFound(UUID id, String apiKey, String workspaceName) {
            try (var actualResponse = client.target(getExperimentsPath())
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        void deleteExperimentsById__whenExperimentDoesNotExist__thenReturnNoContent() {
            var experiment = generateExperiment();

            getExperimentAndAssertNotFound(experiment.id(), API_KEY, TEST_WORKSPACE);

            deleteExperimentAndAssert(Set.of(experiment.id()), API_KEY, TEST_WORKSPACE);
        }

        @Test
        void deleteExperimentsById__whenExperimentHasItems__thenReturnNoContent() {
            var experiment = generateExperiment();

            createAndAssert(experiment, API_KEY, TEST_WORKSPACE);

            getAndAssert(experiment.id(), experiment, TEST_WORKSPACE, API_KEY);

            var experimentItems = PodamFactoryUtils.manufacturePojoList(podamFactory, ExperimentItem.class).stream()
                    .map(experimentItem -> experimentItem.toBuilder().experimentId(experiment.id()).build())
                    .collect(toUnmodifiableSet());

            var createRequest = ExperimentItemsBatch.builder().experimentItems(experimentItems).build();

            createAndAssert(createRequest, API_KEY, TEST_WORKSPACE);

            deleteExperimentAndAssert(Set.of(experiment.id()), API_KEY, TEST_WORKSPACE);

            experimentItems
                    .parallelStream()
                    .forEach(experimentItem -> getAndAssertNotFound(experimentItem.id(), API_KEY, TEST_WORKSPACE));
        }

        @Test
        void deleteExperimentsById__whenDeletingMultipleExperiments__thenReturnNoContent() {
            var experiments = experimentResourceClient.generateExperimentList();

            experiments.forEach(experiment -> createAndAssert(experiment, API_KEY, TEST_WORKSPACE));
            experiments.parallelStream()
                    .forEach(experiment -> getAndAssert(experiment.id(), experiment, TEST_WORKSPACE, API_KEY));

            Set<UUID> ids = experiments.stream().map(Experiment::id).collect(toSet());

            deleteExperimentAndAssert(ids, API_KEY, TEST_WORKSPACE);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamExperimentItems {

        @Test
        void streamByExperimentName() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Creating two traces with input, output and scores
            var traceWithScores1 = createTraceWithScores(apiKey, workspaceName);
            var traceWithScores2 = createTraceWithScores(apiKey, workspaceName);

            var traceIdToScoresMap = Stream
                    .concat(traceWithScores1.getRight().stream(), traceWithScores2.getRight().stream())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream)).toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            // Add comments to trace
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(traceWithScores2.getKey().id(), apiKey,
                            workspaceName, HttpStatus.SC_CREATED))
                    .toList();

            var experiment1 = generateExperiment();

            createAndAssert(experiment1, apiKey, workspaceName);

            var experiment2 = experimentResourceClient.createPartialExperiment()
                    .name(experiment1.name().substring(1, experiment1.name().length() - 1).toLowerCase())
                    .build();
            createAndAssert(experiment2, apiKey, workspaceName);

            var experiment3 = generateExperiment();

            createAndAssert(experiment3, apiKey, workspaceName);

            var experimentItems1 = PodamFactoryUtils.manufacturePojoList(podamFactory, ExperimentItem.class).stream()
                    .map(experimentItem -> experimentItem.toBuilder()
                            .experimentId(experiment1.id())
                            .traceId(traceWithScores1.getLeft().id())
                            .totalEstimatedCost(null)
                            .usage(null)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traceWithScores1.getLeft().startTime(), traceWithScores1.getLeft().endTime()))
                            .build())
                    .collect(toUnmodifiableSet());
            var createRequest1 = ExperimentItemsBatch.builder().experimentItems(experimentItems1).build();
            createAndAssert(createRequest1, apiKey, workspaceName);

            var experimentItems2 = PodamFactoryUtils.manufacturePojoList(podamFactory, ExperimentItem.class).stream()
                    .map(experimentItem -> experimentItem.toBuilder().experimentId(experiment2.id())
                            .traceId(traceWithScores2.getLeft().id())
                            .totalEstimatedCost(null)
                            .usage(null)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traceWithScores1.getLeft().startTime(), traceWithScores1.getLeft().endTime()))
                            .build())
                    .collect(toUnmodifiableSet());
            var createRequest2 = ExperimentItemsBatch.builder().experimentItems(experimentItems2).build();
            createAndAssert(createRequest2, apiKey, workspaceName);

            var experimentItems3 = PodamFactoryUtils.manufacturePojoList(podamFactory, ExperimentItem.class).stream()
                    .map(experimentItem -> experimentItem.toBuilder().experimentId(experiment3.id())
                            .totalEstimatedCost(null)
                            .usage(null)
                            .duration(null)
                            .build())
                    .collect(toUnmodifiableSet());
            var createRequest3 = ExperimentItemsBatch.builder().experimentItems(experimentItems3).build();
            createAndAssert(createRequest3, apiKey, workspaceName);

            var size = experimentItems1.size() + experimentItems2.size();
            var limit = size / 2;

            var expectedExperimentItems = Stream.concat(experimentItems1.stream(), experimentItems2.stream())
                    .sorted(Comparator.comparing(ExperimentItem::experimentId).thenComparing(ExperimentItem::id))
                    .toList()
                    .reversed();

            var expectedExperimentItems1 = expectedExperimentItems.subList(0, limit).stream()
                    .map(experimentItem -> experimentItem.toBuilder()
                            .input(traceWithScores2.getLeft().input())
                            .output(traceWithScores2.getLeft().output())
                            .feedbackScores(traceWithScores2.getRight().stream()
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore).toList())
                            .comments(expectedComments)
                            .totalEstimatedCost(null)
                            .usage(null)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traceWithScores2.getLeft().startTime(), traceWithScores2.getLeft().endTime()))
                            .build())
                    .toList();
            var expectedExperimentItems2 = expectedExperimentItems.subList(limit, size).stream()
                    .map(experimentItem -> experimentItem.toBuilder()
                            .input(traceWithScores1.getLeft().input())
                            .output(traceWithScores1.getLeft().output())
                            .feedbackScores(traceWithScores1.getRight().stream()
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore).toList())
                            .comments(null)
                            .totalEstimatedCost(null)
                            .usage(null)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traceWithScores1.getLeft().startTime(), traceWithScores1.getLeft().endTime()))
                            .build())
                    .toList();

            var streamRequest1 = ExperimentItemStreamRequest.builder()
                    .experimentName(experiment2.name())
                    .limit(limit)
                    .build();
            var unexpectedExperimentItems1 = Stream.concat(expectedExperimentItems2.stream(), experimentItems3.stream())
                    .toList();
            streamAndAssert(
                    streamRequest1, expectedExperimentItems1, unexpectedExperimentItems1, apiKey, workspaceName);

            var streamRequest2 = ExperimentItemStreamRequest.builder()
                    .experimentName(experiment2.name())
                    .lastRetrievedId(expectedExperimentItems1.getLast().id())
                    .build();
            var unexpectedExperimentItems2 = Stream.concat(expectedExperimentItems1.stream(), experimentItems3.stream())
                    .toList();
            streamAndAssert(
                    streamRequest2, expectedExperimentItems2, unexpectedExperimentItems2, apiKey, workspaceName);
        }

        @Test
        void streamByExperimentNameWithNoItems() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiment = generateExperiment();

            createAndAssert(experiment, apiKey, workspaceName);

            var streamRequest = ExperimentItemStreamRequest.builder().experimentName(experiment.name()).build();
            var expectedExperimentItems = List.<ExperimentItem>of();
            var unexpectedExperimentItems1 = List.<ExperimentItem>of();
            streamAndAssert(streamRequest, expectedExperimentItems, unexpectedExperimentItems1, apiKey, workspaceName);
        }

        @Test
        void streamByExperimentNameWithoutExperiments() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var streamRequest = ExperimentItemStreamRequest.builder()
                    .experimentName(RandomStringUtils.secure().nextAlphanumeric(10))
                    .build();
            var expectedExperimentItems = List.<ExperimentItem>of();
            var unexpectedExperimentItems1 = List.<ExperimentItem>of();
            streamAndAssert(streamRequest, expectedExperimentItems, unexpectedExperimentItems1, apiKey, workspaceName);
        }

        @Test
        void streamByExperimentNameWithSpanData() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Creating two traces with input, output and scores
            var traceWithScores1 = createTraceWithScores(apiKey, workspaceName);
            var traceWithScores2 = createTraceWithScores(apiKey, workspaceName);

            Trace trace1 = traceWithScores1.getLeft();
            Trace trace2 = traceWithScores2.getLeft();

            // Creating Span Data
            var span1 = createSpan(trace1, apiKey, workspaceName);
            var span2 = createSpan(trace2, apiKey, workspaceName);

            var span3 = createSpan(trace1, apiKey, workspaceName);
            var span4 = createSpan(trace2, apiKey, workspaceName);

            // Streaming by experiment name

            var experiment = experimentResourceClient.createPartialExperiment().build();

            createAndAssert(experiment, apiKey, workspaceName);

            var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiment.id())
                    .traceId(trace1.id())
                    .totalEstimatedCost(getTotalEstimatedCost(List.of(span1, span3)))
                    .usage(getUsage(List.of(span1, span3)))
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace1.startTime(),
                            trace1.endTime()))
                    .output(trace1.output())
                    .input(trace1.input())
                    .comments(null)
                    .feedbackScores(null)
                    .build();

            var experimentItem2 = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiment.id())
                    .traceId(trace2.id())
                    .totalEstimatedCost(getTotalEstimatedCost(List.of(span2, span4)))
                    .usage(getUsage(List.of(span2, span4)))
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace2.startTime(),
                            trace2.endTime()))
                    .output(trace2.output())
                    .input(trace2.input())
                    .comments(null)
                    .feedbackScores(null)
                    .build();

            var createRequest1 = ExperimentItemsBatch.builder().experimentItems(Set.of(experimentItem, experimentItem2))
                    .build();

            createAndAssert(createRequest1, apiKey, workspaceName);

            var streamRequest = ExperimentItemStreamRequest.builder()
                    .experimentName(experiment.name())
                    .build();
            var expectedExperimentItems = List.of(experimentItem2, experimentItem);
            var unexpectedExperimentItems1 = List.<ExperimentItem>of();
            streamAndAssert(streamRequest, expectedExperimentItems, unexpectedExperimentItems1, apiKey, workspaceName);
        }

        private static Map<String, Long> getUsage(List<Span> spans) {
            return StatsUtils.calculateUsage(
                    spans.stream()
                            .map(it -> it.usage().entrySet()
                                    .stream()
                                    .collect(Collectors.toMap(
                                            Map.Entry::getKey,
                                            entry -> entry.getValue().longValue())))
                            .toList());
        }

        private BigDecimal getTotalEstimatedCost(List<Span> spans) {
            return spans.stream()
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal::add)
                    .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                    .orElse(null);
        }

        private Span createSpan(Trace trace, String apiKey, String workspaceName) {
            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(trace.projectName())
                    .traceId(trace.id())
                    .totalEstimatedCost(BigDecimal.valueOf(PodamUtils.getIntegerInRange(0, 10)))
                    .feedbackScores(null)
                    .totalEstimatedCostVersion(null)
                    .type(SpanType.llm)
                    .build();

            spanResourceClient.createSpan(span, apiKey, workspaceName);

            return span;
        }

        private Pair<Trace, List<FeedbackScoreBatchItem>> createTraceWithScores(String apiKey, String workspaceName) {
            var trace = podamFactory.manufacturePojo(Trace.class);
            traceResourceClient.createTrace(trace, apiKey, workspaceName);

            // Creating 5 scores peach each of the two traces above
            return Pair.of(trace, PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                            .id(trace.id())
                            .projectName(trace.projectName())
                            .value(podamFactory.manufacturePojo(BigDecimal.class))
                            .build())
                    .toList());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateExperimentsItems {

        @Test
        void createAndGet() {
            ExperimentItemsBatch itemsBatch = podamFactory.manufacturePojo(ExperimentItemsBatch.class);
            var request = itemsBatch.toBuilder()
                    .experimentItems(itemsBatch.experimentItems().stream()
                            .map(item -> item.toBuilder()
                                    .totalEstimatedCost(null)
                                    .usage(null)
                                    .duration(null)
                                    .build())
                            .collect(toSet()))
                    .build();

            createAndAssert(request, API_KEY, TEST_WORKSPACE);

            request.experimentItems().forEach(item -> getAndAssert(item, TEST_WORKSPACE, API_KEY));
        }

        @Test
        void insertInvalidDatasetItemWorkspace() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            UUID datasetItemId = createDatasetItem(TEST_WORKSPACE, API_KEY);

            var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .datasetItemId(datasetItemId)
                    .build();

            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class).toBuilder()
                    .experimentItems(Set.of(experimentItem))
                    .build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).getMessage())
                        .isEqualTo("Upserting experiment item with 'dataset_item_id' not belonging to the workspace");
            }
        }

        @Test
        void insertInvalidExperimentWorkspace() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Experiment experiment = generateExperiment();

            UUID experimentId = createAndAssert(experiment, API_KEY, TEST_WORKSPACE);

            var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experimentId)
                    .build();

            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class).toBuilder()
                    .experimentItems(Set.of(experimentItem))
                    .build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).getMessage())
                        .isEqualTo("Upserting experiment item with 'experiment_id' not belonging to the workspace");
            }
        }

        private UUID createDatasetItem(String workspaceName, String apiKey) {
            var item = podamFactory.manufacturePojo(DatasetItem.class);

            var batch = podamFactory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            try (var actualResponse = client.target("%s/v1/private/datasets".formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(batch))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            return item.id();
        }

        Stream<Arguments> insertInvalidId() {
            return Stream.of(
                    arguments(
                            podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .id(UUID.randomUUID())
                                    .build(),
                            "Experiment Item"),
                    arguments(
                            podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .experimentId(UUID.randomUUID())
                                    .build(),
                            "Experiment Item experiment"),
                    arguments(
                            podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .datasetItemId(UUID.randomUUID())
                                    .build(),
                            "Experiment Item datasetItem"),
                    arguments(
                            podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .traceId(UUID.randomUUID())
                                    .build(),
                            "Experiment Item trace"));
        }

        @ParameterizedTest
        @MethodSource
        void insertInvalidId(ExperimentItem experimentItem, String expectedErrorMessage) {
            var request = ExperimentItemsBatch.builder()
                    .experimentItems(Set.of(experimentItem)).build();
            var expectedError = new com.comet.opik.api.error.ErrorMessage(
                    List.of(expectedErrorMessage + " id must be a version 7 UUID"));

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(request))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.QuotaLimitTestUtils#quotaLimitsTestProvider")
        void testQuotasLimit_whenLimitIsEmptyOrNotReached_thenAcceptCreation(
                List<Quota> quotas, boolean isLimitReached) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();

            AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, workspaceName, workspaceId, USER, quotas);

            var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class);

            try (var actualResponse = experimentResourceClient.callCreateExperimentItem(Set.of(experimentItem), API_KEY,
                    workspaceName)) {
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

    @Nested
    @DisplayName("Get Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackScoreNames {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("when get feedback score names, then return feedback score names")
        void getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames(boolean userExperimentId) {

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

            List<List<FeedbackScoreBatchItem>> multipleValuesFeedbackScoreList = traceResourceClient
                    .createMultiValueScores(
                            multipleValuesFeedbackScores, project, apiKey, workspaceName);

            List<List<FeedbackScoreBatchItem>> singleValueScores = traceResourceClient.createMultiValueScores(
                    List.of(names.getLast()),
                    project, apiKey, workspaceName);

            UUID experimentId = createExperimentsItems(apiKey, workspaceName, multipleValuesFeedbackScoreList,
                    singleValueScores);

            // Create unexpected feedback scores
            var unexpectedProject = podamFactory.manufacturePojo(Project.class);

            List<List<FeedbackScoreBatchItem>> unexpectedScores = traceResourceClient.createMultiValueScores(otherNames,
                    unexpectedProject,
                    apiKey, workspaceName);

            createExperimentsItems(apiKey, workspaceName, unexpectedScores, List.of());

            fetchAndAssertResponse(userExperimentId, experimentId, names, otherNames, apiKey, workspaceName);
        }
    }

    private void fetchAndAssertResponse(boolean userExperimentId, UUID experimentId, List<String> names,
            List<String> otherNames, String apiKey, String workspaceName) {

        WebTarget webTarget = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("feedback-scores")
                .path("names");

        if (userExperimentId) {
            var ids = JsonUtils.writeValueAsString(List.of(experimentId));
            webTarget = webTarget.queryParam("experiment_ids", ids);
        }

        List<String> expectedNames = userExperimentId
                ? names
                : Stream.of(names, otherNames).flatMap(List::stream).toList();

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

    private UUID createExperimentsItems(String apiKey, String workspaceName,
            List<List<FeedbackScoreBatchItem>> multipleValuesFeedbackScoreList,
            List<List<FeedbackScoreBatchItem>> singleValueScores) {

        UUID experimentId = experimentResourceClient.create(apiKey, workspaceName);

        Stream.of(multipleValuesFeedbackScoreList, singleValueScores)
                .flatMap(List::stream)
                .flatMap(List::stream)
                .map(FeedbackScoreBatchItem::id)
                .distinct()
                .forEach(traceId -> {
                    var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .traceId(traceId)
                            .experimentId(experimentId)
                            .build();

                    experimentResourceClient.createExperimentItem(Set.of(experimentItem), apiKey, workspaceName);
                });

        return experimentId;
    }

    private void createAndAssert(ExperimentItemsBatch request, String apiKey, String workspaceName) {
        experimentResourceClient.createExperimentItem(request.experimentItems(), apiKey, workspaceName);
    }

    private void getAndAssert(ExperimentItem expectedExperimentItem, String workspaceName, String apiKey) {
        var id = expectedExperimentItem.id();
        try (var actualResponse = client.target(getExperimentItemsPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            var actualExperimentItem = actualResponse.readEntity(ExperimentItem.class);

            assertThat(actualExperimentItem)
                    .usingRecursiveComparison()
                    .ignoringFields(ITEM_IGNORED_FIELDS)
                    .isEqualTo(expectedExperimentItem);

            assertIgnoredFieldsWithoutFeedbacks(actualExperimentItem, expectedExperimentItem);
        }
    }

    private void assertIgnoredFields(
            List<ExperimentItem> actualExperimentItems, List<ExperimentItem> expectedExperimentItems) {
        assertThat(actualExperimentItems).hasSameSizeAs(expectedExperimentItems);
        for (int i = 0; i < actualExperimentItems.size(); i++) {
            assertIgnoredFieldsFullContent(actualExperimentItems.get(i), expectedExperimentItems.get(i));
        }
    }

    private void assertIgnoredFieldsFullContent(ExperimentItem actualExperimentItem,
            ExperimentItem expectedExperimentItem) {
        assertIgnoredFields(actualExperimentItem, expectedExperimentItem, true);
    }

    private void assertIgnoredFieldsWithoutFeedbacks(ExperimentItem actualExperimentItem,
            ExperimentItem expectedExperimentItem) {
        assertIgnoredFields(actualExperimentItem, expectedExperimentItem, false);
    }

    private void assertIgnoredFields(ExperimentItem actualExperimentItem, ExperimentItem expectedExperimentItem,
            boolean isFullContent) {
        assertThat(actualExperimentItem.createdAt()).isAfter(expectedExperimentItem.createdAt());
        assertThat(actualExperimentItem.lastUpdatedAt()).isAfter(expectedExperimentItem.lastUpdatedAt());
        assertThat(actualExperimentItem.createdBy()).isEqualTo(USER);
        assertThat(actualExperimentItem.lastUpdatedBy()).isEqualTo(USER);
        if (isFullContent) {
            actualExperimentItem = assertFeedbackScoresIgnoredFieldsAndSetThemToNull(actualExperimentItem, USER);

            assertThat(actualExperimentItem.feedbackScores())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .ignoringCollectionOrder()
                    .isEqualTo(expectedExperimentItem.feedbackScores());
            assertThat(actualExperimentItem.input()).isEqualTo(expectedExperimentItem.input());
            assertThat(actualExperimentItem.output()).isEqualTo(expectedExperimentItem.output());
            CommentAssertionUtils.assertComments(expectedExperimentItem.comments(), actualExperimentItem.comments());
        } else {
            assertThat(actualExperimentItem.input()).isNull();
            assertThat(actualExperimentItem.output()).isNull();
            assertThat(actualExperimentItem.feedbackScores()).isNull();
        }
    }

    private void getAndAssertNotFound(UUID id, String apiKey, String workspaceName) {
        var expectedError = new ErrorMessage(HttpStatus.SC_NOT_FOUND,
                "Not found experiment item with id '%s'".formatted(id));
        try (var actualResponse = client.target(getExperimentItemsPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);

            var actualError = actualResponse.readEntity(ErrorMessage.class);

            assertThat(actualError).isEqualTo(expectedError);
        }
    }

    private void streamAndAssert(
            ExperimentItemStreamRequest request,
            List<ExperimentItem> expectedExperimentItems,
            List<ExperimentItem> unexpectedExperimentItems,
            String apiKey,
            String workspaceName) {
        try (var actualResponse = client.target(getExperimentItemsPath())
                .path("stream")
                .request()
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var actualExperimentItems = experimentResourceClient.getStreamed(
                    actualResponse, EXPERIMENT_ITEM_TYPE_REFERENCE);

            assertThat(actualExperimentItems)
                    .usingRecursiveComparison()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                    .ignoringFields(ITEM_IGNORED_FIELDS)
                    .isEqualTo(expectedExperimentItems);

            assertIgnoredFields(actualExperimentItems, expectedExperimentItems);

            if (!unexpectedExperimentItems.isEmpty()) {
                assertThat(actualExperimentItems)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ITEM_IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedExperimentItems);
            }
        }
    }

    private String getExperimentsPath() {
        return URL_TEMPLATE.formatted(baseURI);
    }

    private String getExperimentItemsPath() {
        return URL_TEMPLATE.formatted(baseURI) + ITEMS_PATH;
    }

    @Nested
    @DisplayName("Bulk Upload:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BulkUpload {

        @Test
        void experimentItemsBulk__whenProcessingValidBatch__thenReturnNoContent() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
            var datasetItem = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            datasetResourceClient.createDatasetItems(
                    new DatasetItemBatch(dataset.name(), null, List.of(datasetItem)),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a bulk upload request with a single item
            var trace = createTrace();

            var span = creatrSpan();

            var feedbackScore = createScore();

            List<ExperimentItem> expectedItems = getExpectedItem(datasetItem, trace, feedbackScore);

            var experimentName = "Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);
            var bulkRecord = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem.id())
                    .trace(trace)
                    .spans(List.of(span))
                    .feedbackScores(List.of(feedbackScore))
                    .build();

            var bulkUpload = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset.name())
                    .items(List.of(bulkRecord))
                    .build();

            // when
            experimentResourceClient.bulkUploadExperimentItem(bulkUpload, API_KEY, TEST_WORKSPACE);

            // then
            List<ExperimentItem> actualExperimentItems = experimentResourceClient.getExperimentItems(experimentName,
                    API_KEY, TEST_WORKSPACE);

            assertItems(actualExperimentItems, expectedItems, EXPERIMENT_ITEMS_IGNORED_FIELDS);
        }

        private List<ExperimentItem> getExpectedItem(DatasetItem datasetItem, Trace trace,
                FeedbackScore feedbackScore) {
            return List.of(
                    ExperimentItem.builder()
                            .datasetItemId(datasetItem.id())
                            .traceId(Optional.ofNullable(trace).map(Trace::id).orElse(null))
                            .duration(Optional.ofNullable(trace)
                                    .map(t -> DurationUtils.getDurationInMillisWithSubMilliPrecision(t.startTime(),
                                            t.endTime()))
                                    .orElse(0.0))
                            .input(Optional.ofNullable(trace).map(Trace::input).orElse(null))
                            .output(Optional.ofNullable(trace).map(Trace::output).orElse(null))
                            .feedbackScores(List.of(feedbackScore))
                            .createdAt(Optional.ofNullable(trace).map(Trace::createdAt).orElse(null))
                            .lastUpdatedAt(Optional.ofNullable(trace).map(Trace::lastUpdatedAt).orElse(null))
                            .createdBy(USER)
                            .lastUpdatedBy(USER)
                            .build());
        }

        private FeedbackScore createScore() {
            return podamFactory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
        }

        private Span creatrSpan() {
            return podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(podamFactory.manufacturePojo(UUID.class))
                    .startTime(Instant.now())
                    .endTime(Instant.now().plusSeconds(1))
                    .usage(null)
                    .totalEstimatedCost(null)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
        }

        private Trace createTrace() {
            return podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(podamFactory.manufacturePojo(UUID.class))
                    .startTime(Instant.now())
                    .endTime(Instant.now().plusSeconds(1))
                    .usage(null)
                    .totalEstimatedCost(null)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
        }

        @Test
        void experimentItemsBulk__whenProcessingBatchWithNoTraceButWithFeedbackScores__thenReturnNoContent() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
            var datasetItem = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            datasetResourceClient.createDatasetItems(
                    new DatasetItemBatch(dataset.name(), null, List.of(datasetItem)),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a bulk upload request with a single item

            var feedbackScore = createScore();

            List<ExperimentItem> expectedItems = getExpectedItem(datasetItem, null, feedbackScore);

            var experimentName = "Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);
            var bulkItemRecord = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem.id())
                    .feedbackScores(List.of(feedbackScore))
                    .build();

            var bulkUploadRequest = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset.name())
                    .items(List.of(bulkItemRecord))
                    .build();

            // when
            experimentResourceClient.bulkUploadExperimentItem(bulkUploadRequest, API_KEY, TEST_WORKSPACE);

            // then
            List<ExperimentItem> actualExperimentItems = experimentResourceClient.getExperimentItems(experimentName,
                    API_KEY, TEST_WORKSPACE);

            String[] ignoringFields = Stream
                    .concat(Arrays.stream(EXPERIMENT_ITEMS_IGNORED_FIELDS), Stream.of("traceId"))
                    .toArray(String[]::new);

            assertItems(actualExperimentItems, expectedItems, ignoringFields);
        }

        private void assertItems(List<ExperimentItem> actual, List<ExperimentItem> expected, String[] ignoringFields) {
            assertThat(actual).hasSize(expected.size());

            assertThat(actual)
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .ignoringCollectionOrder()
                    .ignoringFields(ignoringFields)
                    .isEqualTo(expected);

            if (ignoringFields != null && Set.of(ignoringFields).contains("traceId")) {
                assertThat(actual.getFirst().traceId()).isNotNull();
            }
        }

        @Test
        void experimentItemsBulk__whenProcessingBatchWithExceedLimit__thenReturnBadRequest() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
            var datasetItem = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();
            datasetResourceClient.createDatasetItems(
                    new DatasetItemBatch(dataset.name(), null, List.of(datasetItem)),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a large string that will cause the request to exceed the max size
            // Each item will have a large input and output to exceed the 4MB limit
            String largeString = generateLargeString(1024 * 1024); // 1MB string

            // Create multiple items with large inputs and outputs
            List<ExperimentItemBulkRecord> items = PodamFactoryUtils.manufacturePojoList(podamFactory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .id(null)
                            .startTime(Instant.now())
                            .endTime(Instant.now().plusSeconds(1))
                            .input(JsonUtils.readTree("{\"text\": \"" + largeString + "\"}"))
                            .output(JsonUtils.readTree("{\"text\": \"" + largeString + "\"}"))
                            .build())
                    .map(trace -> ExperimentItemBulkRecord.builder()
                            .datasetItemId(datasetItem.id())
                            .trace(trace)
                            .build())
                    .toList();
            // 5 items with large data should exceed 4MB

            var bulkUpload = ExperimentItemBulkUpload.builder()
                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(8))
                    .datasetName(dataset.name())
                    .items(items)
                    .build();

            // when
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUpload, API_KEY,
                    TEST_WORKSPACE)) {

                // then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                var errorMessage = response.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(errorMessage.errors())
                        .contains("The request body Request size exceeds the maximum allowed size of 4MB");
            }
        }

        @ParameterizedTest
        @MethodSource
        void experimentItemsBulk__whenProcessingBatchWithEmptyItems__thenReturnBadRequest(ExperimentItemBulkUpload bulk,
                String expectedErrorMessage) {

            // when
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulk, API_KEY, TEST_WORKSPACE)) {

                // then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                var errorMessage = response.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(errorMessage.errors()).contains(expectedErrorMessage);
            }
        }

        Stream<Arguments> experimentItemsBulk__whenProcessingBatchWithEmptyItems__thenReturnBadRequest() {
            return Stream.of(
                    arguments(
                            ExperimentItemBulkUpload.builder()
                                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(8))
                                    .datasetName("Test Dataset")
                                    .items(null)
                                    .build(),
                            "items must not be null"),
                    arguments(
                            ExperimentItemBulkUpload.builder()
                                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(8))
                                    .datasetName("Test Dataset")
                                    .items(List.of())
                                    .build(),
                            "items size must be between 1 and 250"));
        }

        @Test
        void experimentItemsBulk__whenProcessingBatchSizeIsHigherThanLimit__thenReturnBadRequest() {
            // given
            var bulkUpload = ExperimentItemBulkUpload.builder()
                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(8))
                    .datasetName("Test Dataset")
                    .items(IntStream.range(0, 251)
                            .mapToObj(i -> ExperimentItemBulkRecord.builder()
                                    .datasetItemId(UUID.randomUUID())
                                    .trace(Trace.builder()
                                            .id(UUID.randomUUID())
                                            .startTime(Instant.now())
                                            .endTime(Instant.now().plusSeconds(1))
                                            .build())
                                    .build())
                            .toList())
                    .build();

            // when
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUpload, API_KEY,
                    TEST_WORKSPACE)) {

                // then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                var errorMessage = response.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(errorMessage.errors()).contains("items size must be between 1 and 250");
            }
        }

        @Test
        void experimentItemsBulk__whenProcessingBatchHasSpansAndNoTrace__thenReturnBadRequest() {
            // given
            var bulkUpload = ExperimentItemBulkUpload.builder()
                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(8))
                    .datasetName("Test Dataset")
                    .items(List.of(ExperimentItemBulkRecord.builder()
                            .datasetItemId(UUID.randomUUID())
                            .trace(null)
                            .spans(List.of(Span.builder()
                                    .id(UUID.randomUUID())
                                    .traceId(UUID.randomUUID())
                                    .startTime(Instant.now())
                                    .endTime(Instant.now().plusSeconds(1))
                                    .name(UUID.randomUUID().toString())
                                    .type(SpanType.llm)
                                    .build()))
                            .build()))
                    .build();

            // when
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUpload, API_KEY,
                    TEST_WORKSPACE)) {

                // then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var errorMessage = response.readEntity(ErrorMessage.class);

                assertThat(errorMessage.getMessage()).contains("Trace is required when spans are provided");
            }
        }

        private String generateLargeString(int size) {
            return "a".repeat(Math.max(0, size));
        }
    }
}
