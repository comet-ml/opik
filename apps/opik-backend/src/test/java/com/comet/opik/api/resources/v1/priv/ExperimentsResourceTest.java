package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Comment;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentItemsDelete;
import com.comet.opik.api.ExperimentsDelete;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Identifier;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.glassfish.jersey.client.ChunkedInput;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.IGNORED_FIELDS_COMMENTS;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoresIgnoredFieldsAndSetThemToNull;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.api.resources.utils.TestUtils.getIdFromLocation;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.v1.priv.QuotaLimitTestUtils.ERR_USAGE_LIMIT_EXCEEDED;
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
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentsResourceTest {
    private static final String URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String ITEMS_PATH = "/items";
    private static final String URL_TEMPLATE_TRACES = "%s/v1/private/traces";

    private static final String API_KEY = UUID.randomUUID().toString();

    private static final String[] EXPERIMENT_IGNORED_FIELDS = new String[]{
            "id", "datasetId", "name", "feedbackScores", "traceCount", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "comments", "promptVersion", "promptVersions"};
    public static final String[] ITEM_IGNORED_FIELDS = {"input", "output", "feedbackScores", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy", "comments"};

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final GenericType<ChunkedInput<String>> CHUNKED_INPUT_STRING_GENERIC_TYPE = new GenericType<>() {
    };

    private static final TypeReference<ExperimentItem> EXPERIMENT_ITEM_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private final WireMockUtils.WireMockRuntime wireMock;
    private final AppContextConfig contextConfig;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        contextConfig = AppContextConfig.builder()
                .jdbcUrl(MY_SQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .mockEventBus(Mockito.mock(EventBus.class))
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
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

            var expectedExperiment = generateExperiment();

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

            var experiments = generateExperimentList();

            experiments.forEach(experiment -> createAndAssert(experiment, okApikey, workspaceName));

            Set<UUID> ids = experiments.stream().map(Experiment::id).collect(toSet());

            var deleteRequest = new ExperimentsDelete(ids);

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

            var createRequest = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

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

            var experiments = generateExperimentList();

            experiments.forEach(experiment -> createAndAssert(experiment, API_KEY, workspaceName));

            Set<UUID> ids = experiments.stream().map(Experiment::id).collect(toSet());

            var deleteRequest = new ExperimentsDelete(ids);

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

            var createRequest = podamFactory.manufacturePojo(ExperimentItemsBatch.class);
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

    private Experiment generateExperiment() {
        return experimentResourceClient.createPartialExperiment().build();
    }

    private List<Experiment> generateExperimentList() {
        return experimentResourceClient.generateExperimentList();
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
            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class)
                    .stream()
                    .map(experiment -> experimentResourceClient.createPartialExperiment()
                            .datasetName(datasetName)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment().build());

            unexpectedExperiments
                    .forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

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

            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class)
                    .stream()
                    .map(experiment -> experimentResourceClient.createPartialExperiment()
                            .name(name)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment().build());

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

            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class)
                    .stream()
                    .map(experiment -> experimentResourceClient.createPartialExperiment()
                            .datasetName(datasetName)
                            .name(name)
                            .metadata(null)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment().build());

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

        @Test
        void findAll() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiments = generateExperimentList();

            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

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

            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class).stream()
                    .map(experiment -> experimentResourceClient.createPartialExperiment()
                            .feedbackScores(null)
                            .build())
                    .toList();

            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment, apiKey, workspaceName));

            var noScoreExperiment = generateExperiment();

            createAndAssert(noScoreExperiment, apiKey, workspaceName);

            var noItemExperiment = generateExperiment();

            createAndAssert(noItemExperiment, apiKey, workspaceName);

            // Creating three traces with input, output and scores
            var trace1 = makeTrace(apiKey, workspaceName);

            var trace2 = makeTrace(apiKey, workspaceName);

            var trace3 = makeTrace(apiKey, workspaceName);

            var trace4 = makeTrace(apiKey, workspaceName);

            var trace5 = makeTrace(apiKey, workspaceName);

            var trace6 = makeTrace(apiKey, workspaceName);

            var traces = List.of(trace1, trace2, trace3, trace4, trace5);

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
                                    .withComparatorForType(ExperimentsResourceTest.this::customComparator,
                                            BigDecimal.class)
                                    .build())
                            .isEqualTo(expectedScores);

                    var expectedComments = expectedExperimentIdsWithComments.contains(experiment.id())
                            ? comments
                            : null;

                    assertThat(expectedComments)
                            .usingRecursiveComparison()
                            .ignoringFields(IGNORED_FIELDS_COMMENTS)
                            .isEqualTo(experiment.comments());
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
            var trace1 = makeTrace(apiKey, workspaceName);

            var trace2 = makeTrace(apiKey, workspaceName);

            var trace3 = makeTrace(apiKey, workspaceName);

            var trace4 = makeTrace(apiKey, workspaceName);

            var trace5 = makeTrace(apiKey, workspaceName);

            var trace6 = makeTrace(apiKey, workspaceName);

            var traces = List.of(trace1, trace2, trace3, trace4, trace5, trace6);

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
                                    .withComparatorForType(ExperimentsResourceTest.this::customComparator,
                                            BigDecimal.class)
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

            List<Dataset> datasets = PodamFactoryUtils.manufacturePojoList(podamFactory, Dataset.class);

            datasets.forEach(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                createAndAssert(generateExperiment().toBuilder()
                        .datasetName(dataset.name())
                        .build(), apiKey, workspaceName);
            });

            findAndAssert(workspaceName, 1, 10, null, null, List.of(), 0, List.of(), apiKey, true, Map.of(), null);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when searching by dataset deleted and result having {} experiments, then return page")
        void find__whenSearchingByDatasetDeletedAndResultHavingXExperiments__thenReturnPage(
                int experimentCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> datasets = Collections.synchronizedList(new ArrayList<>());
            List<Experiment> experiments = Collections.synchronizedList(new ArrayList<>());

            IntStream.range(0, experimentCount)
                    .parallel()
                    .forEach(i -> {
                        var dataset = podamFactory.manufacturePojo(Dataset.class);
                        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                        datasets.add(dataset);

                        UUID experimentId = createAndAssert(
                                generateExperiment().toBuilder()
                                        .datasetName(dataset.name())
                                        .build(),
                                apiKey,
                                workspaceName);

                        experiments.add(getExperiment(experimentId, workspaceName, apiKey).toBuilder()
                                .datasetName(null)
                                .build());
                    });

            datasetResourceClient.deleteDatasets(datasets, apiKey, workspaceName);

            experiments.sort(Comparator.comparing(Experiment::id));

            findAndAssert(workspaceName, 1, experimentCount, null, null, experiments.reversed(), experimentCount,
                    List.of(), apiKey, true, Map.of(), null);
        }

        Stream<Arguments> find__whenSearchingByDatasetDeletedAndResultHavingXExperiments__thenReturnPage() {
            return Stream.of(
                    arguments(10),
                    arguments(100),
                    arguments(110));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when searching by dataset deleted, having feedback scores, and result having {} datasets, then return page")
        void find__whenSearchingByDatasetDeletedHavingFeedbackScoresAndResultHavingXDatasets__thenReturnPage(
                int experimentCount, int expectedMatchCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            int unexpectedDatasetCount = experimentCount - expectedMatchCount;

            IntStream.range(0, unexpectedDatasetCount)
                    .parallel()
                    .forEach(i -> {
                        var dataset = podamFactory.manufacturePojo(Dataset.class);
                        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                        createAndAssert(
                                generateExperiment().toBuilder()
                                        .datasetName(dataset.name())
                                        .build(),
                                apiKey,
                                workspaceName);
                    });

            List<Dataset> datasets = Collections.synchronizedList(new ArrayList<>());
            List<Experiment> experiments = Collections.synchronizedList(new ArrayList<>());

            IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .forEach(experiment -> {
                        var dataset = podamFactory.manufacturePojo(Dataset.class);
                        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                        datasets.add(dataset);

                        Experiment expectedExperiment = createExperimentWithFeedbackScores(apiKey, workspaceName,
                                dataset.name());
                        experiments.add(expectedExperiment);
                    });

            datasetResourceClient.deleteDatasets(datasets, apiKey, workspaceName);

            experiments.sort(Comparator.comparing(Experiment::id));

            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = experiments
                    .stream()
                    .collect(toMap(Experiment::id, experiment -> experiment.feedbackScores().stream()
                            .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))));

            findAndAssert(workspaceName, 1, expectedMatchCount, null, null, experiments.reversed(), expectedMatchCount,
                    List.of(), apiKey, true, expectedScoresPerExperiment, null);
        }

        Stream<Arguments> find__whenSearchingByDatasetDeletedHavingFeedbackScoresAndResultHavingXDatasets__thenReturnPage() {
            return Stream.of(
                    arguments(10, 5),
                    arguments(100, 50),
                    arguments(110, 10));
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
            var experiment = generateExperiment().toBuilder()
                    .datasetName(datasetName)
                    .build();

            createAndAssert(experiment, apiKey, workspaceName);

            var expectedExperiment = getExperiment(experiment.id(), workspaceName, apiKey).toBuilder()
                    .datasetName(null)
                    .build();

            var trace = makeTrace(apiKey, workspaceName);

            var traces = List.of(trace);

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

            return expectedExperiment.toBuilder()
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
                        var experiment = generateExperiment().toBuilder()
                                .datasetName(dataset.name())
                                .promptVersion(versionLink)
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

                        var experiment = generateExperiment().toBuilder()
                                .datasetName(dataset.name())
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
        void whenSortingByFieldAndDirection__thenReturnPage(Comparator<Experiment> comparator,
                SortingField sortingField) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiments = IntStream.range(0, 5)
                    .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build())
                    .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment))
                    .toList();

            var expectedExperiments = experiments
                    .stream()
                    .sorted(comparator)
                    .toList();

            Map<UUID, Map<String, BigDecimal>> expectedScores = expectedExperiments
                    .stream()
                    .map(experiment -> Map.entry(experiment.id(), experiment.feedbackScores()
                            .stream()
                            .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, expectedScores, null, List.of(sortingField));
        }

        private Experiment generateFullExperiment(String apiKey, String workspaceName, Experiment expectedExperiment) {

            createAndAssert(expectedExperiment, apiKey, workspaceName);

            int tracesNumber = PodamUtils.getIntegerInRange(1, 10);

            List<Trace> traces = IntStream.range(0, tracesNumber)
                    .parallel()
                    .mapToObj(i -> makeTrace(apiKey, workspaceName))
                    .toList();

            Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap = traces.stream()
                    .map(ExperimentsResourceTest.this::makeTraceScores)
                    .flatMap(List::stream)
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

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

            return expectedExperiment.toBuilder()
                    .traceCount((long) traces.size())
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
                    var experiment = generateExperiment().toBuilder()
                            .datasetName(dataset.name())
                            .build();

                    createAndAssert(experiment, apiKey, workspaceName);
                });
        return dataset;
    }

    private void deleteTrace(UUID id, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(getTracesPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    private @NotNull Map<UUID, Map<String, BigDecimal>> getExpectedScoresPerExperiment(
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

    private @NotNull Set<UUID> getExpectedExperimentIdsWithComments(
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
                unexpectedExperiments, apiKey, datasetDeleted, expectedScoresPerExperiment, promptId, null);
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
            List<SortingField> sortingFields) {

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

        if (promptId != null) {
            webTarget = webTarget.queryParam("prompt_id", promptId);
        }

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            webTarget = webTarget.queryParam("sorting", toURLEncodedQueryParam(sortingFields));
        }

        try (var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            var actualPage = actualResponse.readEntity(ExperimentPage.class);
            var actualExperiments = actualPage.content();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            assertThat(actualPage.page()).isEqualTo(page);
            assertThat(actualPage.size()).isEqualTo(expectedExperiments.size());
            assertThat(actualPage.total()).isEqualTo(expectedTotal);
            assertThat(actualExperiments).hasSize(expectedExperiments.size());

            assertThat(actualExperiments)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedExperiments);

            assertIgnoredFields(actualExperiments, expectedExperiments, datasetId);

            if (!unexpectedExperiments.isEmpty()) {
                assertThat(actualExperiments)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedExperiments);
            }

            if (expectedScoresPerExperiment != null) {
                for (Experiment experiment : actualExperiments) {
                    var expectedScores = expectedScoresPerExperiment.get(experiment.id());
                    var actualScores = getScoresMap(experiment);

                    assertThat(actualScores)
                            .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(ExperimentsResourceTest.this::customComparator,
                                            BigDecimal.class)
                                    .build())
                            .isEqualTo(expectedScores);
                }
            }
        }
    }

    private void createScoreAndAssert(FeedbackScoreBatch feedbackScoreBatch) {
        createScoreAndAssert(feedbackScoreBatch, API_KEY, TEST_WORKSPACE);
    }

    private void createScoreAndAssert(FeedbackScoreBatch batch, String apiKey, String workspaceName) {
        traceResourceClient.feedbackScores(batch.scores(), apiKey, workspaceName);
    }

    private int customComparator(BigDecimal v1, BigDecimal v2) {
        //TODO This is a workaround to compare BigDecimals and clickhouse floats seems to have some precision issues
        // Compare the integer parts directly

        if (v1.compareTo(v2) == 0) {
            return 0;
        }

        int intComparison = v1.toBigInteger().compareTo(v2.toBigInteger());
        if (intComparison != 0) {
            return intComparison;
        }

        // Extract and compare the decimal parts
        BigDecimal v1Decimal = v1.remainder(BigDecimal.ONE).abs(); // Get the decimal part
        BigDecimal v2Decimal = v2.remainder(BigDecimal.ONE).abs(); // Get the decimal part

        // Convert decimal parts to integers by scaling them to eliminate the decimal point
        BigDecimal v1DecimalInt = v1Decimal.movePointRight(v1Decimal.scale());
        BigDecimal v2DecimalInt = v2Decimal.movePointRight(v2Decimal.scale());

        // Calculate the difference between the integer representations of the decimal parts
        BigDecimal decimalDifference = v1DecimalInt.subtract(v2DecimalInt).abs();

        // If the difference is 1 or less, consider the numbers equal
        if (decimalDifference.compareTo(BigDecimal.ONE) <= 0) {
            return 0;
        }

        // Otherwise, compare the decimal parts as integers
        return v1DecimalInt.compareTo(v2DecimalInt);
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
            var trace1 = makeTrace();

            var trace2 = makeTrace();

            var trace3 = makeTrace();

            var traces = List.of(trace1, trace2, trace3);

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

            Experiment experiment = getAndAssert(expectedExperiment.id(), expectedExperiment, TEST_WORKSPACE, API_KEY);

            assertThat(experiment.traceCount()).isEqualTo(expectedExperiment.traceCount());
            assertThat(experiment.feedbackScores()).hasSize(totalNumberOfScores);

            Map<String, BigDecimal> actualScores = getScoresMap(experiment);

            assertThat(actualScores)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedScores);

        }

        @Test
        void createAndGetByName() {
            var expectedExperiment = generateExperiment();
            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedExperiment.id(), expectedExperiment, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new Identifier(expectedExperiment.name())))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualExperiment = actualResponse.readEntity(Experiment.class);
                assertThat(actualExperiment.id()).isEqualTo(expectedExperiment.id());

                assertThat(actualExperiment)
                        .usingRecursiveComparison()
                        .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                        .isEqualTo(expectedExperiment);
            }
        }

        @Test
        void getByNameNotFound() {
            String name = UUID.randomUUID().toString();
            var expectedError = new ErrorMessage(HttpStatus.SC_NOT_FOUND,
                    "Not found experiment with name '%s'".formatted(name));
            try (var actualResponse = client.target(getExperimentsPath())
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new Identifier(name)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);

                var actualError = actualResponse.readEntity(ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @Test
        void createAndGetFeedbackAvgAndComments() {
            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .traceCount(3L)
                    .build();

            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            // Creating three traces with input, output, and scores
            var trace1 = makeTrace();

            var trace2 = makeTrace();

            var trace3 = makeTrace();

            var traces = List.of(trace1, trace2, trace3);

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
                            .withComparatorForType(ExperimentsResourceTest.this::customComparator, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedScores);

            assertThat(expectedComments)
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS_COMMENTS)
                    .isEqualTo(experiment.comments());
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

        @Test
        void createWithSameIdIsIdempotent() {
            var expectedExperiment = generateExperiment();
            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            var unexpectedExperiment = generateExperiment().toBuilder().id(expectedExperiment.id()).build();
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

            var expectedExperiment = experimentResourceClient.createPartialExperiment()
                    .feedbackScores(null)
                    .build();

            createAndAssert(expectedExperiment, apiKey, workspaceName);

            // Creating three traces with input, output and scores
            var trace1 = makeTrace(apiKey, workspaceName);

            var trace2 = makeTrace(apiKey, workspaceName);

            var trace3 = makeTrace(apiKey, workspaceName);

            var trace4 = makeTrace(apiKey, workspaceName);

            var trace5 = makeTrace(apiKey, workspaceName);

            var trace6 = makeTrace(apiKey, workspaceName);

            var traces = List.of(trace1, trace2, trace3, trace4, trace5, trace6);

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

            Map<String, BigDecimal> expectedScores = getExpectedScores(
                    traceIdToScoresMap.entrySet()
                            .stream()
                            .filter(e -> !e.getKey().equals(trace6.id()))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));

            Experiment experiment = getAndAssert(expectedExperiment.id(), expectedExperiment, workspaceName, apiKey);

            assertThat(experiment.traceCount()).isEqualTo(traces.size()); // decide if we should count deleted traces

            Map<String, BigDecimal> actual = getScoresMap(experiment);

            assertThat(actual)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(ExperimentsResourceTest.this::customComparator, BigDecimal.class)
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
                        .value(podamFactory.manufacturePojo(BigDecimal.class))
                        .build())
                .toList();
    }

    private List<FeedbackScoreBatchItem> makeTraceScores(Trace trace) {
        return copyScoresFrom(
                PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class),
                trace);
    }

    private Trace makeTrace() {
        Trace trace = podamFactory.manufacturePojo(Trace.class);
        createTraceAndAssert(trace, API_KEY, TEST_WORKSPACE);
        return trace;
    }

    private Trace makeTrace(String apiKey, String workspaceName) {
        Trace trace = podamFactory.manufacturePojo(Trace.class);
        createTraceAndAssert(trace, apiKey, workspaceName);
        return trace;
    }

    private String getTracesPath() {
        return URL_TEMPLATE_TRACES.formatted(baseURI);
    }

    private void createTraceAndAssert(Trace trace, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(getTracesPath())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
            assertThat(actualResponse.hasEntity()).isFalse();

            var actualHeaderString = actualResponse.getHeaderString("Location");
            assertThat(actualHeaderString).isEqualTo(getTracesPath() + "/" + trace.id());
        }
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
            assertThat(experimentCaptor.getValue().createdAt()).isEqualTo(actualExperiment.createdAt());

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

            if (actualResponse.getStatusInfo().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return null;
            }

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
            var createRequest = podamFactory.manufacturePojo(ExperimentItemsBatch.class).toBuilder()
                    .build();
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
                    .post(Entity.json(new ExperimentsDelete(ids)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);

                if (datasetIds.isEmpty()) {

                    ArgumentCaptor<ExperimentsDeleted> experimentCaptor = ArgumentCaptor
                            .forClass(ExperimentsDeleted.class);
                    Mockito.verify(defaultEventBus).post(experimentCaptor.capture());

                    assertThat(experimentCaptor.getValue().datasetIds()).isEqualTo(datasetIds);
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
            var experiments = generateExperimentList();

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
                    .map(experimentItem -> experimentItem.toBuilder().experimentId(experiment1.id())
                            .traceId(traceWithScores1.getLeft().id()).build())
                    .collect(toUnmodifiableSet());
            var createRequest1 = ExperimentItemsBatch.builder().experimentItems(experimentItems1).build();
            createAndAssert(createRequest1, apiKey, workspaceName);

            var experimentItems2 = PodamFactoryUtils.manufacturePojoList(podamFactory, ExperimentItem.class).stream()
                    .map(experimentItem -> experimentItem.toBuilder().experimentId(experiment2.id())
                            .traceId(traceWithScores2.getLeft().id()).build())
                    .collect(toUnmodifiableSet());
            var createRequest2 = ExperimentItemsBatch.builder().experimentItems(experimentItems2).build();
            createAndAssert(createRequest2, apiKey, workspaceName);

            var experimentItems3 = PodamFactoryUtils.manufacturePojoList(podamFactory, ExperimentItem.class).stream()
                    .map(experimentItem -> experimentItem.toBuilder().experimentId(experiment3.id()).build())
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
                            .build())
                    .toList();
            var expectedExperimentItems2 = expectedExperimentItems.subList(limit, size).stream()
                    .map(experimentItem -> experimentItem.toBuilder()
                            .input(traceWithScores1.getLeft().input())
                            .output(traceWithScores1.getLeft().output())
                            .feedbackScores(traceWithScores1.getRight().stream()
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore).toList())
                            .comments(null)
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
            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

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
        @MethodSource("com.comet.opik.api.resources.v1.priv.QuotaLimitTestUtils#quotaLimitsTestProvider")
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

        UUID experimentId = experimentResourceClient.createExperiment(apiKey, workspaceName);

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
        try (var actualResponse = client.target(getExperimentItemsPath())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }

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
            assertThat(actualExperimentItem.comments())
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS_COMMENTS)
                    .isEqualTo(expectedExperimentItem.comments());
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

            var actualExperimentItems = getStreamedItems(actualResponse);

            assertThat(actualExperimentItems)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ITEM_IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedExperimentItems);

            assertIgnoredFields(actualExperimentItems, expectedExperimentItems);

            if (!unexpectedExperimentItems.isEmpty()) {
                assertThat(actualExperimentItems)
                        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ITEM_IGNORED_FIELDS)
                        .doesNotContainAnyElementsOf(unexpectedExperimentItems);
            }
        }
    }

    private List<ExperimentItem> getStreamedItems(Response response) {
        var items = new ArrayList<ExperimentItem>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String stringItem;
            while ((stringItem = inputStream.read()) != null) {
                items.add(JsonUtils.readValue(stringItem, EXPERIMENT_ITEM_TYPE_REFERENCE));
            }
        }
        return items;
    }

    private String getExperimentsPath() {
        return URL_TEMPLATE.formatted(baseURI);
    }

    private String getExperimentItemsPath() {
        return URL_TEMPLATE.formatted(baseURI) + ITEMS_PATH;
    }
}
