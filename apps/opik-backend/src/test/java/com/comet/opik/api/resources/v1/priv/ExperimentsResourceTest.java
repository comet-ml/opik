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
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.CommentAssertionUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.ExperimentsTestUtils;
import com.comet.opik.api.resources.utils.ListComparators;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.v1.events.TraceDeletedListener;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DatasetEventInfoHolder;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.r2dbc.spi.Statement;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;
import static com.comet.opik.api.grouping.GroupingFactory.METADATA;
import static com.comet.opik.api.grouping.GroupingFactory.TAGS;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.ExperimentsTestUtils.getQuantities;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoreNames;
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
import static java.util.stream.Collectors.averagingLong;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentsResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String ITEMS_PATH = "/items";

    private static final String API_KEY = UUID.randomUUID().toString();

    private static final String[] EXPERIMENT_IGNORED_FIELDS = new String[]{
            "id", "datasetId", "name", "feedbackScores", "traceCount", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "comments", "projectId"};

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String TEST_WORKSPACE = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);

    private static final TypeReference<ExperimentItem> EXPERIMENT_ITEM_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;
    private final AppContextConfig contextConfig;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        contextConfig = AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
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
    private SpanResourceClient spanResourceClient;
    private TraceDeletedListener traceDeletedListener;
    private TransactionTemplateAsync clickHouseTemplate;

    @BeforeAll
    void beforeAll(ClientSupport client, TraceDeletedListener traceDeletedListener,
            TransactionTemplateAsync clickHouseTemplate) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.traceDeletedListener = traceDeletedListener;
        this.clickHouseTemplate = clickHouseTemplate;

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

        @Test
        @DisplayName("when getting experiment with experiment_scores, then scores are returned")
        void getExperimentById_whenExperimentHasScores_thenScoresReturned() {
            var workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var experimentScores = List.of(
                    ExperimentScore.builder()
                            .name("accuracy")
                            .value(new BigDecimal("0.95"))
                            .build(),
                    ExperimentScore.builder()
                            .name("latency")
                            .value(new BigDecimal("120.5"))
                            .build());

            var expectedExperiment = generateExperiment().toBuilder()
                    .optimizationId(null)
                    .experimentScores(experimentScores)
                    .build();

            var experimentId = createAndAssert(expectedExperiment, okApikey, workspaceName);

            var retrievedExperiment = getExperiment(experimentId, workspaceName, okApikey);
            assertThat(retrievedExperiment).isNotNull();
            assertThat(retrievedExperiment.experimentScores())
                    .isNotNull()
                    .hasSize(2)
                    .extracting(ExperimentScore::name, ExperimentScore::value)
                    .containsExactlyInAnyOrder(
                            tuple("accuracy", new BigDecimal("0.95")),
                            tuple("latency", new BigDecimal("120.5")));
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

        @Test
        @DisplayName("when creating experiment with experiment_scores, then scores are saved and retrieved correctly")
        void createExperiment_whenExperimentScoresProvided_thenScoresSavedAndRetrieved() {
            var workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var experimentScores = List.of(
                    ExperimentScore.builder()
                            .name("accuracy")
                            .value(new BigDecimal("0.95"))
                            .build(),
                    ExperimentScore.builder()
                            .name("latency")
                            .value(new BigDecimal("120.5"))
                            .build());

            var expectedExperiment = generateExperiment().toBuilder()
                    .experimentScores(experimentScores)
                    .build();

            var experimentId = createAndAssert(expectedExperiment, okApikey, workspaceName);

            var retrievedExperiment = getAndAssert(experimentId, expectedExperiment, workspaceName, okApikey);
            assertThat(retrievedExperiment.experimentScores())
                    .isNotNull()
                    .hasSize(2)
                    .extracting(ExperimentScore::name, ExperimentScore::value)
                    .containsExactlyInAnyOrder(
                            tuple("accuracy", new BigDecimal("0.95")),
                            tuple("latency", new BigDecimal("120.5")));
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

            var createRequest = createItemsWithoutTrace();

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

            var createRequest = createItemsWithoutTrace();

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
                                .output(null)
                                .feedbackScores(null)
                                .input(null)
                                .totalEstimatedCost(null)
                                .comments(null)
                                .lastUpdatedBy(USER)
                                .createdBy(USER)
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
        void findByFilterMetadata(Operator operator, String key, String value) {
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
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                                            "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}"))
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment());

            unexpectedExperiments
                    .forEach(unexpectedExperiment -> createAndAssert(unexpectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                    .datasetId();
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            var filters = List.of(ExperimentFilter.builder()
                    .field(ExperimentField.METADATA)
                    .operator(operator)
                    .key(key)
                    .value(value)
                    .build());

            findAndAssert(workspaceName, 1, pageSize, datasetId, name, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, null, null, null, filters);
            findAndAssert(workspaceName, 2, pageSize, datasetId, name, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, null, null, null, filters);
        }

        private Stream<Arguments> findByFilterMetadata() {
            return Stream.of(
                    Arguments.of(
                            Operator.EQUAL,
                            "$.model[0].version",
                            "OPENAI, CHAT-GPT 4.0"),
                    Arguments.of(
                            Operator.NOT_EQUAL,
                            "$.model[0].version",
                            "OPENAI, CHAT-GPT Something"),
                    Arguments.of(
                            Operator.EQUAL,
                            "model[0].year",
                            "2024"),
                    Arguments.of(
                            Operator.NOT_EQUAL,
                            "model[0].year",
                            "2023"),
                    Arguments.of(
                            Operator.EQUAL,
                            "model[0].trueFlag",
                            "TRUE"),
                    Arguments.of(
                            Operator.NOT_EQUAL,
                            "model[0].trueFlag",
                            "FALSE"),
                    Arguments.of(
                            Operator.EQUAL,
                            "model[0].nullField",
                            "NULL"),
                    Arguments.of(
                            Operator.NOT_EQUAL,
                            "model[0].version",
                            "NULL"),
                    Arguments.of(
                            Operator.CONTAINS,
                            "$.model[0].version",
                            "CHAT-GPT"),
                    Arguments.of(
                            Operator.CONTAINS,
                            "$.model[0].year",
                            "02"),
                    Arguments.of(
                            Operator.CONTAINS,
                            "$.model[0].trueFlag",
                            "TRU"),
                    Arguments.of(
                            Operator.CONTAINS,
                            "$.model[0].nullField",
                            "NUL"),
                    Arguments.of(
                            Operator.NOT_CONTAINS,
                            "$.model[0].version",
                            "OPENAI, CHAT-GPT 2.0"),
                    Arguments.of(
                            Operator.STARTS_WITH,
                            "$.model[0].version",
                            "OPENAI, CHAT-GPT"),
                    Arguments.of(
                            Operator.ENDS_WITH,
                            "$.model[0].version",
                            "Chat-GPT 4.0"),
                    Arguments.of(
                            Operator.GREATER_THAN,
                            "model[0].year",
                            "2021"),
                    Arguments.of(
                            Operator.LESS_THAN,
                            "model[0].year",
                            "2031"));
        }

        @ParameterizedTest
        @MethodSource
        void findByFilterTags(Operator operator, String value) {
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
                            .tags(Set.of("tag1", "tag2", "tag3"))
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment().toBuilder().tags(Set.of("other")).build());

            unexpectedExperiments
                    .forEach(unexpectedExperiment -> createAndAssert(unexpectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                    .datasetId();
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            var filters = List.of(ExperimentFilter.builder()
                    .field(ExperimentField.TAGS)
                    .operator(operator)
                    .value(value)
                    .build());

            findAndAssert(workspaceName, 1, pageSize, datasetId, name, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, null, null, null, filters);
            findAndAssert(workspaceName, 2, pageSize, datasetId, name, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, null, null, null, filters);
        }

        private Stream<Arguments> findByFilterTags() {
            return Stream.of(
                    Arguments.of(Operator.EQUAL, "tag1"),
                    Arguments.of(Operator.NOT_EQUAL, "other"),
                    Arguments.of(Operator.CONTAINS, "tag"),
                    Arguments.of(Operator.NOT_CONTAINS, "other"));
        }

        @ParameterizedTest
        @MethodSource("getValidFilters")
        void findByFiltering(Function<Experiment, ExperimentFilter> getFilter) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);

            var prompt = podamFactory.manufacturePojo(Prompt.class);
            PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
            PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());

            var experiments = experimentResourceClient.generateExperimentList()
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .datasetName(datasetName)
                            .promptVersion(versionLink)
                            .promptVersions(List.of(versionLink))
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(generateExperiment());

            unexpectedExperiments
                    .forEach(unexpectedExperiment -> createAndAssert(unexpectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var experiment = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey);
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            var filters = List.of(getFilter.apply(experiment));

            findAndAssert(workspaceName, 1, pageSize, null, null, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, null, null, null, filters);
            findAndAssert(workspaceName, 2, pageSize, null, null, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey, false, Map.of(), null, null, null, null, filters);
        }

        private Stream<Arguments> getValidFilters() {
            Integer random = new Random().nextInt(5);
            return Stream.of(
                    Arguments.of(
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.DATASET_ID)
                                    .operator(Operator.EQUAL)
                                    .value(experiment.datasetId().toString())
                                    .build()),
                    Arguments.of(
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.PROMPT_IDS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.promptVersion().promptId().toString())
                                    .build()));
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
        @DisplayName("when filtering by experiment_ids, then return only matching experiments")
        void findByExperimentIds() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create 5 experiments
            var allExperiments = IntStream.range(0, 5)
                    .mapToObj(i -> generateExperiment())
                    .toList();
            allExperiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

            // Select 2 experiments to filter by
            var experimentIdsToFilter = Set.of(allExperiments.get(1).id(), allExperiments.get(3).id());
            var unexpectedExperiments = allExperiments.stream()
                    .filter(e -> !experimentIdsToFilter.contains(e.id()))
                    .toList();

            // Build experiment_ids query param as comma-separated UUIDs
            var experimentIdsParam = JsonUtils.writeValueAsString(experimentIdsToFilter);

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", 1)
                    .queryParam("size", 10)
                    .queryParam("experiment_ids", experimentIdsParam)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualPage = actualResponse.readEntity(ExperimentPage.class);

                assertThat(actualPage.page()).isEqualTo(1);
                assertThat(actualPage.total()).isEqualTo(2);
                assertThat(actualPage.content()).hasSize(2);

                // Verify the returned experiments match the requested IDs
                var actualIds = actualPage.content().stream()
                        .map(Experiment::id)
                        .collect(Collectors.toSet());
                assertThat(actualIds).isEqualTo(experimentIdsToFilter);

                // Verify unexpected experiments are not in the response
                var unexpectedIds = unexpectedExperiments.stream()
                        .map(Experiment::id)
                        .collect(Collectors.toSet());
                assertThat(actualIds).doesNotContainAnyElementsOf(unexpectedIds);
            }
        }

        @Test
        @DisplayName("when filtering by feedback_scores, then return only experiments with matching scores")
        void findByFeedbackScoresFilter() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create experiments with feedback scores
            var scoreName = "test_score_" + UUID.randomUUID().toString().substring(0, 8);
            var experimentScores = List.of(
                    new BigDecimal("0.8"),
                    new BigDecimal("0.5"),
                    new BigDecimal("0.3"));

            var experiments = new ArrayList<Experiment>();
            var traces = new ArrayList<Trace>();
            var experimentItems = new ArrayList<ExperimentItem>();
            var scores = new ArrayList<FeedbackScoreBatchItem>();

            for (int i = 0; i < experimentScores.size(); i++) {
                var experiment = generateExperiment();
                var trace = podamFactory.manufacturePojo(Trace.class);

                experiments.add(experiment);
                traces.add(trace);

                createAndAssert(experiment, apiKey, workspaceName);

                experimentItems.add(podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experiment.id())
                        .traceId(trace.id())
                        .build());

                scores.add(FeedbackScoreBatchItem.builder()
                        .id(trace.id())
                        .projectName(trace.projectName())
                        .name(scoreName)
                        .value(experimentScores.get(i))
                        .source(ScoreSource.SDK)
                        .build());
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            createAndAssert(new ExperimentItemsBatch(Set.copyOf(experimentItems)), apiKey, workspaceName);
            createScoreAndAssert(FeedbackScoreBatch.builder().scores(scores).build(), apiKey, workspaceName);

            // Test different filter operators
            var filterTestCases = List.of(
                    new FilterTestCase(Operator.GREATER_THAN, "0.7", experiments.get(0).id(), 1),
                    new FilterTestCase(Operator.EQUAL, "0.5", experiments.get(1).id(), 1),
                    new FilterTestCase(Operator.LESS_THAN, "0.4", experiments.get(2).id(), 1),
                    new FilterTestCase(Operator.GREATER_THAN_EQUAL, "0.5", null, 2),
                    new FilterTestCase(Operator.LESS_THAN_EQUAL, "0.5", null, 2));

            for (var testCase : filterTestCases) {
                var filters = List.of(ExperimentFilter.builder()
                        .field(ExperimentField.FEEDBACK_SCORES)
                        .operator(testCase.operator)
                        .key(scoreName)
                        .value(testCase.filterValue)
                        .build());

                try (var actualResponse = findExperiment(workspaceName, apiKey, 1, 10, null, null, false, null, null,
                        null, null, filters)) {

                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var actualPage = actualResponse.readEntity(ExperimentPage.class);

                    assertThat(actualPage.total()).isEqualTo(testCase.expectedCount);
                    assertThat(actualPage.content()).hasSize(testCase.expectedCount);

                    if (testCase.expectedExperimentId != null) {
                        assertThat(actualPage.content().getFirst().id()).isEqualTo(testCase.expectedExperimentId);
                    }
                }
            }
        }

        private record FilterTestCase(Operator operator, String filterValue, UUID expectedExperimentId,
                int expectedCount) {
        }

        @ParameterizedTest
        @MethodSource("feedbackScoresEmptyOperators")
        @DisplayName("when filtering by feedback_scores with IS_EMPTY/IS_NOT_EMPTY, then return correct experiments")
        void findByFeedbackScoresEmptyFilter(Operator operator, boolean expectExperimentWithScores) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create 2 experiments
            var experimentWithScores = generateExperiment();
            var experimentWithoutScores = generateExperiment();

            createAndAssert(experimentWithScores, apiKey, workspaceName);
            createAndAssert(experimentWithoutScores, apiKey, workspaceName);

            // Create traces
            var traceWithScores = podamFactory.manufacturePojo(Trace.class);
            var traceWithoutScores = podamFactory.manufacturePojo(Trace.class);

            traceResourceClient.batchCreateTraces(List.of(traceWithScores, traceWithoutScores), apiKey, workspaceName);

            // Create experiment items
            var experimentItemWithScores = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experimentWithScores.id())
                    .traceId(traceWithScores.id())
                    .build();
            var experimentItemWithoutScores = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experimentWithoutScores.id())
                    .traceId(traceWithoutScores.id())
                    .build();

            createAndAssert(new ExperimentItemsBatch(Set.of(experimentItemWithScores, experimentItemWithoutScores)),
                    apiKey, workspaceName);

            // Create feedback score only for one trace
            var scoreName = "empty_test_score_" + UUID.randomUUID().toString().substring(0, 8);
            var score = FeedbackScoreBatchItem.builder()
                    .id(traceWithScores.id())
                    .projectName(traceWithScores.projectName())
                    .name(scoreName)
                    .value(new BigDecimal("0.9"))
                    .source(ScoreSource.SDK)
                    .build();

            createScoreAndAssert(FeedbackScoreBatch.builder()
                    .scores(List.of(score))
                    .build(), apiKey, workspaceName);

            // Filter by feedback_scores with IS_EMPTY or IS_NOT_EMPTY
            var filters = List.of(ExperimentFilter.builder()
                    .field(ExperimentField.FEEDBACK_SCORES)
                    .operator(operator)
                    .key(scoreName)
                    .value("")
                    .build());

            try (var actualResponse = findExperiment(workspaceName, apiKey, 1, 10, null, null, false, null, null,
                    null, null, filters)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualPage = actualResponse.readEntity(ExperimentPage.class);

                assertThat(actualPage.total()).isEqualTo(1);
                assertThat(actualPage.content()).hasSize(1);

                var expectedExperimentId = expectExperimentWithScores
                        ? experimentWithScores.id()
                        : experimentWithoutScores.id();
                assertThat(actualPage.content().getFirst().id()).isEqualTo(expectedExperimentId);
            }
        }

        private Stream<Arguments> feedbackScoresEmptyOperators() {
            return Stream.of(
                    Arguments.of(Operator.IS_NOT_EMPTY, true),
                    Arguments.of(Operator.IS_EMPTY, false));
        }

        @Test
        @DisplayName("when filtering by experiment_scores, then return only experiments with matching scores")
        void findByExperimentScoresFilter() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create experiments with experiment_scores
            var scoreName = "exp_score_" + UUID.randomUUID().toString().substring(0, 8);
            var experimentScores = List.of(
                    new BigDecimal("0.85"),
                    new BigDecimal("0.60"),
                    new BigDecimal("0.35"));

            var experiments = new ArrayList<Experiment>();

            for (int i = 0; i < experimentScores.size(); i++) {
                var experimentScore = ExperimentScore.builder()
                        .name(scoreName)
                        .value(experimentScores.get(i))
                        .build();

                var experiment = generateExperiment().toBuilder()
                        .experimentScores(List.of(experimentScore))
                        .build();

                experiments.add(experiment);
                createAndAssert(experiment, apiKey, workspaceName);
            }

            // Test different filter operators
            var filterTestCases = List.of(
                    new FilterTestCase(Operator.GREATER_THAN, "0.75", experiments.get(0).id(), 1),
                    new FilterTestCase(Operator.EQUAL, "0.60", experiments.get(1).id(), 1),
                    new FilterTestCase(Operator.LESS_THAN, "0.40", experiments.get(2).id(), 1),
                    new FilterTestCase(Operator.GREATER_THAN_EQUAL, "0.60", null, 2),
                    new FilterTestCase(Operator.LESS_THAN_EQUAL, "0.60", null, 2));

            for (var testCase : filterTestCases) {
                var filters = List.of(ExperimentFilter.builder()
                        .field(ExperimentField.EXPERIMENT_SCORES)
                        .operator(testCase.operator)
                        .key(scoreName)
                        .value(testCase.filterValue)
                        .build());

                try (var actualResponse = findExperiment(workspaceName, apiKey, 1, 10, null, null, false, null, null,
                        null, null, filters)) {

                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var actualPage = actualResponse.readEntity(ExperimentPage.class);

                    assertThat(actualPage.total()).isEqualTo(testCase.expectedCount);
                    assertThat(actualPage.content()).hasSize(testCase.expectedCount);

                    if (testCase.expectedExperimentId != null) {
                        assertThat(actualPage.content().getFirst().id()).isEqualTo(testCase.expectedExperimentId);
                    }
                }
            }
        }

        @ParameterizedTest
        @MethodSource("experimentScoresEmptyOperators")
        @DisplayName("when filtering by experiment_scores with IS_EMPTY/IS_NOT_EMPTY, then return correct experiments")
        void findByExperimentScoresEmptyFilter(Operator operator, boolean expectExperimentWithScores) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create experiment with experiment_scores
            var scoreName = "empty_exp_score_" + UUID.randomUUID().toString().substring(0, 8);
            var experimentScoreWithScore = ExperimentScore.builder()
                    .name(scoreName)
                    .value(new BigDecimal("0.95"))
                    .build();

            var experimentWithScores = generateExperiment().toBuilder()
                    .experimentScores(List.of(experimentScoreWithScore))
                    .build();

            createAndAssert(experimentWithScores, apiKey, workspaceName);

            // Create experiment without experiment_scores
            var experimentWithoutScores = generateExperiment().toBuilder()
                    .experimentScores(null)
                    .build();

            createAndAssert(experimentWithoutScores, apiKey, workspaceName);

            // Filter by experiment_scores with IS_EMPTY or IS_NOT_EMPTY
            var filters = List.of(ExperimentFilter.builder()
                    .field(ExperimentField.EXPERIMENT_SCORES)
                    .operator(operator)
                    .key(scoreName)
                    .value("")
                    .build());

            try (var actualResponse = findExperiment(workspaceName, apiKey, 1, 10, null, null, false, null, null,
                    null, null, filters)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualPage = actualResponse.readEntity(ExperimentPage.class);

                assertThat(actualPage.total()).isEqualTo(1);
                assertThat(actualPage.content()).hasSize(1);

                var expectedExperimentId = expectExperimentWithScores
                        ? experimentWithScores.id()
                        : experimentWithoutScores.id();
                assertThat(actualPage.content().getFirst().id()).isEqualTo(expectedExperimentId);
            }
        }

        private Stream<Arguments> experimentScoresEmptyOperators() {
            return Stream.of(
                    Arguments.of(Operator.IS_NOT_EMPTY, true),
                    Arguments.of(Operator.IS_EMPTY, false));
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
                    .collect(groupingBy(FeedbackScoreItem::id));

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
                    .collect(groupingBy(FeedbackScoreItem::id));

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

            // Since we mock the EventBus, we need to manually trigger the deletion of the trace items
            ArgumentCaptor<TracesDeleted> experimentCaptor = ArgumentCaptor.forClass(TracesDeleted.class);
            Mockito.verify(defaultEventBus).post(experimentCaptor.capture());
            assertThat(experimentCaptor.getValue().traceIds()).isEqualTo(Set.of(trace6.id()));
            assertThat(experimentCaptor.getValue().workspaceId()).isEqualTo(workspaceId);

            traceDeletedListener.onTracesDeleted(TracesDeleted.builder()
                    .traceIds(Set.of(trace6.id()))
                    .workspaceId(workspaceId)
                    .userName(USER)
                    .build());

            List<ExperimentItem> experimentExpected = experimentItems
                    .stream()
                    .filter(item -> !item.traceId().equals(trace6.id()))
                    .toList();

            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(
                    List.of(expectedExperiment), experimentExpected);

            var page = 1;
            var pageSize = 1;

            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
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
            });
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
                    .totalEstimatedCostAvg(getTotalEstimatedCostAvg(spans))
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
                    .collect(groupingBy(Map.Entry::getKey, averagingLong(Map.Entry::getValue)));
        }

        private BigDecimal getTotalEstimatedCostAvg(List<Span> spans) {

            BigDecimal accumulated = spans.stream()
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal::add)
                    .orElse(BigDecimal.ZERO);

            return accumulated.divide(BigDecimal.valueOf(spans.size()), ValidationUtils.SCALE, RoundingMode.HALF_UP);
        }

        private BigDecimal getTotalEstimatedCost(List<Span> spans) {
            return spans.stream()
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
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
                    .collect(groupingBy(FeedbackScoreItem::id));

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
                        PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());
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
                        PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());
                        PromptVersionLink versionLink2 = buildVersionLink(promptVersion2, prompt2.name());

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
                                    .build()),
                    arguments(
                            Comparator.comparing((Experiment e) -> e.duration().p50())
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field("duration.p50").direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing((Experiment e) -> e.duration().p50()).reversed()
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field("duration.p50").direction(Direction.DESC)
                                    .build()),
                    arguments(
                            Comparator.comparing((Experiment e) -> e.tags().stream().toList(),
                                    ListComparators.ascending())
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing((Experiment e) -> e.tags().stream().toList(),
                                    ListComparators.descending())
                                    .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                    .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()));
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

            var scoreForTrace = PodamFactoryUtils.manufacturePojoList(podamFactory,
                    FeedbackScoreBatchItem.class);
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
                    null, null, null);
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        @DisplayName("when sorting by feedback scores, then return page")
        void whenSortingByFeedbackScores__thenReturnPage(Direction direction) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var scoreForTrace = PodamFactoryUtils.manufacturePojoList(podamFactory,
                    FeedbackScoreBatchItem.class);
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
                    null, null, null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"feedback_scores", "feedback_score.dsfsdfd", "feedback_scores."})
        void whenSortingByInvalidFeedbackScoresPattern__thenIgnoreAndReturnPage(String field) {

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
                    null,
                    null)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        @DisplayName("when sorting by experiment scores, then return page")
        void whenSortingByExperimentScores__thenReturnPage(Direction direction) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var scoreName = "accuracy";
            var experiments = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        var experiment = experimentResourceClient.createPartialExperiment()
                                .lastUpdatedBy(USER)
                                .createdBy(USER)
                                .build();
                        var experimentId = experimentResourceClient.create(experiment, apiKey, workspaceName);
                        // Set different experiment_scores values for each experiment
                        var scoreValue = new BigDecimal(String.valueOf(0.90 + i * 0.01)); // 0.90, 0.91, 0.92, 0.93, 0.94
                        var experimentScores = List.of(
                                ExperimentScore.builder()
                                        .name(scoreName)
                                        .value(scoreValue)
                                        .build());
                        experimentResourceClient.updateExperiment(experimentId,
                                ExperimentUpdate.builder().experimentScores(experimentScores).build(),
                                apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
                        return getExperiment(experimentId, workspaceName, apiKey);
                    })
                    .toList();

            var sortingField = new SortingField(
                    "experiment_scores.%s".formatted(scoreName),
                    direction);

            Comparator<Experiment> comparing = Comparator.comparing(
                    (Experiment experiment) -> experiment.experimentScores()
                            .stream()
                            .filter(score -> score.name().equals(scoreName))
                            .findFirst()
                            .map(ExperimentScore::value)
                            .orElse(null),
                    direction == Direction.ASC
                            ? Comparator.nullsFirst(Comparator.naturalOrder())
                            : Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(Experiment::id).reversed());

            var expectedExperiments = experiments
                    .stream()
                    .sorted(comparing)
                    .toList();

            findAndAssert(workspaceName, 1, expectedExperiments.size(), null, null, expectedExperiments,
                    expectedExperiments.size(), List.of(), apiKey, false, Map.of(), null, List.of(sortingField),
                    null, null, null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"experiment_scores", "experiment_score.invalid", "experiment_scores."})
        @DisplayName("when sorting by experiment scores with invalid field, then ignore and return success")
        void whenSortingByExperimentScoresWithInvalidField_thenIgnoreAndReturnSuccess(String field) {
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
                    null,
                    null)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }
        }

        @Test
        @DisplayName("legacy experiments with unknown status should show up as completed")
        void testUnknownStatusExperimentCanBeRetrieved() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiment = experimentResourceClient.createPartialExperiment().build();

            // simulate legacy experiment insertion with 'unknown' status directly in ClickHouse
            Mono<Void> insertResult = clickHouseTemplate.nonTransaction(connection -> {
                String insertSql = """
                        INSERT INTO experiments (
                            id,
                            dataset_id,
                            name,
                            workspace_id,
                            metadata,
                            created_by,
                            last_updated_by,
                            prompt_version_id,
                            prompt_id,
                            prompt_versions,
                            type,
                            optimization_id,
                            status
                        ) VALUES (
                            :id, :dataset_id, :name, :workspace_id, :metadata, :created_by, :last_updated_by,
                            :prompt_version_id, :prompt_id, :prompt_versions, :type, :optimization_id, 'unknown'
                        )
                        """;

                Statement statement = connection.createStatement(insertSql)
                        .bind("id", experiment.id())
                        .bind("dataset_id", experiment.datasetId())
                        .bind("name", experiment.name())
                        .bind("workspace_id", workspaceId)
                        .bind("metadata", experiment.metadata().toString())
                        .bind("created_by", USER)
                        .bind("last_updated_by", USER)
                        .bindNull("prompt_version_id", UUID.class)
                        .bindNull("prompt_id", UUID.class)
                        .bind("prompt_versions", new UUID[]{})
                        .bind("type", ExperimentType.REGULAR)
                        .bind("optimization_id", "");

                return Mono.from(statement.execute())
                        .then();
            });

            // Execute the insertion
            StepVerifier.create(insertResult)
                    .verifyComplete();

            var actual = experimentResourceClient.streamExperiments(ExperimentStreamRequest.builder()
                    .limit(5).name(experiment.name()).build(), apiKey, workspaceName);
            assertThat(actual).hasSize(1);
            assertThat(actual.getFirst().status()).isEqualTo(ExperimentStatus.COMPLETED);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GroupExperimentsAggregations {

        @ParameterizedTest
        @MethodSource
        void findGroupsAggregationsWithFilter__happyFlow(boolean withFilter, List<GroupBy> groups,
                Function<Experiment, ExperimentFilter> filterFunction,
                Function<Experiment, Predicate<Experiment>> predicateFunction) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasets = PodamFactoryUtils.manufacturePojoList(podamFactory, Dataset.class);
            Random random = new Random();

            List<String> metadatas = List.of("{\"provider\":\"openai\",\"model\":\"gpt-4\"}",
                    "{\"provider\":\"anthropic\",\"model\":\"claude-3\"}",
                    "{\"provider\":\"openai\",\"model\":\"gpt-3.5\"}");

            List<Set<String>> tagsList = List.of(
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class),
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class),
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class));

            Map<UUID, List<ExperimentItem>> experimentToItems = new HashMap<>();
            List<Trace> tracesAll = new ArrayList<>();
            Map<UUID, List<Span>> traceToSpans = new HashMap<>();

            var allExperiments = datasets.stream().flatMap(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                var prompt = podamFactory.manufacturePojo(Prompt.class);
                PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
                PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());

                var experiments = experimentResourceClient.generateExperimentList()
                        .stream()
                        .map(experiment -> experiment.toBuilder()
                                .datasetId(dataset.id())
                                .datasetName(dataset.name())
                                .promptVersion(versionLink)
                                .promptVersions(List.of(versionLink))
                                .metadata(JsonUtils
                                        .getJsonNodeFromString(metadatas.get(random.nextInt(metadatas.size()))))
                                .tags(tagsList.get(random.nextInt(tagsList.size())))
                                .build())
                        .toList();
                experiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

                // Create traces with different durations and setup spans with cost
                var traces = IntStream.range(0, 10)
                        .mapToObj(i -> createTraceWithDuration(random.nextInt(300)))
                        .toList();
                tracesAll.addAll(traces);
                traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

                // Create spans with cost for each trace
                var spans = traces.stream().map(trace -> {
                    var span = createSpanWithCost(trace, BigDecimal.valueOf(random.nextDouble()));
                    traceToSpans.put(trace.id(), List.of(span));
                    return span;
                }).toList();
                spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

                // Create feedback scores
                List<List<FeedbackScoreBatchItem>> allScores = traces.stream()
                        .map(trace -> makeTraceScoresWithSpecificValues(trace,
                                List.of(BigDecimal.valueOf(random.nextDouble()),
                                        BigDecimal.valueOf(random.nextDouble()))))
                        .toList();

                var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class)
                        .toBuilder()
                        .scores(allScores.stream().flatMap(List::stream).collect(Collectors.toList()))
                        .build();

                createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

                Set<ExperimentItem> experimentItems = IntStream.range(0, traces.size())
                        .mapToObj(i -> {
                            var experimentId = experiments.get(i % experiments.size()).id();
                            List<ExperimentItem> experimentItemList = experimentToItems.computeIfAbsent(
                                    experimentId, k -> new ArrayList<>());
                            ExperimentItem experimentItem = createExperimentItemWithFeedbackScores(
                                    experimentId,
                                    traces.get(i).id(),
                                    allScores.get(i));
                            experimentItemList.add(experimentItem);
                            return experimentItem;
                        })
                        .collect(Collectors.toSet());

                var experimentItemsBatch = ExperimentItemsBatch.builder()
                        .experimentItems(experimentItems)
                        .build();

                createAndAssert(experimentItemsBatch, apiKey, workspaceName);

                return experiments.stream();
            }).toList();

            // Call the aggregations endpoint
            var response = experimentResourceClient.findGroupsAggregations(
                    groups,
                    Set.of(ExperimentType.REGULAR),
                    withFilter ? List.of(filterFunction.apply(allExperiments.getFirst())) : null,
                    null,
                    apiKey,
                    workspaceName,
                    200);

            var expectedExperiments = withFilter
                    ? allExperiments.stream()
                            .filter(predicateFunction.apply(allExperiments.getFirst()))
                            .toList()
                    : allExperiments;

            // Build expected response
            var expectedResponse = ExperimentsTestUtils.buildExpectedGroupAggregationsResponse(
                    groups,
                    expectedExperiments,
                    experimentToItems,
                    traceToSpans,
                    tracesAll);

            assertThat(response)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedResponse);
        }

        private Stream<Arguments> findGroupsAggregationsWithFilter__happyFlow() {
            return Stream.of(
                    Arguments.of(false, List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("provider").type(FieldType.DICTIONARY).build()),
                            null, null),
                    Arguments.of(true, List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("provider").type(FieldType.DICTIONARY).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.DATASET_ID)
                                    .operator(Operator.EQUAL)
                                    .value(experiment.datasetId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.datasetId()
                                    .equals(experiment.datasetId())),
                    Arguments.of(true, List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.DATASET_ID)
                                    .operator(Operator.EQUAL)
                                    .value(experiment.datasetId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.datasetId()
                                    .equals(experiment.datasetId())),
                    Arguments.of(true, List
                            .of(GroupBy.builder().field(METADATA).key("provider").type(FieldType.DICTIONARY).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.PROMPT_IDS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.promptVersion().promptId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.promptVersion()
                                    .equals(experiment.promptVersion())),
                    Arguments.of(true,
                            List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                                    GroupBy.builder().field(METADATA).key("something").type(FieldType.DICTIONARY)
                                            .build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.PROMPT_IDS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.promptVersion().promptId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.promptVersion()
                                    .equals(experiment.promptVersion())),
                    // Test grouping by TAGS without filter
                    Arguments.of(false, List.of(GroupBy.builder().field(TAGS).type(FieldType.LIST).build()),
                            null, null),
                    // Test grouping by TAGS with DATASET_ID, no filter
                    Arguments.of(false, List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(TAGS).type(FieldType.LIST).build()),
                            null, null),
                    // Test grouping by TAGS with filter on TAGS using CONTAINS
                    Arguments.of(true, List.of(GroupBy.builder().field(TAGS).type(FieldType.LIST).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.TAGS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.tags().iterator().next())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.tags() != null
                                    && exp.tags().contains(experiment.tags().stream().sorted().findFirst().orElse(""))),
                    // Test grouping by DATASET_ID and TAGS with filter on TAGS using CONTAINS
                    Arguments.of(true,
                            List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                                    GroupBy.builder().field(TAGS).type(FieldType.LIST).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.TAGS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.tags().iterator().next())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.tags() != null
                                    && exp.tags()
                                            .contains(experiment.tags().stream().sorted().findFirst().orElse(""))));
        }

        @ParameterizedTest
        @MethodSource
        void groupExperimentsAggregationsInvalidGroupingsShouldFail(List<GroupBy> groups) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            experimentResourceClient.findGroupsAggregations(
                    groups,
                    Set.of(ExperimentType.REGULAR), null, null, apiKey, workspaceName, 400);
        }

        private Stream<Arguments> groupExperimentsAggregationsInvalidGroupingsShouldFail() {
            return Stream.of(
                    Arguments.of(List.of(GroupBy.builder().field("NOT_SUPPORTED").type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("model[0].year").type(FieldType.DICTIONARY).build())),
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.LIST).build())),
                    Arguments.of(List.of(GroupBy.builder().field(METADATA).type(FieldType.DICTIONARY).build())),
                    Arguments.of(List.of(GroupBy.builder().field(TAGS).type(FieldType.DATE_TIME).build())));
        }

        @Test
        void groupExperimentsAggregationsMissingGroupingsShouldFail() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            experimentResourceClient.findGroupsAggregations(
                    null,
                    Set.of(ExperimentType.REGULAR), null, null, apiKey, workspaceName, 400);
        }

        private Trace createTraceWithDuration(long durationMillis) {
            var startTime = Instant.now();
            return podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .startTime(startTime)
                    .endTime(startTime.plusMillis(durationMillis))
                    .build();
        }

        private Span createSpanWithCost(Trace trace, BigDecimal cost) {
            return podamFactory.manufacturePojo(Span.class).toBuilder()
                    .traceId(trace.id())
                    .projectName(trace.projectName())
                    .type(SpanType.llm)
                    .totalEstimatedCost(cost)
                    .build();
        }

        private List<FeedbackScoreBatchItem> makeTraceScoresWithSpecificValues(Trace trace, List<BigDecimal> values) {
            var scoreNames = List.of("accuracy", "helpfulness");
            return IntStream.range(0, Math.min(scoreNames.size(), values.size()))
                    .<FeedbackScoreBatchItem>mapToObj(i -> FeedbackScoreBatchItem.builder()
                            .id(trace.id())
                            .projectName(trace.projectName())
                            .name(scoreNames.get(i))
                            .value(values.get(i))
                            .source(ScoreSource.SDK)
                            .build())
                    .collect(toList());
        }

        private ExperimentItem createExperimentItemWithFeedbackScores(UUID experimentId, UUID traceId,
                List<FeedbackScoreBatchItem> scores) {
            return podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experimentId)
                    .traceId(traceId)
                    .feedbackScores(scores.stream()
                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                            .toList())
                    .build();
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GroupExperiments {

        @ParameterizedTest
        @MethodSource
        void groupExperiments(List<GroupBy> groups) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Random random = new Random();

            var datasets = PodamFactoryUtils.manufacturePojoList(podamFactory, Dataset.class);

            List<Set<String>> tagsList = List.of(
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class),
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class),
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class));

            var allExperiments = datasets.stream().flatMap(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                var experiments = experimentResourceClient.generateExperimentList()
                        .stream()
                        .map(experiment -> experiment.toBuilder()
                                .datasetId(dataset.id())
                                .datasetName(dataset.name())
                                .metadata(JsonUtils
                                        .getJsonNodeFromString(
                                                "{\"provider\":\"openai\",\"model\":[{\"year\":%s,\"version\":\"OpenAI, "
                                                        .formatted(random.nextBoolean() ? "2024" : "2025") +
                                                        "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}"))
                                .tags(tagsList.get(random.nextInt(tagsList.size())))
                                .build())
                        .toList();
                experiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

                return experiments.stream();
            }).toList();

            var response = experimentResourceClient.findGroups(
                    groups,
                    Set.of(ExperimentType.REGULAR), null, null, apiKey, workspaceName, 200);

            var expectedResponse = ExperimentsTestUtils.buildExpectedGroupResponse(
                    groups,
                    allExperiments);
            assertThat(response).isEqualTo(expectedResponse);
        }

        @ParameterizedTest
        @MethodSource
        void groupExperimentsWithFilter(List<GroupBy> groups, Function<Experiment, ExperimentFilter> filterFunction,
                Function<Experiment, Predicate<Experiment>> predicateFunction) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Random random = new Random();

            var datasets = PodamFactoryUtils.manufacturePojoList(podamFactory, Dataset.class);

            List<Set<String>> tagsList = List.of(
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class),
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class),
                    PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class));

            var allExperiments = datasets.stream().flatMap(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                var prompt = podamFactory.manufacturePojo(Prompt.class);
                PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
                PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());

                var experiments = experimentResourceClient.generateExperimentList()
                        .stream()
                        .map(experiment -> experiment.toBuilder()
                                .datasetId(dataset.id())
                                .promptVersion(versionLink)
                                .promptVersions(List.of(versionLink))
                                .datasetName(dataset.name())
                                .metadata(JsonUtils
                                        .getJsonNodeFromString(
                                                "{\"provider\":\"openai\",\"model\":[{\"year\":%s,\"version\":\"OpenAI, "
                                                        .formatted(random.nextBoolean() ? "2024" : "2025") +
                                                        "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}"))
                                .tags(tagsList.get(random.nextInt(tagsList.size())))
                                .build())
                        .toList();
                experiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

                return experiments.stream();
            }).toList();

            var response = experimentResourceClient.findGroups(
                    groups,
                    Set.of(ExperimentType.REGULAR), List.of(filterFunction.apply(allExperiments.getFirst())), null,
                    apiKey, workspaceName, 200);

            var expectedExperiments = allExperiments.stream()
                    .filter(predicateFunction.apply(allExperiments.getFirst()))
                    .toList();

            var expectedResponse = ExperimentsTestUtils.buildExpectedGroupResponse(
                    groups,
                    expectedExperiments);
            assertThat(response).isEqualTo(expectedResponse);
        }

        @Test
        void groupExperimentsWithDeletedDataset() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Random random = new Random();

            var datasets = PodamFactoryUtils.manufacturePojoList(podamFactory, Dataset.class);

            var allExperiments = datasets.stream().flatMap(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

                var experiments = experimentResourceClient.generateExperimentList()
                        .stream()
                        .map(experiment -> experiment.toBuilder()
                                .datasetId(dataset.id())
                                .datasetName(dataset.name())
                                .metadata(JsonUtils
                                        .getJsonNodeFromString(
                                                "{\"provider\":\"openai\",\"model\":[{\"year\":%s,\"version\":\"OpenAI, "
                                                        .formatted(random.nextBoolean() ? "2024" : "2025") +
                                                        "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}"))
                                .build())
                        .toList();
                experiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

                return experiments.stream();
            }).toList();

            datasetResourceClient.deleteDataset(datasets.get(0).id(), apiKey, workspaceName);

            var groups = List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                    GroupBy.builder().field(METADATA).key("model[0].year").type(FieldType.DICTIONARY).build());

            var response = experimentResourceClient.findGroups(
                    groups,
                    Set.of(ExperimentType.REGULAR), null, null, apiKey, workspaceName, 200);

            allExperiments = allExperiments.stream()
                    .map(experiment -> experiment.datasetId() == datasets.get(0).id()
                            ? experiment.toBuilder().datasetName(null).build()
                            : experiment)
                    .toList();

            var expectedResponse = ExperimentsTestUtils.buildExpectedGroupResponse(
                    groups,
                    allExperiments);
            assertThat(response).isEqualTo(expectedResponse);
        }

        @ParameterizedTest
        @MethodSource
        void groupExperimentsInvalidGroupingsShouldFail(List<GroupBy> groups) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            experimentResourceClient.findGroups(
                    groups,
                    Set.of(ExperimentType.REGULAR), null, null, apiKey, workspaceName, 400);
        }

        @Test
        void groupExperimentsMissingGroupingsShouldFail() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            experimentResourceClient.findGroups(
                    null,
                    Set.of(ExperimentType.REGULAR), null, null, apiKey, workspaceName, 400);
        }

        private Stream<Arguments> groupExperimentsInvalidGroupingsShouldFail() {
            return Stream.of(
                    Arguments.of(List.of(GroupBy.builder().field("NOT_SUPPORTED").type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("model[0].year").type(FieldType.DICTIONARY).build())),
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.LIST).build())),
                    Arguments.of(List.of(GroupBy.builder().field(METADATA).type(FieldType.DICTIONARY).build())),
                    Arguments.of(List.of(GroupBy.builder().field(TAGS).type(FieldType.DICTIONARY).build())),
                    Arguments.of(List.of(GroupBy.builder().field(TAGS).type(FieldType.DATE_TIME).build())));
        }

        private Stream<Arguments> groupExperiments() {
            return Stream.of(
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("model[0].year").type(FieldType.DICTIONARY).build())),
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build())),
                    Arguments.of(List
                            .of(GroupBy.builder().field(METADATA).key("provider").type(FieldType.DICTIONARY).build())),
                    Arguments.of(List
                            .of(GroupBy.builder().field(METADATA).key("invalid key").type(FieldType.DICTIONARY)
                                    .build())),
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("something").type(FieldType.DICTIONARY).build())),
                    // Test grouping by TAGS
                    Arguments.of(List.of(GroupBy.builder().field(TAGS).type(FieldType.LIST).build())),
                    // Test grouping by TAGS with DATASET_ID
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(TAGS).type(FieldType.LIST).build())));
        }

        private Stream<Arguments> groupExperimentsWithFilter() {
            return Stream.of(
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                            GroupBy.builder().field(METADATA).key("model[0].year").type(FieldType.DICTIONARY).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.DATASET_ID)
                                    .operator(Operator.EQUAL)
                                    .value(experiment.datasetId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.datasetId()
                                    .equals(experiment.datasetId())),
                    Arguments.of(List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.DATASET_ID)
                                    .operator(Operator.EQUAL)
                                    .value(experiment.datasetId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.datasetId()
                                    .equals(experiment.datasetId())),
                    Arguments.of(List
                            .of(GroupBy.builder().field(METADATA).key("provider").type(FieldType.DICTIONARY).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.PROMPT_IDS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.promptVersion().promptId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.promptVersion()
                                    .equals(experiment.promptVersion())),
                    Arguments.of(
                            List.of(GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build(),
                                    GroupBy.builder().field(METADATA).key("something").type(FieldType.DICTIONARY)
                                            .build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.PROMPT_IDS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.promptVersion().promptId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.promptVersion()
                                    .equals(experiment.promptVersion())),
                    Arguments.of(List.of(GroupBy.builder().field(TAGS).type(FieldType.LIST).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.TAGS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.tags().iterator().next())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.tags()
                                    .contains(experiment.tags().iterator().next())),
                    Arguments.of(
                            List.of(GroupBy.builder().field(TAGS).type(FieldType.LIST).build(),
                                    GroupBy.builder().field(DATASET_ID).type(FieldType.STRING).build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.DATASET_ID)
                                    .operator(Operator.EQUAL)
                                    .value(experiment.datasetId().toString())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.datasetId()
                                    .equals(experiment.datasetId())),
                    Arguments.of(
                            List.of(GroupBy.builder().field(TAGS).type(FieldType.LIST).build(),
                                    GroupBy.builder().field(METADATA).key("provider").type(FieldType.DICTIONARY)
                                            .build()),
                            (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                    .field(ExperimentField.TAGS)
                                    .operator(Operator.CONTAINS)
                                    .value(experiment.tags().iterator().next())
                                    .build(),
                            (Function<Experiment, Predicate<Experiment>>) experiment -> exp -> exp.tags()
                                    .contains(experiment.tags().iterator().next())));
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
                .mapToObj(i -> podamFactory.manufacturePojo(Trace.class).toBuilder()
                        .startTime(Instant.now())
                        .endTime(Instant.now().plus(PodamUtils.getIntegerInRange(100, 2000), ChronoUnit.MILLIS))
                        .build())
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

    private static PromptVersionLink buildVersionLink(PromptVersion promptVersion, String promptName) {
        return PromptVersionLink.builder()
                .id(promptVersion.id())
                .commit(promptVersion.commit())
                .promptId(promptVersion.promptId())
                .promptName(promptName)
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
                unexpectedExperiments, apiKey, datasetDeleted, expectedScoresPerExperiment, promptId, null, null, null,
                null);
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
                optimizationId, types, null);
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
            Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters) {

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

        if (CollectionUtils.isNotEmpty(filters)) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
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
            Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters) {
        try (var actualResponse = findExperiment(
                workspaceName, apiKey, page, pageSize, datasetId, name, datasetDeleted, promptId, sortingFields,
                optimizationId, types, filters)) {
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
                                    return buildVersionLink(promptVersion, prompt.name());
                                })
                                .toList();
                        var experiment = experimentResourceClient.createPartialExperiment()
                                .datasetName(datasetName)
                                .name(name)
                                .promptVersion(promptVersions.getFirst())
                                .promptVersions(promptVersions)
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
                                .filter(item -> item.experimentId().equals(experiment.id()))
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
                    .collect(groupingBy(FeedbackScoreItem::id));

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
                    .collect(groupingBy(FeedbackScoreItem::id));

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
                    promptVersion.promptId(), promptName);

            var expectedExperiment = experimentResourceClient.createPartialExperiment()
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
                    .promptVersion(new PromptVersionLink(GENERATOR.generate(), null, GENERATOR.generate(), null))
                    .datasetVersionId(null)
                    .datasetVersionSummary(null)
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
                    .collect(groupingBy(FeedbackScoreItem::id));

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

            // Since we mock the EventBus, we need to manually trigger the deletion of the trace items
            ArgumentCaptor<TracesDeleted> experimentCaptor = ArgumentCaptor.forClass(TracesDeleted.class);
            Mockito.verify(defaultEventBus).post(experimentCaptor.capture());
            assertThat(experimentCaptor.getValue().traceIds()).isEqualTo(Set.of(trace6.id()));
            assertThat(experimentCaptor.getValue().workspaceId()).isEqualTo(workspaceId);

            traceDeletedListener.onTracesDeleted(TracesDeleted.builder()
                    .traceIds(Set.of(trace6.id()))
                    .workspaceId(workspaceId)
                    .userName(USER)
                    .build());

            List<BigDecimal> quantities = getQuantities(Stream.of(trace1, trace2, trace3, trace4, trace5));

            var expectedExperiment = experiment.toBuilder()
                    .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                    .build();

            Map<String, BigDecimal> expectedScores = getExpectedScores(
                    traceIdToScoresMap.entrySet()
                            .stream()
                            .filter(e -> !e.getKey().equals(trace6.id()))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));

            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                Experiment actualExperiment = getAndAssert(experiment.id(), expectedExperiment, workspaceName, apiKey);

                assertThat(actualExperiment.traceCount()).isEqualTo(traces.size()); // decide if we should count deleted traces

                Map<String, BigDecimal> actual = getScoresMap(actualExperiment);

                assertThat(actual)
                        .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                        .isEqualTo(expectedScores);
            });
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

    private Map<String, BigDecimal> getExpectedScores(
            Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap) {
        return traceIdToScoresMap
                .values()
                .stream()
                .flatMap(Collection::stream)
                .collect(groupingBy(
                        FeedbackScoreItem::name,
                        mapping(FeedbackScoreItem::value, toList())))
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
                                traceIdToScoresMap.get(traces.get(i / totalNumberOfScoresPerTrace).id())
                                        .stream()

                                        .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                        .toList())
                        .build())
                .toList();
    }

    private List<FeedbackScoreBatchItem> copyScoresFrom(List<FeedbackScoreBatchItem> scoreForTrace,
            Trace trace) {
        return scoreForTrace
                .stream()
                .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                        .id(trace.id())
                        .projectName(trace.projectName())
                        .value(podamFactory.manufacturePojo(BigDecimal.class).abs())
                        .build())
                .collect(toList());
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
            var createRequest = createItemsWithoutTrace();

            createAndAssert(createRequest, API_KEY, TEST_WORKSPACE);
            createRequest.experimentItems()
                    .forEach(item -> getAndAssert(item, TEST_WORKSPACE, API_KEY));

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

    private ExperimentItemsBatch createItemsWithoutTrace() {
        ExperimentItemsBatch itemsBatch = getExperimentItemsBatch();
        return itemsBatch.toBuilder()
                .experimentItems(itemsBatch.experimentItems().stream()
                        .map(experimentItem -> experimentItem.toBuilder()
                                .traceVisibilityMode(null)
                                .build())
                        .collect(toSet()))
                .build();
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
                    .collect(groupingBy(FeedbackScoreItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream)).toList())
                    .build();

            Instant scoreCreatedAt = Instant.now();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            // Add comments to trace
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        Instant commentCreatedAt = Instant.now();
                        return traceResourceClient.generateAndCreateComment(traceWithScores2.getKey().id(), apiKey,
                                workspaceName, HttpStatus.SC_CREATED).toBuilder()
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
                                .createdAt(commentCreatedAt)
                                .lastUpdatedAt(commentCreatedAt)
                                .build();
                    })
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
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                    .map(score -> score.toBuilder()
                                            .createdBy(USER)
                                            .lastUpdatedBy(USER)
                                            .lastUpdatedAt(scoreCreatedAt)
                                            .createdAt(scoreCreatedAt)
                                            .build())
                                    .toList())
                            .comments(expectedComments)
                            .totalEstimatedCost(null)
                            .usage(null)
                            .createdBy(USER)
                            .lastUpdatedBy(USER)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traceWithScores2.getLeft().startTime(), traceWithScores2.getLeft().endTime()))
                            .build())
                    .toList();
            var expectedExperimentItems2 = expectedExperimentItems.subList(limit, size).stream()
                    .map(experimentItem -> experimentItem.toBuilder()
                            .input(traceWithScores1.getLeft().input())
                            .output(traceWithScores1.getLeft().output())
                            .feedbackScores(traceWithScores1.getRight().stream()
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                    .map(score -> score.toBuilder()
                                            .createdBy(USER)
                                            .lastUpdatedBy(USER)
                                            .lastUpdatedAt(scoreCreatedAt)
                                            .createdAt(scoreCreatedAt)
                                            .build())
                                    .toList())
                            .comments(null)
                            .totalEstimatedCost(null)
                            .usage(null)
                            .createdBy(USER)
                            .lastUpdatedBy(USER)
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
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
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
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
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
                                    .collect(toMap(
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

        private Pair<Trace, List<FeedbackScoreBatchItem>> createTraceWithScores(String apiKey,
                String workspaceName) {
            var trace = podamFactory.manufacturePojo(Trace.class);
            traceResourceClient.createTrace(trace, apiKey, workspaceName);

            // Creating 5 scores peach each of the two traces above
            return Pair.of(trace,
                    PodamFactoryUtils.manufacturePojoList(podamFactory, FeedbackScoreBatchItem.class)
                            .stream()
                            .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                                    .id(trace.id())
                                    .projectName(trace.projectName())
                                    .value(podamFactory.manufacturePojo(BigDecimal.class))
                                    .build())
                            .collect(toList()));
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
                                    .comments(null)
                                    .createdBy(USER)
                                    .lastUpdatedBy(USER)
                                    .feedbackScores(null)
                                    .input(null)
                                    .output(null)
                                    .traceVisibilityMode(null)
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
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "workspace-" + UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = podamFactory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);
            project = project.toBuilder().id(projectId).build();
            var names = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var otherNames = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);

            // Create multiple values feedback scores
            var multipleValuesFeedbackScores = names.subList(0, names.size() - 1);
            var multipleValuesFeedbackScoreList = traceResourceClient.createMultiValueScores(
                    multipleValuesFeedbackScores, project, apiKey, workspaceName);
            var singleValueScores = traceResourceClient.createMultiValueScores(
                    List.of(names.getLast()), project, apiKey, workspaceName);

            // Create experiment, including experiment feedback scores
            var experiment = createExperimentsItems(
                    apiKey, workspaceName, multipleValuesFeedbackScoreList, singleValueScores);

            // Create unexpected feedback scores, both feedback and experiment scores
            var unexpectedProject = podamFactory.manufacturePojo(Project.class);
            var unexpectedScores = traceResourceClient.createMultiValueScores(
                    otherNames, unexpectedProject, apiKey, workspaceName);
            createExperimentsItems(apiKey, workspaceName, unexpectedScores, List.of());

            fetchAndAssertResponse(userExperimentId, experiment, names, otherNames, apiKey, workspaceName);
        }
    }

    private void fetchAndAssertResponse(
            boolean userExperimentId,
            Experiment experiment,
            List<String> names,
            List<String> otherNames,
            String apiKey,
            String workspaceName) {
        var webTarget = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("feedback-scores")
                .path("names");
        if (userExperimentId) {
            var ids = JsonUtils.writeValueAsString(List.of(experiment.id()));
            webTarget = webTarget.queryParam("experiment_ids", ids);
        }
        try (var actualResponse = webTarget.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            var actualEntity = actualResponse.readEntity(FeedbackScoreNames.class);
            if (userExperimentId) {
                var actualExperimentScores = actualEntity.scores().stream()
                        .filter(score -> "experiment_scores".equals(score.type()))
                        .map(FeedbackScoreNames.ScoreName::name)
                        .toList();
                var expectedExperimentScores = experiment.experimentScores().stream()
                        .map(ExperimentScore::name)
                        .toList();
                assertThat(actualExperimentScores).containsExactlyInAnyOrderElementsOf(expectedExperimentScores);
            }
            var expectedNames = userExperimentId
                    ? names
                    : Stream.of(names, otherNames).flatMap(List::stream).toList();
            assertFeedbackScoreNames(actualEntity, expectedNames);
        }
    }

    private Experiment createExperimentsItems(String apiKey,
            String workspaceName,
            List<List<FeedbackScoreBatchItem>> multipleValuesFeedbackScoreList,
            List<List<FeedbackScoreBatchItem>> singleValueScores) {
        var experiment = experimentResourceClient.createPartialExperiment().build();
        experimentResourceClient.create(experiment, apiKey, workspaceName);
        var experimentId = experiment.id();
        var experimentItems = Stream.of(multipleValuesFeedbackScoreList, singleValueScores)
                .flatMap(List::stream)
                .flatMap(List::stream)
                .map(FeedbackScoreItem::id)
                .distinct()
                .map(traceId -> podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .traceId(traceId)
                        .experimentId(experimentId)
                        .build())
                .collect(Collectors.toSet());
        experimentResourceClient.createExperimentItem(experimentItems, apiKey, workspaceName);
        return experiment;
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

            ExperimentTestAssertions.assertExperimentResults(actualExperimentItem, expectedExperimentItem, USER);
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
        var actualExperimentItems = experimentResourceClient.streamExperimentItems(request, apiKey, workspaceName);
        ExperimentTestAssertions.assertExperimentResults(actualExperimentItems, expectedExperimentItems,
                unexpectedExperimentItems, USER);
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
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a bulk upload request with a single item
            var trace = createTrace();

            var span = creatrSpan();

            var feedbackScore = createScore();

            List<ExperimentItem> expectedItems = getExpectedItem(datasetItem, trace, feedbackScore, null);

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

            ExperimentTestAssertions.assertExperimentResultsIgnoringFields(actualExperimentItems, expectedItems,
                    Stream.concat(Arrays.stream(ExperimentTestAssertions.EXPERIMENT_ITEMS_IGNORED_FIELDS),
                            Stream.of("id", "experimentId"))
                            .toArray(String[]::new));
        }

        private List<ExperimentItem> getExpectedItem(DatasetItem datasetItem, Trace trace,
                FeedbackScore feedbackScore, JsonNode evaluateTaskResult) {
            return List.of(
                    ExperimentItem.builder()
                            .datasetItemId(datasetItem.id())
                            .traceId(Optional.ofNullable(trace).map(Trace::id).orElse(null))
                            .duration(Optional.ofNullable(trace)
                                    .map(t -> DurationUtils.getDurationInMillisWithSubMilliPrecision(t.startTime(),
                                            t.endTime()))
                                    .orElse(0.0))
                            .input(Optional.ofNullable(trace).map(Trace::input).orElse(null))
                            .output(Optional.ofNullable(trace).map(Trace::output).orElse(evaluateTaskResult))
                            .feedbackScores(List.of(feedbackScore))
                            .createdAt(Optional.ofNullable(trace).map(Trace::createdAt).orElse(null))
                            .lastUpdatedAt(Optional.ofNullable(trace).map(Trace::lastUpdatedAt).orElse(null))
                            .createdBy(USER)
                            .lastUpdatedBy(USER)
                            .traceVisibilityMode(trace == null ? VisibilityMode.HIDDEN : VisibilityMode.DEFAULT)
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
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a bulk upload request with a single item

            var feedbackScore = createScore();

            List<ExperimentItem> expectedItems = getExpectedItem(datasetItem, null, feedbackScore, null);

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
                    .concat(Arrays.stream(ExperimentTestAssertions.EXPERIMENT_ITEMS_IGNORED_FIELDS),
                            Stream.of("traceId", "id", "experimentId"))
                    .toArray(String[]::new);

            ExperimentTestAssertions.assertExperimentResultsIgnoringFields(actualExperimentItems, expectedItems,
                    ignoringFields);

            Trace trace = traceResourceClient.getById(actualExperimentItems.getFirst().traceId(), TEST_WORKSPACE,
                    API_KEY);

            assertThat(trace).isNotNull();
            assertThat(trace.visibilityMode()).isEqualTo(VisibilityMode.HIDDEN);
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
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem)).build(),
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
                            "items Experiment items list size must be between 1 and 1000"));
        }

        @Test
        void experimentItemsBulk__whenProcessingBatchSizeIsHigherThanLimit__thenReturnBadRequest() {
            // given
            var bulkUpload = ExperimentItemBulkUpload.builder()
                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(8))
                    .datasetName("Test Dataset")
                    .items(IntStream.range(0, 1001)
                            .mapToObj(i -> ExperimentItemBulkRecord.builder()
                                    .datasetItemId(UUID.randomUUID())
                                    .trace(Trace.builder()
                                            .id(UUID.randomUUID())
                                            .startTime(Instant.now())
                                            .endTime(Instant.now().plusSeconds(1))
                                            .build())
                                    .spans(List.of())
                                    .build())
                            .toList())
                    .build();

            // when
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUpload, API_KEY,
                    TEST_WORKSPACE)) {

                // then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                var errorMessage = response.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(errorMessage.errors())
                        .contains("items Experiment items list size must be between 1 and 1000");
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

        @Test
        void experimentItemsBulk__whenBothEvaluateTaskResultAndTraceProvided__thenReturnBadRequest() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
            var datasetItem = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a bulk upload request with both evaluateTaskResult and trace
            var trace = createTrace();
            var evaluateTaskResult = podamFactory.manufacturePojo(JsonNode.class);

            var bulkItemRecord = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem.id())
                    .trace(trace)
                    .evaluateTaskResult(evaluateTaskResult)
                    .build();

            var bulkUploadRequest = ExperimentItemBulkUpload.builder()
                    .experimentName("Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20))
                    .datasetName(dataset.name())
                    .items(List.of(bulkItemRecord))
                    .build();

            // when
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUploadRequest, API_KEY,
                    TEST_WORKSPACE)) {
                // then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
                var errorMessage = response.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(errorMessage.errors()).contains(
                        "items[0].<list element> cannot provide both evaluate_task_result and trace together");
            }
        }

        @Test
        void experimentItemsBulk__whenOnlyEvaluateTaskResultProvided__thenSucceed() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);
            var datasetItem = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create a bulk upload request with only evaluateTaskResult
            var evaluateTaskResult = podamFactory.manufacturePojo(JsonNode.class);
            var experimentName = "Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);

            var feedbackScore = createScore();

            var bulkItemRecord = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem.id())
                    .evaluateTaskResult(evaluateTaskResult)
                    .feedbackScores(List.of(feedbackScore))
                    .build();

            var bulkUploadRequest = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset.name())
                    .items(List.of(bulkItemRecord))
                    .build();

            List<ExperimentItem> expectedItems = getExpectedItem(datasetItem, null, feedbackScore, evaluateTaskResult);

            // when
            experimentResourceClient.bulkUploadExperimentItem(bulkUploadRequest, API_KEY, TEST_WORKSPACE);

            // then
            List<ExperimentItem> actualExperimentItems = experimentResourceClient.getExperimentItems(experimentName,
                    API_KEY, TEST_WORKSPACE);

            String[] ignoringFields = Stream
                    .concat(Arrays.stream(ExperimentTestAssertions.EXPERIMENT_ITEMS_IGNORED_FIELDS),
                            Stream.of("traceId", "id", "experimentId"))
                    .toArray(String[]::new);

            ExperimentTestAssertions.assertExperimentResultsIgnoringFields(actualExperimentItems, expectedItems,
                    ignoringFields);

            Trace trace = traceResourceClient.getById(actualExperimentItems.getFirst().traceId(), TEST_WORKSPACE,
                    API_KEY);

            assertThat(trace).isNotNull();
            assertThat(trace.visibilityMode()).isEqualTo(VisibilityMode.HIDDEN);
        }

        @Test
        @DisplayName("Should add items to same experiment when same experiment ID used across multiple uploads")
        void experimentItemsBulk__whenUsingSameExperimentIdAcrossMultipleUploads__thenAllItemsAddedToSameExperiment() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

            // Create multiple dataset items for the test
            var datasetItem1 = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();
            var datasetItem2 = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem1, datasetItem2)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Generate a UUID v7 for the experiment
            var experimentId = podamFactory.manufacturePojo(UUID.class);
            var experimentName = "Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);

            // Create first bulk upload with the experiment ID
            var trace1 = createTrace();
            var span1 = creatrSpan();
            var feedbackScore1 = createScore();

            var bulkRecord1 = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem1.id())
                    .trace(trace1)
                    .spans(List.of(span1))
                    .feedbackScores(List.of(feedbackScore1))
                    .build();

            var bulkUpload1 = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset.name())
                    .experimentId(experimentId) // Use the generated experiment ID
                    .items(List.of(bulkRecord1))
                    .build();

            // Create second bulk upload with the same experiment ID
            var trace2 = createTrace();
            var span2 = creatrSpan();
            var feedbackScore2 = createScore();

            var bulkRecord2 = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem2.id())
                    .trace(trace2)
                    .spans(List.of(span2))
                    .feedbackScores(List.of(feedbackScore2))
                    .build();

            var bulkUpload2 = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset.name())
                    .experimentId(experimentId) // Use the same experiment ID
                    .items(List.of(bulkRecord2))
                    .build();

            // when - upload both batches
            experimentResourceClient.bulkUploadExperimentItem(bulkUpload1, API_KEY, TEST_WORKSPACE);
            experimentResourceClient.bulkUploadExperimentItem(bulkUpload2, API_KEY, TEST_WORKSPACE);

            // then - verify both items were added to the same experiment
            List<ExperimentItem> actualExperimentItems = experimentResourceClient.getExperimentItems(experimentName,
                    API_KEY, TEST_WORKSPACE);

            assertThat(actualExperimentItems).hasSize(2);

            // Verify all items belong to the same experiment
            assertThat(actualExperimentItems)
                    .allSatisfy(item -> assertThat(item.experimentId()).isEqualTo(experimentId));

            // Verify the correct dataset items are present
            var datasetItemIds = actualExperimentItems.stream()
                    .map(ExperimentItem::datasetItemId)
                    .collect(Collectors.toSet());
            assertThat(datasetItemIds).containsExactlyInAnyOrder(datasetItem1.id(), datasetItem2.id());

            // Verify the correct traces are present
            var traceIds = actualExperimentItems.stream()
                    .map(ExperimentItem::traceId)
                    .collect(Collectors.toSet());
            assertThat(traceIds).containsExactlyInAnyOrder(trace1.id(), trace2.id());
        }

        @Test
        @DisplayName("Should fail when using same experiment ID with different dataset")
        void experimentItemsBulk__whenUsingSameExperimentIdWithDifferentDataset__thenReturnConflict() {
            // given - Create first dataset and experiment
            var dataset1 = podamFactory.manufacturePojo(Dataset.class);
            var dataset1Id = datasetResourceClient.createDataset(dataset1, API_KEY, TEST_WORKSPACE);

            var datasetItem1 = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(dataset1Id)
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder().datasetName(dataset1.name())
                            .items(List.of(datasetItem1)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Generate a UUID v7 for the experiment
            var experimentId = podamFactory.manufacturePojo(UUID.class);
            var experimentName = "Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);

            var trace1 = createTrace();
            var span1 = creatrSpan();
            var feedbackScore1 = createScore();

            var bulkRecord1 = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem1.id())
                    .trace(trace1)
                    .spans(List.of(span1))
                    .feedbackScores(List.of(feedbackScore1))
                    .build();

            var bulkUpload1 = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset1.name())
                    .experimentId(experimentId) // Use the generated experiment ID
                    .items(List.of(bulkRecord1))
                    .build();

            // Create second dataset
            var dataset2 = podamFactory.manufacturePojo(Dataset.class);
            var dataset2Id = datasetResourceClient.createDataset(dataset2, API_KEY, TEST_WORKSPACE);

            var datasetItem2 = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(dataset2Id)
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder().datasetName(dataset2.name())
                            .items(List.of(datasetItem2)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Create second bulk upload with the same experiment ID but different dataset
            var trace2 = createTrace();
            var span2 = creatrSpan();
            var feedbackScore2 = createScore();
            var bulkRecord2 = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem2.id())
                    .trace(trace2)
                    .spans(List.of(span2))
                    .feedbackScores(List.of(feedbackScore2))
                    .build();

            var bulkUpload2 = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset2.name()) // Different dataset!
                    .experimentId(experimentId) // Same experiment ID
                    .items(List.of(bulkRecord2))
                    .build();

            // when - upload first batch successfully
            experimentResourceClient.bulkUploadExperimentItem(bulkUpload1, API_KEY, TEST_WORKSPACE);

            // then - second upload should fail with conflict
            try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUpload2, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);

                var errorMessage = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(errorMessage.getMessage()).contains(
                        "Experiment '%s' belongs to dataset '%s', but request specifies dataset '%s'"
                                .formatted(experimentId, dataset1.name(), dataset2.name()));
            }

            // Verify only the first experiment items were created
            List<ExperimentItem> actualExperimentItems = experimentResourceClient.getExperimentItems(experimentName,
                    API_KEY, TEST_WORKSPACE);

            assertThat(actualExperimentItems).hasSize(1);
            assertThat(actualExperimentItems.getFirst().experimentId()).isEqualTo(experimentId);
            assertThat(actualExperimentItems.getFirst().datasetItemId()).isEqualTo(datasetItem1.id());
            assertThat(actualExperimentItems.getFirst().traceId()).isEqualTo(trace1.id());
        }

        @Test
        @DisplayName("Should ignore different name when using same experiment ID with a different experiment name")
        void experimentItemsBulk__whenUsingSameExperimentIdWithDifferentName__ignoreTheNewName() {
            // given
            var dataset = podamFactory.manufacturePojo(Dataset.class);
            var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

            // Create multiple dataset items for the test
            var datasetItem1 = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();
            var datasetItem2 = podamFactory.manufacturePojo(DatasetItem.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            datasetResourceClient.createDatasetItems(
                    DatasetItemBatch.builder().datasetName(dataset.name())
                            .items(List.of(datasetItem1, datasetItem2)).build(),
                    TEST_WORKSPACE,
                    API_KEY);

            // Generate a UUID v7 for the experiment
            var experimentId = podamFactory.manufacturePojo(UUID.class);
            var experimentName = "Test Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);

            // Create first bulk upload with the experiment ID
            var trace1 = createTrace();
            var span1 = creatrSpan();
            var feedbackScore1 = createScore();

            var bulkRecord1 = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem1.id())
                    .trace(trace1)
                    .spans(List.of(span1))
                    .feedbackScores(List.of(feedbackScore1))
                    .build();

            var bulkUpload1 = ExperimentItemBulkUpload.builder()
                    .experimentName(experimentName)
                    .datasetName(dataset.name())
                    .experimentId(experimentId) // Use the generated experiment ID
                    .items(List.of(bulkRecord1))
                    .build();

            // Create second bulk upload with the same experiment ID
            var trace2 = createTrace();
            var span2 = creatrSpan();
            var feedbackScore2 = createScore();

            var bulkRecord2 = ExperimentItemBulkRecord.builder()
                    .datasetItemId(datasetItem2.id())
                    .trace(trace2)
                    .spans(List.of(span2))
                    .feedbackScores(List.of(feedbackScore2))
                    .build();

            var anotherExperimentName = "Another Experiment " + RandomStringUtils.secure().nextAlphanumeric(20);
            var bulkUpload2 = ExperimentItemBulkUpload.builder()
                    .experimentName(anotherExperimentName)
                    .datasetName(dataset.name())
                    .experimentId(experimentId) // Use the same experiment ID
                    .items(List.of(bulkRecord2))
                    .build();

            // when - upload both batches
            experimentResourceClient.bulkUploadExperimentItem(bulkUpload1, API_KEY, TEST_WORKSPACE);
            experimentResourceClient.bulkUploadExperimentItem(bulkUpload2, API_KEY, TEST_WORKSPACE);

            // then - verify both items were added to the same experiment
            List<ExperimentItem> actualExperimentItems = experimentResourceClient.getExperimentItems(experimentName,
                    API_KEY, TEST_WORKSPACE);

            assertThat(actualExperimentItems).hasSize(2);

            // Verify all items belong to the same experiment
            assertThat(actualExperimentItems)
                    .allSatisfy(item -> assertThat(item.experimentId()).isEqualTo(experimentId));

            // Verify the correct dataset items are present
            var datasetItemIds = actualExperimentItems.stream()
                    .map(ExperimentItem::datasetItemId)
                    .collect(Collectors.toSet());
            assertThat(datasetItemIds).containsExactlyInAnyOrder(datasetItem1.id(), datasetItem2.id());

            // Verify the correct traces are present
            var traceIds = actualExperimentItems.stream()
                    .map(ExperimentItem::traceId)
                    .collect(Collectors.toSet());
            assertThat(traceIds).containsExactlyInAnyOrder(trace1.id(), trace2.id());

            assertThat(experimentResourceClient.getExperimentItems(anotherExperimentName, API_KEY, TEST_WORKSPACE))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Update Experiments:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateExperiments {

        @ParameterizedTest
        @MethodSource("updateExperimentTestCases")
        @DisplayName("when updating experiment with valid data, then experiment is updated correctly")
        void updateExperiment_whenValidUpdate_thenExperimentUpdatedCorrectly(
                ExperimentUpdate updateRequest,
                Function<Experiment, Experiment> experimentSetter) {
            // given
            var originalMetadata = JsonUtils.getJsonNodeFromString("{\"original\": \"value\"}");
            var initialExperiment = experimentResourceClient.createPartialExperiment()
                    .name("Original Name")
                    .metadata(originalMetadata)
                    .type(ExperimentType.REGULAR)
                    .build();
            var experimentId = experimentResourceClient.create(initialExperiment, API_KEY, TEST_WORKSPACE);

            // when
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then
            getAndAssert(experimentId, experimentSetter.apply(initialExperiment), TEST_WORKSPACE, API_KEY);
        }

        Stream<Arguments> updateExperimentTestCases() {
            var updatedMetadata = JsonUtils.getJsonNodeFromString("{\"temperature\": 0.7, \"max_tokens\": 100}");

            return Stream.of(
                    // Update all fields
                    arguments(
                            ExperimentUpdate.builder()
                                    .name("Updated Experiment")
                                    .metadata(updatedMetadata)
                                    .type(ExperimentType.TRIAL)
                                    .status(ExperimentStatus.RUNNING)
                                    .build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment.toBuilder()
                                    .name("Updated Experiment")
                                    .metadata(updatedMetadata)
                                    .type(ExperimentType.TRIAL)
                                    .status(ExperimentStatus.RUNNING)
                                    .build()),
                    // Update only name
                    arguments(
                            ExperimentUpdate.builder()
                                    .name("New Name Only")
                                    .build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment.toBuilder()
                                    .name("New Name Only")
                                    .build()),
                    // Update only metadata
                    arguments(
                            ExperimentUpdate.builder()
                                    .metadata(updatedMetadata)
                                    .build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment.toBuilder()
                                    .metadata(updatedMetadata)
                                    .build()),
                    // Update only type
                    arguments(
                            ExperimentUpdate.builder()
                                    .type(ExperimentType.MINI_BATCH)
                                    .build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment.toBuilder()
                                    .type(ExperimentType.MINI_BATCH)
                                    .build()),
                    // Update only status
                    arguments(
                            ExperimentUpdate.builder()
                                    .status(ExperimentStatus.COMPLETED)
                                    .build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment.toBuilder()
                                    .status(ExperimentStatus.COMPLETED)
                                    .build()),
                    // Update only experiment_scores
                    arguments(
                            ExperimentUpdate.builder()
                                    .experimentScores(List.of(
                                            ExperimentScore.builder()
                                                    .name("accuracy")
                                                    .value(new BigDecimal("0.95"))
                                                    .build()))
                                    .build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment.toBuilder()
                                    .experimentScores(List.of(
                                            ExperimentScore.builder()
                                                    .name("accuracy")
                                                    .value(new BigDecimal("0.95"))
                                                    .build()))
                                    .build()),
                    // Empty update
                    arguments(ExperimentUpdate.builder().build(),
                            (Function<Experiment, Experiment>) initialExperiment -> initialExperiment));
        }

        @Test
        @DisplayName("when updating non-existent experiment, then return 404")
        void updateExperiment_whenNonExistentExperiment_thenReturn404() {
            // given
            var nonExistentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("Non-existent")
                    .build();

            // when & then
            experimentResourceClient.updateExperiment(nonExistentId, experimentUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("when updating experiment with experiment_scores, then scores are saved and retrieved correctly")
        void updateExperiment_whenExperimentScoresProvided_thenScoresSavedAndRetrieved() {
            // given
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Test Experiment")
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            var experimentScores = List.of(
                    ExperimentScore.builder()
                            .name("accuracy")
                            .value(new BigDecimal("0.95"))
                            .build(),
                    ExperimentScore.builder()
                            .name("latency")
                            .value(new BigDecimal("120.5"))
                            .build());

            var updateRequest = ExperimentUpdate.builder()
                    .experimentScores(experimentScores)
                    .build();

            // when
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then
            var updatedExperiment = getExperiment(experimentId, TEST_WORKSPACE, API_KEY);
            assertThat(updatedExperiment.experimentScores())
                    .isNotNull()
                    .hasSize(2)
                    .extracting(ExperimentScore::name, ExperimentScore::value)
                    .containsExactlyInAnyOrder(
                            tuple("accuracy", new BigDecimal("0.95")),
                            tuple("latency", new BigDecimal("120.5")));
        }

        @Test
        @DisplayName("when overriding experiment_scores, then new scores replace old ones")
        void updateExperiment_whenOverridingExperimentScores_thenNewScoresReplaceOld() {
            // given
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Test Experiment")
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            var initialScores = List.of(
                    ExperimentScore.builder()
                            .name("accuracy")
                            .value(new BigDecimal("0.90"))
                            .build());

            experimentResourceClient.updateExperiment(experimentId,
                    ExperimentUpdate.builder().experimentScores(initialScores).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // when - update with new scores
            var newScores = List.of(
                    ExperimentScore.builder()
                            .name("accuracy")
                            .value(new BigDecimal("0.95"))
                            .build(),
                    ExperimentScore.builder()
                            .name("f1_score")
                            .value(new BigDecimal("0.88"))
                            .build());

            experimentResourceClient.updateExperiment(experimentId,
                    ExperimentUpdate.builder().experimentScores(newScores).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // then
            var updatedExperiment = getExperiment(experimentId, TEST_WORKSPACE, API_KEY);
            assertThat(updatedExperiment.experimentScores())
                    .isNotNull()
                    .hasSize(2)
                    .extracting(ExperimentScore::name, ExperimentScore::value)
                    .containsExactlyInAnyOrder(
                            tuple("accuracy", new BigDecimal("0.95")),
                            tuple("f1_score", new BigDecimal("0.88")));
        }

        @Test
        @DisplayName("when updating experiment with empty experiment_scores, then scores are cleared")
        void updateExperiment_whenEmptyExperimentScores_thenScoresCleared() {
            // given
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Test Experiment")
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            var initialScores = List.of(
                    ExperimentScore.builder()
                            .name("accuracy")
                            .value(new BigDecimal("0.95"))
                            .build());

            experimentResourceClient.updateExperiment(experimentId,
                    ExperimentUpdate.builder().experimentScores(initialScores).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // when - update with empty list
            experimentResourceClient.updateExperiment(experimentId,
                    ExperimentUpdate.builder().experimentScores(List.of()).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // then
            var updatedExperiment = getExperiment(experimentId, TEST_WORKSPACE, API_KEY);
            assertThat(updatedExperiment.experimentScores()).isNull();
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when updating experiment with invalid values, then return 400")
        void updateExperiment_whenInvalidUpdate_thenReturn400(ExperimentUpdate experimentUpdate) {
            // given
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Original Name")
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when & then
            experimentResourceClient.updateExperiment(experimentId, experimentUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        private Stream<Arguments> updateExperiment_whenInvalidUpdate_thenReturn400() {
            return Stream.of(
                    arguments(named("blank name", ExperimentUpdate.builder().name("   ").build())));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when updating experiment with invalid experiment_scores, then return 400")
        void updateExperiment_whenInvalidExperimentScores_thenReturn400(List<ExperimentScore> invalidScores) {
            // given
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Test Experiment")
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            var updateRequest = ExperimentUpdate.builder()
                    .experimentScores(invalidScores)
                    .build();

            // when & then
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        private Stream<Arguments> updateExperiment_whenInvalidExperimentScores_thenReturn400() {
            return Stream.of(
                    arguments(named("blank name", List.of(ExperimentScore.builder()
                            .name("") // blank name
                            .value(new BigDecimal("0.95"))
                            .build()))),
                    arguments(named("null value", List.of(ExperimentScore.builder()
                            .name("accuracy")
                            .value(null) // null value
                            .build()))));
        }

        @Test
        @DisplayName("when updating experiment multiple times with partial updates, then latest values are preserved")
        void updateExperiment_whenMultiplePartialUpdates_thenLatestValuesPreserved() {
            var originalName = "original-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var originalMetadata = podamFactory.manufacturePojo(JsonNode.class);
            var initialExperiment = experimentResourceClient.createPartialExperiment()
                    .name(originalName)
                    .metadata(originalMetadata)
                    .build();
            var experimentId = experimentResourceClient.create(initialExperiment, API_KEY, TEST_WORKSPACE);

            // First update: Change both name and metadata with random values
            var firstUpdateName = "first-update-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var firstUpdateMetadata = podamFactory.manufacturePojo(JsonNode.class);
            var firstUpdate = ExperimentUpdate.builder()
                    .name(firstUpdateName)
                    .metadata(firstUpdateMetadata)
                    .build();
            experimentResourceClient.updateExperiment(
                    experimentId, firstUpdate, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Second update: Change only the name with random value
            var secondUpdateName = "second-update-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var secondUpdate = ExperimentUpdate.builder()
                    .name(secondUpdateName)
                    .build();
            experimentResourceClient.updateExperiment(
                    experimentId, secondUpdate, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Verify that metadata from first update is preserved (not from original)
            var expectedExperiment = initialExperiment.toBuilder()
                    .name(secondUpdateName)
                    .metadata(firstUpdateMetadata)
                    .build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);

            // Third update: Change only the metadata with random value
            var thirdUpdateMetadata = podamFactory.manufacturePojo(JsonNode.class);
            var thirdUpdate = ExperimentUpdate.builder()
                    .metadata(thirdUpdateMetadata)
                    .build();
            experimentResourceClient.updateExperiment(
                    experimentId, thirdUpdate, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Verify that name from second update is preserved (not from first or original)
            var finalExpectedExperiment = initialExperiment.toBuilder()
                    .name(secondUpdateName)
                    .metadata(thirdUpdateMetadata)
                    .build();
            getAndAssert(experimentId, finalExpectedExperiment, TEST_WORKSPACE, API_KEY);
        }
    }

    @Nested
    @DisplayName("Experiment Tags:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ExperimentTags {

        @ParameterizedTest
        @MethodSource("createExperimentTagsProvider")
        @DisplayName("when creating experiment with various tag inputs, then tags are handled correctly")
        void createExperimentWithVariousTags_thenTagsHandledCorrectly(Set<String> inputTags, Set<String> expectedTags) {
            // given
            var experiment = experimentResourceClient.createPartialExperiment()
                    .tags(inputTags)
                    .build();

            // when
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // then
            var expectedExperiment = experiment.toBuilder().tags(expectedTags).build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        private Stream<Arguments> createExperimentTagsProvider() {
            var testRandomTags = PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class);
            return Stream.of(
                    Arguments.of(testRandomTags, testRandomTags),
                    Arguments.of(Set.of(), null),
                    Arguments.of(null, null));
        }

        @Test
        @DisplayName("when updating experiment tags, then tags are replaced correctly")
        void updateExperimentTags_thenTagsReplacedCorrectly() {
            // given - create experiment with initial tags
            Set<String> initialTags = Set.of("initial1", "initial2");
            var experiment = experimentResourceClient.createPartialExperiment()
                    .tags(initialTags)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - update with new tags
            Set<String> updatedTags = Set.of("updated1", "updated2", "updated3");
            var updateRequest = ExperimentUpdate.builder()
                    .tags(updatedTags)
                    .build();
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify tags are replaced
            var expectedExperiment = experiment.toBuilder()
                    .tags(updatedTags)
                    .build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when updating experiment to remove tags, then tags are cleared correctly")
        void updateExperimentToRemoveTags_thenTagsClearedCorrectly() {
            // given - create experiment with tags
            var initialTags = PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class);
            var experiment = experimentResourceClient.createPartialExperiment()
                    .tags(initialTags)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - update with empty tags
            var updateRequest = ExperimentUpdate.builder()
                    .tags(Set.of())
                    .build();
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify tags are cleared (backend returns null for empty tags)
            var expectedExperiment = experiment.toBuilder()
                    .tags(null)
                    .build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when updating experiment with null tags, then existing tags are preserved")
        void updateExperimentWithNullTags_thenExistingTagsPreserved() {
            // given - create experiment with tags
            var initialTags = PodamFactoryUtils.manufacturePojoSet(podamFactory, String.class);
            var experiment = experimentResourceClient.createPartialExperiment()
                    .tags(initialTags)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - update with null tags (should preserve existing tags)
            var updateRequest = ExperimentUpdate.builder()
                    .name("Updated Name")
                    .tags(null)
                    .build();
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify existing tags are preserved
            var expectedExperiment = experiment.toBuilder()
                    .name("Updated Name")
                    .tags(initialTags)
                    .build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when updating only experiment name, then tags are preserved")
        void updateExperimentName_thenTagsPreserved() {
            // given - create experiment with tags
            Set<String> initialTags = Set.of("preserve1", "preserve2");
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Original Name")
                    .tags(initialTags)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - update only the name
            var updateRequest = ExperimentUpdate.builder()
                    .name("New Name")
                    .build();
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify tags are preserved
            var expectedExperiment = experiment.toBuilder()
                    .name("New Name")
                    .tags(initialTags)
                    .build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when updating experiment with multiple fields including tags, then all fields are updated correctly")
        void updateExperimentWithMultipleFieldsIncludingTags_thenAllFieldsUpdatedCorrectly() {
            // given
            var originalMetadata = JsonUtils.getJsonNodeFromString("{\"original\": \"value\"}");
            Set<String> initialTags = Set.of("initial");
            var experiment = experimentResourceClient.createPartialExperiment()
                    .name("Original Name")
                    .metadata(originalMetadata)
                    .tags(initialTags)
                    .type(ExperimentType.REGULAR)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - update multiple fields including tags
            var updatedMetadata = JsonUtils.getJsonNodeFromString("{\"updated\": \"value\"}");
            Set<String> updatedTags = Set.of("updated1", "updated2");
            var updateRequest = ExperimentUpdate.builder()
                    .name("Updated Name")
                    .metadata(updatedMetadata)
                    .tags(updatedTags)
                    .type(ExperimentType.TRIAL)
                    .status(ExperimentStatus.RUNNING)
                    .build();
            experimentResourceClient.updateExperiment(experimentId, updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify all fields are updated
            var expectedExperiment = experiment.toBuilder()
                    .name("Updated Name")
                    .metadata(updatedMetadata)
                    .tags(updatedTags)
                    .type(ExperimentType.TRIAL)
                    .status(ExperimentStatus.RUNNING)
                    .build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when creating multiple experiments with different tags, then each experiment has correct tags")
        void createMultipleExperimentsWithDifferentTags_thenEachHasCorrectTags() {
            // given
            Set<String> tags1 = Set.of("experiment1", "common");
            Set<String> tags2 = Set.of("experiment2", "common");
            Set<String> tags3 = Set.of("experiment3");

            var experiment1 = experimentResourceClient.createPartialExperiment()
                    .tags(tags1)
                    .build();
            var experiment2 = experimentResourceClient.createPartialExperiment()
                    .tags(tags2)
                    .build();
            var experiment3 = experimentResourceClient.createPartialExperiment()
                    .tags(tags3)
                    .build();

            // when
            var experimentId1 = experimentResourceClient.create(experiment1, API_KEY, TEST_WORKSPACE);
            var experimentId2 = experimentResourceClient.create(experiment2, API_KEY, TEST_WORKSPACE);
            var experimentId3 = experimentResourceClient.create(experiment3, API_KEY, TEST_WORKSPACE);

            // then - verify each experiment has its own tags
            getAndAssert(experimentId1, experiment1, TEST_WORKSPACE, API_KEY);
            getAndAssert(experimentId2, experiment2, TEST_WORKSPACE, API_KEY);
            getAndAssert(experimentId3, experiment3, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when sequentially updating tags multiple times, then latest tags are applied")
        void sequentiallyUpdateTags_thenLatestTagsApplied() {
            // given - create experiment with initial tags
            Set<String> initialTags = Set.of("initial");
            var experiment = experimentResourceClient.createPartialExperiment()
                    .tags(initialTags)
                    .build();
            var experimentId = experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            // when - first update
            Set<String> firstUpdateTags = Set.of("first", "first_update");
            var firstUpdate = ExperimentUpdate.builder()
                    .tags(firstUpdateTags)
                    .build();
            experimentResourceClient.updateExperiment(experimentId, firstUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify the first update
            var expectedExperiment = experiment.toBuilder().tags(firstUpdateTags).build();
            var afterFirstUpdate = getAndAssert(experimentId, expectedExperiment,
                    TEST_WORKSPACE, API_KEY);
            assertThat(afterFirstUpdate.tags()).containsExactlyInAnyOrderElementsOf(firstUpdateTags);

            // when - second update
            Set<String> secondUpdateTags = Set.of("second", "second_update", "tags");
            var secondUpdate = ExperimentUpdate.builder()
                    .tags(secondUpdateTags)
                    .build();
            experimentResourceClient.updateExperiment(experimentId, secondUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            // then - verify the second update (should replace first update tags)
            expectedExperiment = experiment.toBuilder().tags(secondUpdateTags).build();
            getAndAssert(experimentId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }
    }
}
