package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Column;
import com.comet.opik.api.Comment;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
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
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
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
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.Column.ColumnType;
import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.api.Visibility.PRIVATE;
import static com.comet.opik.api.Visibility.PUBLIC;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.CommentAssertionUtils.IGNORED_FIELDS_COMMENTS;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScoresIgnoredFieldsAndSetThemToNull;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.api.resources.utils.TestUtils.getIdFromLocation;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.api.resources.v1.priv.OptimizationsResourceTest.OPTIMIZATION_IGNORED_FIELDS;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.stream.Collectors.flatMapping;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsResourceTest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/datasets";
    private static final String EXPERIMENT_RESOURCE_URI = "%s/v1/private/experiments";
    private static final String DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH = "/items/experiments/items";

    private static final String URL_TEMPLATE_TRACES = "%s/v1/private/traces";

    public static final String[] IGNORED_FIELDS_LIST = {"feedbackScores", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "comments"};
    public static final String[] IGNORED_FIELDS_DATA_ITEM = {"createdAt", "lastUpdatedAt", "experimentItems",
            "createdBy", "lastUpdatedBy", "datasetId"};
    public static final String[] DATASET_IGNORED_FIELDS = {"id", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "experimentCount", "mostRecentExperimentAt", "lastCreatedExperimentAt",
            "datasetItemsCount", "lastCreatedOptimizationAt", "mostRecentOptimizationAt", "optimizationCount"};

    public static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory,
                wireMock.runtimeInfo(),
                REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private PromptResourceClient promptResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private TransactionTemplate mySqlTemplate;
    private OptimizationResourceClient optimizationResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, TransactionTemplate mySqlTemplate) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        promptResourceClient = new PromptResourceClient(client, baseURI, factory);
        experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        datasetResourceClient = new DatasetResourceClient(client, baseURI);
        traceResourceClient = new TraceResourceClient(this.client, baseURI);
        spanResourceClient = new SpanResourceClient(this.client, baseURI);
        optimizationResourceClient = new OptimizationResourceClient(client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
    }

    private UUID createAndAssert(Dataset dataset) {
        return createAndAssert(dataset, API_KEY, TEST_WORKSPACE);
    }

    private UUID createAndAssert(Dataset dataset, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(dataset))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            var id = getIdFromLocation(actualResponse.getLocation());

            assertThat(id).isNotNull();
            assertThat(id.version()).isEqualTo(7);

            return id;
        }
    }

    private Dataset getAndAssertEquals(UUID id, Dataset expected, String workspaceName, String apiKey) {
        var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
        var actualEntity = actualResponse.readEntity(Dataset.class);

        assertThat(actualEntity.id()).isEqualTo(id);
        assertThat(actualEntity).usingRecursiveComparison()
                .ignoringFields(DATASET_IGNORED_FIELDS)
                .isEqualTo(expected);

        assertThat(actualEntity.lastUpdatedBy()).isEqualTo(USER);
        assertThat(actualEntity.createdBy()).isEqualTo(USER);
        assertThat(actualEntity.createdAt()).isInThePast();
        assertThat(actualEntity.lastUpdatedAt()).isInThePast();
        assertThat(actualEntity.experimentCount()).isNotNull();
        assertThat(actualEntity.datasetItemsCount()).isNotNull();

        return actualEntity;
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

        Stream<Arguments> getDatasetPublicCredentials() {
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
        @DisplayName("create dataset: when api key is present, then return proper response")
        void createDataset__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .id(null)
                    .build();

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(project))) {

                if (shouldSucceed) {
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
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset by id: when api key is present, then return proper response")
        void getDatasetById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, Visibility visibility,
                int expectedCode) {

            Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .visibility(visibility)
                    .build();

            var id = createAndAssert(dataset);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);
            mockGetWorkspaceIdByName(TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(Dataset.class);
                    assertThat(actualEntity.id()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }
        }

        @ParameterizedTest
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset by name: when api key is present, then return proper response")
        void getDatasetByName__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, Visibility visibility,
                int expectedCode) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .visibility(visibility)
                    .build();

            var id = createAndAssert(dataset);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);
            mockGetWorkspaceIdByName(TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(Dataset.class);
                    assertThat(actualEntity.id()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        @DisplayName("Get datasets: when api key is present, then return proper response")
        void getDatasets__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, Visibility visibility) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);

            List<Dataset> expected = prepareDatasetsListWithOnePublic();

            expected.forEach(dataset -> createAndAssert(dataset, okApikey, workspaceName));

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualResponse.hasEntity()).isTrue();
                var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);
                if (visibility == PRIVATE) {
                    assertThat(actualEntity.content()).hasSize(expected.size());
                } else {
                    assertThat(actualEntity.content()).hasSize(1);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Update dataset: when api key is present, then return proper response")
        void updateDataset__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            var update = factory.manufacturePojo(DatasetUpdate.class);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(update))) {

                if (shouldSucceed) {
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
        @DisplayName("Delete dataset: when api key is present, then return proper response")
        void deleteDataset__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                if (shouldSucceed) {
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
        @DisplayName("Create dataset items: when api key is present, then return proper response")
        void createDatasetItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .put(Entity.json(batch))) {

                if (shouldSucceed) {
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
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset items by dataset id: when api key is present, then return proper response")
        void getDatasetItemsByDatasetId__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility,
                int expectedCode) {

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .visibility(visibility)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);
            mockGetWorkspaceIdByName(TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(DatasetItemPage.class);
                    assertThat(actualEntity.content().size()).isEqualTo(items.size());
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }
        }

        @ParameterizedTest
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Stream dataset items: when api key is present, then return proper response")
        void streamDatasetItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, Visibility visibility,
                int expectedCode) {
            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
                    .visibility(visibility)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);
            mockGetWorkspaceIdByName(TEST_WORKSPACE, WORKSPACE_ID);

            var request = new DatasetItemStreamRequest(name, null, null);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .post(Entity.json(request))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                if (expectedCode == 200) {
                    assertThat(actualResponse.hasEntity()).isTrue();
                    List<DatasetItem> actualItems = getStreamedItems(actualResponse);
                    assertThat(actualItems.size()).isEqualTo(items.size());

                } else {
                    assertThat(actualResponse.hasEntity()).isFalse();
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Delete dataset items: when api key is present, then return proper response")
        void deleteDatasetItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            var delete = new DatasetItemsDelete(items.stream().map(DatasetItem::id).toList());

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(delete))) {

                if (shouldSucceed) {
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
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset item by id: when api key is present, then return proper response")
        void getDatasetItemById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                Visibility visibility,
                int expectedCode) {
            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
                    .visibility(visibility)
                    .build());

            var item = factory.manufacturePojo(DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);
            mockGetWorkspaceIdByName(TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path(item.id().toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(DatasetItem.class);
                    assertThat(actualEntity.id()).isEqualTo(item.id());
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
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

        @BeforeAll
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
                                                    401)))));
        }

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(sessionToken, true, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, false, UUID.randomUUID().toString()));
        }

        Stream<Arguments> publicCredentials() {
            return Stream.of(
                    arguments(sessionToken, PRIVATE, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, PUBLIC, UUID.randomUUID().toString()));
        }

        Stream<Arguments> getDatasetPublicCredentials() {
            return Stream.of(
                    arguments(sessionToken, PRIVATE, "OK_" + UUID.randomUUID(), 200),
                    arguments(sessionToken, PUBLIC, "OK_" + UUID.randomUUID(), 200),
                    arguments(fakeSessionToken, PRIVATE, UUID.randomUUID().toString(), 404),
                    arguments(fakeSessionToken, PUBLIC, UUID.randomUUID().toString(), 200));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create dataset: when session token is present, then return proper response")
        void createDataset__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(dataset))) {

                if (shouldSucceed) {
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
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset by id: when session token is present, then return proper response")
        void getDatasetById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .visibility(visibility)
                    .build();

            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            var id = createAndAssert(dataset);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(Dataset.class);
                    assertThat(actualEntity.id()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }
        }

        @ParameterizedTest
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset by name: when session token is present, then return proper response")
        void getDatasetByName__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .visibility(visibility)
                    .build();

            var id = createAndAssert(dataset);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();

                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(Dataset.class);
                    assertThat(actualEntity.id()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }
        }

        @ParameterizedTest
        @MethodSource("publicCredentials")
        @DisplayName("Get datasets: when session token is present, then return proper response")
        void getDatasets__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility, String workspaceName) {

            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockGetWorkspaceIdByName(workspaceName, workspaceId);
            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            List<Dataset> expected = prepareDatasetsListWithOnePublic();

            expected.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualResponse.hasEntity()).isTrue();
                var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);
                if (visibility == PRIVATE) {
                    assertThat(actualEntity.content()).hasSize(expected.size());
                } else {
                    assertThat(actualEntity.content()).hasSize(1);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Update dataset: when session token is present, then return proper response")
        void updateDataset__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            var update = factory.manufacturePojo(DatasetUpdate.class);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .put(Entity.json(update))) {

                if (shouldSucceed) {
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
        @DisplayName("Delete dataset: when session token is present, then return proper response")
        void deleteDataset__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .delete()) {

                if (shouldSucceed) {
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
        @DisplayName("Create dataset items: when session token is present, then return proper response")
        void createDatasetItems__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .put(Entity.json(batch))) {

                if (shouldSucceed) {
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
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset items by dataset id: when session token is present, then return proper response")
        void getDatasetItemsByDatasetId__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .visibility(visibility)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();

                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(DatasetItemPage.class);
                    assertThat(actualEntity.content().size()).isEqualTo(items.size());
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }
        }

        @ParameterizedTest
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Stream dataset items: when session token is present, then return proper response")
        void getDatasetItemsStream__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {
            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
                    .visibility(visibility)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, WORKSPACE_ID);
            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            var request = new DatasetItemStreamRequest(name, null, null);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .post(Entity.json(request))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                if (expectedCode == 200) {
                    assertThat(actualResponse.hasEntity()).isTrue();
                    List<DatasetItem> actualItems = getStreamedItems(actualResponse);
                    assertThat(actualItems.size()).isEqualTo(items.size());

                } else {
                    assertThat(actualResponse.hasEntity()).isFalse();
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Delete dataset items: when session token is present, then return proper response")
        void deleteDatasetItems__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, WORKSPACE_ID);

            var delete = new DatasetItemsDelete(items.stream().map(DatasetItem::id).toList());

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(delete))) {

                if (shouldSucceed) {
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
        @MethodSource("getDatasetPublicCredentials")
        @DisplayName("Get dataset item by id: when session token is present, then return proper response")
        void getDatasetItemById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                Visibility visibility,
                String workspaceName, int expectedCode) {

            mockGetWorkspaceIdByName(workspaceName, WORKSPACE_ID);

            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
                    .visibility(visibility)
                    .build());

            var item = factory.manufacturePojo(DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(datasetId)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path(item.id().toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedCode);
                assertThat(actualResponse.hasEntity()).isTrue();
                if (expectedCode == 200) {
                    var actualEntity = actualResponse.readEntity(DatasetItem.class);
                    assertThat(actualEntity.id()).isEqualTo(item.id());
                } else {
                    assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
                }
            }

        }

    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateDataset {

        @Test
        @DisplayName("Success")
        void create__success() {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            createAndAssert(dataset);
        }

        @Test
        @DisplayName("when description is multiline, then accept the request")
        void create__whenDescriptionIsMultiline__thenAcceptTheRequest() {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .description("""
                            Test
                            Description
                            """)
                    .build();

            createAndAssert(dataset);
        }

        @Test
        @DisplayName("when creating datasets with same name in different workspaces, then accept the request")
        void create__whenCreatingDatasetsWithSameNameInDifferentWorkspaces__thenAcceptTheRequest() {

            var name = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset1 = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
                    .build();

            var dataset2 = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
                    .build();

            createAndAssert(dataset1, apiKey, workspaceName);
            createAndAssert(dataset2);
        }

        @Test
        @DisplayName("when description is null, then accept the request")
        void create__whenDescriptionIsNull__thenAcceptNameCreate() {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .description(null)
                    .build();

            createAndAssert(dataset);
        }

        private Stream<Arguments> invalidDataset() {
            return Stream.of(
                    arguments(factory.manufacturePojo(Dataset.class).toBuilder().name(null).build(),
                            "name must not be blank"),
                    arguments(factory.manufacturePojo(Dataset.class).toBuilder().name("").build(),
                            "name must not be blank"));
        }

        @ParameterizedTest
        @MethodSource("invalidDataset")
        @DisplayName("when request is not valid, then return 422")
        void create__whenRequestIsNotValid__thenReturn422(Dataset dataset, String errorMessage) {

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(dataset))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }

        }

        @Test
        @DisplayName("when dataset name already exists, then reject the request")
        void create__whenDatasetNameAlreadyExists__thenRejectNameCreate() {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            createAndAssert(dataset);

            createAndAssertConflict(dataset, "Dataset already exists");
        }

        @Test
        @DisplayName("when dataset id already exists, then reject the request")
        void create__whenDatasetIdAlreadyExists__thenRejectNameCreate() {

            var dataset = factory.manufacturePojo(Dataset.class);
            var dataset2 = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(dataset.id())
                    .build();

            createAndAssert(dataset);

            createAndAssertConflict(dataset2, "Dataset already exists");
        }

        private void createAndAssertConflict(Dataset dataset, String conflictMessage) {
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.entity(dataset, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(conflictMessage);
            }
        }
    }

    @Nested
    @DisplayName("Get: {id, name}")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetDataset {

        @Test
        @DisplayName("Success")
        void getDatasetById() {
            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            getAndAssertEquals(id, dataset, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when dataset not found, then return 404")
        void getDatasetById__whenDatasetNotFound__whenReturn404() {

            var id = UUID.randomUUID().toString();

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            assertThat(actualResponse.hasEntity()).isTrue();
            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
        }

        @Test
        @DisplayName("when retrieving dataset by name, then return dataset")
        void getDatasetByIdentifier() {
            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            createAndAssert(dataset);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualEntity = actualResponse.readEntity(Dataset.class);
                assertThat(actualEntity).usingRecursiveComparison()
                        .ignoringFields(DATASET_IGNORED_FIELDS)
                        .isEqualTo(dataset);
            }
        }

        @Test
        @DisplayName("when dataset not found by dataset name, then return 404")
        void getDatasetByIdentifier__whenDatasetItemNotFound__thenReturn404() {
            var name = UUID.randomUUID().toString();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetIdentifier(name)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
            }
        }

        @Test
        @DisplayName("when dataset has optimizations linked to it, then return dataset with optimizations summary")
        void getDatasetById__whenDatasetHasOptimizationsLinkedToIt__thenReturnDatasetWithOptimizationSummary() {
            var dataset = factory.manufacturePojo(Dataset.class);
            createAndAssert(dataset);

            Instant beforeCreateOptimizations = Instant.now();
            int optimizationsCnt = 5;
            for (int i = 0; i < optimizationsCnt; i++) {
                var optimization = optimizationResourceClient.createPartialOptimization().datasetName(dataset.name())
                        .build();
                optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE);
            }

            var actualDataset = getAndAssertEquals(dataset.id(), dataset, TEST_WORKSPACE, API_KEY);

            assertThat(actualDataset.optimizationCount()).isEqualTo(optimizationsCnt);
            assertThat(actualDataset.mostRecentOptimizationAt()).isAfter(beforeCreateOptimizations);
            assertThat(actualDataset.lastCreatedOptimizationAt()).isAfter(beforeCreateOptimizations);
        }

        @Test
        @DisplayName("when dataset has experiments linked to it, then return dataset with experiment summary")
        void getDatasetById__whenDatasetHasExperimentsLinkedToIt__thenReturnDatasetWithExperimentSummary() {

            var dataset = factory.manufacturePojo(Dataset.class);

            createAndAssert(dataset);

            var experiment1 = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .build();

            var experiment2 = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .build();

            createAndAssert(experiment1, API_KEY, TEST_WORKSPACE);
            createAndAssert(experiment2, API_KEY, TEST_WORKSPACE);

            var datasetItems = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            DatasetItemBatch batch = new DatasetItemBatch(dataset.name(), null, datasetItems);

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            // Creating two traces with input, output and scores
            var trace1 = factory.manufacturePojo(Trace.class);
            createTrace(trace1, API_KEY, TEST_WORKSPACE);

            var trace2 = factory.manufacturePojo(Trace.class);
            createTrace(trace2, API_KEY, TEST_WORKSPACE);

            var traces = List.of(trace1, trace2);

            // Creating 5 scores peach each of the two traces above
            var scores1 = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                            .id(trace1.id())
                            .projectName(trace1.projectName())
                            .build())
                    .toList();

            var scores2 = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                            .id(trace2.id())
                            .projectName(trace2.projectName())
                            .build())
                    .toList();

            var traceIdToScoresMap = Stream.concat(scores1.stream(), scores2.stream())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = factory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, API_KEY, TEST_WORKSPACE);

            var experimentItems = IntStream.range(0, 10)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(List.of(experiment1, experiment2).get(i / 5).id())
                            .traceId(traces.get(i / 5).id())
                            .feedbackScores(traceIdToScoresMap.get(traces.get(i / 5).id()).stream()
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                    .toList())
                            .build())
                    .toList();

            // When storing the experiment items in batch, adding some more unrelated random ones
            var experimentItemsBatch = factory.manufacturePojo(ExperimentItemsBatch.class);
            experimentItemsBatch = experimentItemsBatch.toBuilder()
                    .experimentItems(Stream.concat(
                            experimentItemsBatch.experimentItems().stream(),
                            experimentItems.stream())
                            .collect(toUnmodifiableSet()))
                    .build();

            Instant beforeCreateExperimentItems = Instant.now();

            createAndAssert(experimentItemsBatch, API_KEY, TEST_WORKSPACE);

            var actualDataset = getAndAssertEquals(dataset.id(), dataset, TEST_WORKSPACE, API_KEY);

            assertThat(actualDataset.experimentCount()).isEqualTo(2);
            assertThat(actualDataset.datasetItemsCount()).isEqualTo(datasetItems.size());
            assertThat(actualDataset.mostRecentExperimentAt()).isAfter(beforeCreateExperimentItems);
        }

    }

    private void createScoreAndAssert(FeedbackScoreBatch feedbackScoreBatch, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(getTracesPath())
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(feedbackScoreBatch))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private void createAndAssert(Experiment experiment, String apiKey, String workspaceName) {
        experimentResourceClient.create(experiment, apiKey, workspaceName);
    }

    @Nested
    @DisplayName("Get:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindDatasets {

        @Test
        @DisplayName("Success")
        void getDatasets() {

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expected1 = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);
            List<Dataset> expected2 = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            expected1.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));
            expected2.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .name("The most expressive LLM: " + UUID.randomUUID()
                            + " \uD83D\uDE05\uD83E\uDD23\uD83D\uDE02\uD83D\uDE42\uD83D\uDE43\uD83E\uDEE0")
                    .description("Emoji Test \uD83E\uDD13\uD83E\uDDD0")
                    .build();

            createAndAssert(dataset, apiKey, workspaceName);

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            int defaultPageSize = 10;

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var expectedContent = new ArrayList<Dataset>();
            expectedContent.add(dataset);

            expected2.reversed()
                    .stream()
                    .filter(__ -> expectedContent.size() < defaultPageSize)
                    .forEach(expectedContent::add);

            expected1.reversed()
                    .stream()
                    .filter(__ -> expectedContent.size() < defaultPageSize)
                    .forEach(expectedContent::add);

            findAndAssertPage(actualEntity, defaultPageSize, expectedContent.size() + 1, 1, expectedContent);
        }

        @Test
        @DisplayName("when limit is 5 but there are N datasets, then return 5 datasets and total N")
        void getDatasets__whenLimitIs5ButThereAre10Datasets__thenReturn5DatasetsAndTotal10() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expected1 = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);
            List<Dataset> expected2 = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            expected1.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));
            expected2.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            int pageSize = 5;
            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", pageSize)
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            var expectedContent = new ArrayList<>(expected2.reversed().subList(0, pageSize));

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            findAndAssertPage(actualEntity, pageSize, expected1.size() + expected2.size(), 1, expectedContent);
        }

        @Test
        @DisplayName("when fetching all datasets, then return datasets sorted by created date")
        void getDatasets__whenFetchingAllDatasets__thenReturnDatasetsSortedByCreatedDate() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expected = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            expected.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", 5)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            findAndAssertPage(actualEntity, expected.size(), expected.size(), 1, expected.reversed());
        }

        @ParameterizedTest
        @MethodSource("sortDirectionProvider")
        @DisplayName("when fetching all datasets, then return datasets sorted by last_created_experiment_at")
        void getDatasets__whenFetchingAllDatasets__thenReturnDatasetsSortedByLastCreatedExperimentAt(
                Direction requestDirection, Direction expectedDirection) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expected = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);
            Set<DatasetLastExperimentCreated> datasetsLastExperimentCreated = new HashSet<>();

            expected.forEach(dataset -> {
                var id = createAndAssert(dataset, apiKey, workspaceName);
                datasetsLastExperimentCreated.add(new DatasetLastExperimentCreated(id, Instant.now()));
            });

            mySqlTemplate.inTransaction(WRITE, handle -> {

                var dao = handle.attach(DatasetDAO.class);
                dao.recordExperiments(workspaceId, datasetsLastExperimentCreated);

                return null;
            });

            requestAndAssertDatasetsSorting(workspaceName, apiKey, expected, requestDirection, expectedDirection,
                    SortableFields.LAST_CREATED_EXPERIMENT_AT);
        }

        @ParameterizedTest
        @MethodSource("sortDirectionProvider")
        @DisplayName("when fetching all datasets, then return datasets sorted by last_created_optimization_at")
        void getDatasets__whenFetchingAllDatasets__thenReturnDatasetsSortedByLastCreatedOptimizationAt(
                Direction requestDirection, Direction expectedDirection) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expected = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);
            Set<DatasetLastOptimizationCreated> datasetsLastOptimizationCreated = new HashSet<>();

            expected.forEach(dataset -> {
                var id = createAndAssert(dataset, apiKey, workspaceName);
                datasetsLastOptimizationCreated.add(new DatasetLastOptimizationCreated(id, Instant.now()));
            });

            mySqlTemplate.inTransaction(WRITE, handle -> {

                var dao = handle.attach(DatasetDAO.class);
                dao.recordOptimizations(workspaceId, datasetsLastOptimizationCreated);

                return null;
            });

            requestAndAssertDatasetsSorting(workspaceName, apiKey, expected, requestDirection, expectedDirection,
                    SortableFields.LAST_CREATED_OPTIMIZATION_AT);
        }

        public static Stream<Arguments> sortDirectionProvider() {
            return Stream.of(
                    Arguments.of(Named.of("non specified", null), Direction.ASC),
                    Arguments.of(Named.of("ascending", Direction.ASC), Direction.ASC),
                    Arguments.of(Named.of("descending", Direction.DESC), Direction.DESC));
        }

        private void requestAndAssertDatasetsSorting(String workspaceName, String apiKey, List<Dataset> allDatasets,
                Direction request, Direction expected, String sortingField) {
            var sorting = List.of(SortingField.builder()
                    .field(sortingField)
                    .direction(request)
                    .build());

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", allDatasets.size())
                    .queryParam("sorting", URLEncoder.encode(JsonUtils.writeValueAsString(sorting),
                            StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(allDatasets.size());
            assertThat(actualEntity.total()).isEqualTo(allDatasets.size());
            assertThat(actualEntity.page()).isEqualTo(1);

            if (expected == Direction.DESC) {
                allDatasets = allDatasets.reversed();
            }

            assertThat(actualEntity.content())
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DATASET_IGNORED_FIELDS)
                    .containsExactlyElementsOf(allDatasets);
        }

        @Test
        @DisplayName("when searching by dataset name, then return full text search result")
        void getDatasets__whenSearchingByDatasetName__thenReturnFullTextSearchResult() {
            UUID datasetSuffix = UUID.randomUUID();
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> datasets = List.of(
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("MySQL, realtime chatboot: " + datasetSuffix).build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("Chatboot using mysql: " + datasetSuffix)
                            .build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("Chatboot MYSQL expert: " + datasetSuffix)
                            .build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("Chatboot expert (my SQL): " + datasetSuffix).build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("Chatboot expert: " + datasetSuffix)
                            .build());

            datasets.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", "MySql")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.total()).isEqualTo(3);
            assertThat(actualEntity.size()).isEqualTo(3);

            var actualDatasets = actualEntity.content();
            assertThat(actualDatasets.stream().map(Dataset::name).toList()).contains(
                    "MySQL, realtime chatboot: " + datasetSuffix,
                    "Chatboot using mysql: " + datasetSuffix,
                    "Chatboot MYSQL expert: " + datasetSuffix);
        }

        @Test
        @DisplayName("when searching by dataset name fragments, then return full text search result")
        void getDatasets__whenSearchingByDatasetNameFragments__thenReturnFullTextSearchResult() {

            UUID datasetSuffix = UUID.randomUUID();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> datasets = List.of(
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("MySQL: " + datasetSuffix)
                            .build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("Chat-boot using mysql: " + datasetSuffix)
                            .build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("MYSQL CHATBOOT expert: " + datasetSuffix)
                            .build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("Expert Chatboot: " + datasetSuffix)
                            .build(),
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name("My chat expert: " + datasetSuffix)
                            .build());

            datasets.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", "cha")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.total()).isEqualTo(4);
            assertThat(actualEntity.size()).isEqualTo(4);

            var actualDatasets = actualEntity.content();

            assertThat(actualDatasets.stream().map(Dataset::name).toList()).contains(
                    "Chat-boot using mysql: " + datasetSuffix,
                    "MYSQL CHATBOOT expert: " + datasetSuffix,
                    "Expert Chatboot: " + datasetSuffix,
                    "My chat expert: " + datasetSuffix);
        }

        @Test
        @DisplayName("when searching by dataset name and workspace name, then return full text search result")
        void getDatasets__whenSearchingByDatasetNameAndWorkspaceName__thenReturnFullTextSearchResult() {

            var name = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Dataset dataset1 = factory.manufacturePojo(Dataset.class).toBuilder()
                    .name(name)
                    .build();

            Dataset dataset2 = factory.manufacturePojo(Dataset.class).toBuilder()
                    .name(name)
                    .build();

            createAndAssert(dataset1, API_KEY, TEST_WORKSPACE);

            createAndAssert(dataset2, apiKey, workspaceName);

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", name)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            findAndAssertPage(actualEntity, 1, 1, 1, List.of(dataset1));
        }

        @Test
        @DisplayName("when searching by dataset name with different workspace name, then return no match")
        void getDatasets__whenSearchingByDatasetNameWithDifferentWorkspaceAndWorkspaceName__thenReturnNoMatch() {

            var name = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> datasets = List.of(
                    factory.manufacturePojo(Dataset.class).toBuilder()
                            .name(name)
                            .build());

            datasets.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", name)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            findAndAssertPage(actualEntity, 0, 0, 1, List.of());
        }

        @Test
        @DisplayName("when datasets have experiments linked to them, then return datasets with experiment summary")
        void getDatasets__whenDatasetsHaveExperimentsLinkedToThem__thenReturnDatasetsWithExperimentSummary() {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            datasets.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            AtomicInteger index = new AtomicInteger();

            var experiments = experimentResourceClient.generateExperimentList()
                    .stream()
                    .flatMap(experiment -> Stream.of(experiment.toBuilder()
                            .datasetName(datasets.get(index.getAndIncrement()).name())
                            .datasetId(null)
                            .build()))
                    .toList();

            experiments.forEach(experiment -> createAndAssert(experiment, apiKey, workspaceName));

            index.set(0);

            var datasetItems = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            datasetItems.forEach(datasetItem -> putAndAssert(
                    new DatasetItemBatch(null, datasets.get(index.getAndIncrement()).id(), List.of(datasetItem)),
                    workspaceName, apiKey));

            // Creating two traces with input, output and scores
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class);

            traces.forEach(trace -> createTrace(trace, apiKey, workspaceName));

            index.set(0);

            // Creating 5 scores peach each of the two traces above
            var scores = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                            .id(traces.get(index.get()).id())
                            .projectName(traces.get(index.getAndIncrement()).projectName())
                            .build())
                    .toList();

            var traceIdToScoresMap = scores.stream()
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = factory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            index.set(0);

            var experimentItems = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .datasetItemId(datasetItems.get(index.get()).id())
                            .experimentId(experiments.get(index.get()).id())
                            .traceId(traces.get(index.get()).id())
                            .feedbackScores(traceIdToScoresMap.get(traces.get(index.getAndIncrement()).id()).stream()
                                    .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                    .toList())
                            .build())
                    .collect(toSet());

            var experimentItemsBatch = factory.manufacturePojo(ExperimentItemsBatch.class).toBuilder()
                    .experimentItems(experimentItems)
                    .build();
            Instant beforeCreateExperimentItems = Instant.now();

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            assertThat(actualEntity.content()).hasSize(datasets.size());
            assertThat(actualEntity.total()).isEqualTo(datasets.size());
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(datasets.size());

            for (int i = 0; i < actualEntity.content().size(); i++) {
                var dataset = actualEntity.content().get(i);

                assertThat(dataset.experimentCount()).isEqualTo(1);
                assertThat(dataset.datasetItemsCount()).isEqualTo(1);
                assertThat(dataset.mostRecentExperimentAt()).isAfter(beforeCreateExperimentItems);
            }
        }

        @Test
        @DisplayName("when searching by dataset with experiments only but no experiment found, then return empty page")
        void getDatasets__whenSearchingByDatasetWithExperimentsOnlyButNoExperimentFound__thenReturnEmptyPage() {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            datasets.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("with_experiments_only", true)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            findAndAssertPage(actualEntity, 0, 0, 1, List.of());
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when searching by dataset with experiments only and result having {} datasets, then return page")
        void getDatasets__whenSearchingByDatasetWithExperimentsOnlyAndResultHavingXDatasets__thenReturnPage(
                int datasetCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expectedDatasets = IntStream.range(0, datasetCount)
                    .parallel()
                    .mapToObj(i -> createDatasetWithExperiment(apiKey, workspaceName, null))
                    .sorted(Comparator.comparing(Dataset::id))
                    .toList();

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", expectedDatasets.size())
                    .queryParam("with_experiments_only", true)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            findAndAssertPage(actualEntity, expectedDatasets.size(), expectedDatasets.size(), 1,
                    expectedDatasets.reversed());
        }

        Stream<Arguments> getDatasets__whenSearchingByDatasetWithExperimentsOnlyAndResultHavingXDatasets__thenReturnPage() {
            return Stream.of(
                    arguments(10),
                    arguments(100),
                    arguments(110));
        }

        @Test
        @DisplayName("when searching by dataset with optimizations only and result having {} datasets, then return page")
        void getDatasets__whenSearchingByDatasetWithOptimizationsOnlyAndResultHavingXDatasets__thenReturnPage() {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dataset> expectedDatasets = IntStream.range(0, 10)
                    .parallel()
                    .mapToObj(i -> createDatasetWithOptimization(apiKey, workspaceName))
                    .sorted(Comparator.comparing(Dataset::id))
                    .toList();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                                .queryParam("size", expectedDatasets.size())
                                .queryParam("with_optimizations_only", true)
                                .request()
                                .header(HttpHeaders.AUTHORIZATION, apiKey)
                                .header(WORKSPACE_HEADER, workspaceName)
                                .get();

                        var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

                        findAndAssertPage(actualEntity, expectedDatasets.size(), expectedDatasets.size(), 1,
                                expectedDatasets.reversed());
                    });

        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when searching by dataset with experiments only, name {}, and result having {} datasets, then return page")
        void getDatasets__whenSearchingByDatasetWithExperimentsOnlyAndNameXAndResultHavingXDatasets__thenReturnPage(
                String datasetNamePrefix, int datasetCount, int expectedMatchCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            int unexpectedDatasetCount = datasetCount - expectedMatchCount;

            IntStream.range(0, unexpectedDatasetCount)
                    .parallel()
                    .mapToObj(i -> createDatasetWithExperiment(apiKey, workspaceName, null))
                    .sorted(Comparator.comparing(Dataset::id))
                    .toList();

            List<Dataset> expectedMatchedDatasets = IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .mapToObj(i -> createDatasetWithExperiment(apiKey, workspaceName, datasetNamePrefix))
                    .sorted(Comparator.comparing(Dataset::id))
                    .toList();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                                .queryParam("size", expectedMatchedDatasets.size())
                                .queryParam("with_experiments_only", true)
                                .queryParam("name", datasetNamePrefix)
                                .request()
                                .header(HttpHeaders.AUTHORIZATION, apiKey)
                                .header(WORKSPACE_HEADER, workspaceName)
                                .get();

                        var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

                        findAndAssertPage(actualEntity, expectedMatchedDatasets.size(), expectedMatchedDatasets.size(),
                                1,
                                expectedMatchedDatasets.reversed());
                    });
        }

        Stream<Arguments> getDatasets__whenSearchingByDatasetWithExperimentsOnlyAndNameXAndResultHavingXDatasets__thenReturnPage() {
            return Stream.of(
                    arguments(UUID.randomUUID().toString(), 10, 5),
                    arguments(UUID.randomUUID().toString(), 100, 50),
                    arguments(UUID.randomUUID().toString(), 110, 10));
        }

        private Dataset createDatasetWithExperiment(String apiKey, String workspaceName, String datasetNamePrefix) {
            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .name(datasetNamePrefix == null
                            ? UUID.randomUUID().toString()
                            : datasetNamePrefix + " " + UUID.randomUUID())
                    .build();

            createAndAssert(dataset, apiKey, workspaceName);

            Experiment experiment = experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .type(null)
                    .build();

            createAndAssert(
                    experiment,
                    apiKey,
                    workspaceName);

            experiment = getExperiment(apiKey, workspaceName, experiment);

            return dataset.toBuilder()
                    .experimentCount(1L)
                    .lastCreatedExperimentAt(experiment.createdAt())
                    .mostRecentExperimentAt(experiment.createdAt())
                    .build();
        }

        private Dataset createDatasetWithOptimization(String apiKey, String workspaceName) {
            var dataset = factory.manufacturePojo(Dataset.class);
            createAndAssert(dataset, apiKey, workspaceName);

            var optimization = optimizationResourceClient.createPartialOptimization().datasetName(dataset.name())
                    .build();
            optimizationResourceClient.create(optimization, apiKey, workspaceName);

            return dataset.toBuilder()
                    .optimizationCount(1L)
                    .build();
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when searching by prompt id and result having {} datasets linked to experiments with prompt id, then return page")
        void getDatasets__whenSearchingByPromptIdAndResultHavingXDatasetsLinkedToExperimentsWithPromptId__thenReturnPage(
                int datasetCount, int expectedMatchCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            IntStream.range(0, datasetCount - expectedMatchCount)
                    .parallel()
                    .forEach(i -> createDatasetWithExperiment(apiKey, workspaceName, null));

            Prompt prompt = factory.manufacturePojo(Prompt.class).toBuilder().latestVersion(null).build();

            PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);

            List<Dataset> expectedDatasets = IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .mapToObj(i -> {
                        var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                                .name(UUID.randomUUID().toString())
                                .build();

                        createAndAssert(dataset, apiKey, workspaceName);

                        Experiment experiment = factory.manufacturePojo(Experiment.class).toBuilder()
                                .datasetName(dataset.name())
                                .type(null)
                                .promptVersion(
                                        Experiment.PromptVersionLink.builder()
                                                .promptId(promptVersion.promptId())
                                                .id(promptVersion.id())
                                                .commit(promptVersion.commit())
                                                .build())
                                .promptVersions(null)
                                .build();

                        createAndAssert(
                                experiment,
                                apiKey,
                                workspaceName);

                        experiment = getExperiment(apiKey, workspaceName, experiment);

                        // Create trial experiment for the same dataset, should not be included in experiment count
                        Experiment trial = factory.manufacturePojo(Experiment.class).toBuilder()
                                .datasetName(dataset.name())
                                .type(ExperimentType.TRIAL)
                                .promptVersion(null)
                                .promptVersions(null)
                                .build();

                        createAndAssert(
                                trial,
                                apiKey,
                                workspaceName);

                        return dataset.toBuilder()
                                .experimentCount(1L)
                                .lastCreatedExperimentAt(experiment.createdAt())
                                .mostRecentExperimentAt(experiment.createdAt())
                                .build();
                    })
                    .sorted(Comparator.comparing(Dataset::id))
                    .toList();

            var actualEntity = datasetResourceClient.getDatasetPage(apiKey, workspaceName, expectedDatasets.size(),
                    promptVersion);

            findAndAssertPage(actualEntity, expectedDatasets.size(), expectedDatasets.size(), 1,
                    expectedDatasets.reversed());
        }

        @ParameterizedTest
        @MethodSource("getDatasets__whenSearchingByPromptIdAndResultHavingXDatasetsLinkedToExperimentsWithPromptId__thenReturnPage")
        @DisplayName("when searching by prompt id and result having {} datasets linked to experiments with list of prompt ids, then return page")
        void getDatasets__whenSearchingByPromptIdAndResultHavingXDatasetsLinkedToExperimentsWithListOfPromptIds__thenReturnPage(
                int datasetCount, int expectedMatchCount) {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            IntStream.range(0, datasetCount - expectedMatchCount)
                    .parallel()
                    .forEach(i -> createDatasetWithExperiment(apiKey, workspaceName, null));

            Prompt prompt = factory.manufacturePojo(Prompt.class).toBuilder().latestVersion(null).build();

            PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);

            List<Dataset> expectedDatasets = IntStream.range(0, expectedMatchCount)
                    .parallel()
                    .mapToObj(i -> {
                        var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                                .name(UUID.randomUUID().toString())
                                .build();

                        createAndAssert(dataset, apiKey, workspaceName);

                        Experiment experiment = factory.manufacturePojo(Experiment.class).toBuilder()
                                .datasetName(dataset.name())
                                .type(null)
                                .promptVersion(null)
                                .promptVersions(List.of(
                                        Experiment.PromptVersionLink.builder()
                                                .promptId(promptVersion.promptId())
                                                .id(promptVersion.id())
                                                .commit(promptVersion.commit())
                                                .build()))
                                .build();

                        createAndAssert(
                                experiment,
                                apiKey,
                                workspaceName);

                        experiment = getExperiment(apiKey, workspaceName, experiment);

                        return dataset.toBuilder()
                                .experimentCount(1L)
                                .lastCreatedExperimentAt(experiment.createdAt())
                                .mostRecentExperimentAt(experiment.createdAt())
                                .build();
                    })
                    .sorted(Comparator.comparing(Dataset::id))
                    .toList();

            var actualEntity = datasetResourceClient.getDatasetPage(apiKey, workspaceName, expectedDatasets.size(),
                    promptVersion);

            findAndAssertPage(actualEntity, expectedDatasets.size(), expectedDatasets.size(), 1,
                    expectedDatasets.reversed());
        }

        Stream<Arguments> getDatasets__whenSearchingByPromptIdAndResultHavingXDatasetsLinkedToExperimentsWithPromptId__thenReturnPage() {
            return Stream.of(
                    arguments(10, 0),
                    arguments(10, 5));
        }
    }

    private Experiment getExperiment(String apiKey, String workspaceName, Experiment experiment) {

        try (var actualResponse = client.target(EXPERIMENT_RESOURCE_URI.formatted(baseURI))
                .path(experiment.id().toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.hasEntity()).isTrue();

            return actualResponse.readEntity(Experiment.class);
        }

    }

    private void findAndAssertPage(Dataset.DatasetPage actualEntity, int expected, int total, int page,
            List<Dataset> expectedContent) {
        assertThat(actualEntity.size()).isEqualTo(expected);
        assertThat(actualEntity.content()).hasSize(expected);
        assertThat(actualEntity.page()).isEqualTo(page);
        assertThat(actualEntity.total()).isEqualTo(total);

        assertThat(actualEntity.content())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DATASET_IGNORED_FIELDS)
                .isEqualTo(expectedContent);
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateDataset {

        public Stream<Arguments> invalidDataset() {
            return Stream.of(
                    arguments(factory.manufacturePojo(DatasetUpdate.class).toBuilder().name(null).build(),
                            "name must not be blank"),
                    arguments(factory.manufacturePojo(DatasetUpdate.class).toBuilder().name("").build(),
                            "name must not be blank"),
                    arguments(
                            factory.manufacturePojo(DatasetUpdate.class).toBuilder().description("").build(),
                            "description must not be blank"));
        }

        @Test
        @DisplayName("Success")
        void updateDataset() {
            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            var datasetUpdate = factory.manufacturePojo(DatasetUpdate.class);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(datasetUpdate, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var expectedDataset = dataset.toBuilder()
                    .name(datasetUpdate.name())
                    .description(datasetUpdate.description())
                    .visibility(datasetUpdate.visibility())
                    .build();

            getAndAssertEquals(id, expectedDataset, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when dataset not found, then return 404")
        void updateDataset__whenDatasetNotFound__thenReturn404() {
            var datasetUpdate = factory.manufacturePojo(DatasetUpdate.class);
            var id = UUID.randomUUID().toString();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id)
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(datasetUpdate, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
            }
        }

        @ParameterizedTest
        @MethodSource("invalidDataset")
        @DisplayName("when updating request is not valid, then return 422")
        void updateDataset__whenUpdatingRequestIsNotValid__thenReturn422(DatasetUpdate datasetUpdate,
                String errorMessage) {
            var id = factory.manufacturePojo(Dataset.class).id();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(datasetUpdate, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }

        }

        @Test
        @DisplayName("when updating only name, then update only name")
        void updateDataset__whenUpdatingOnlyName__thenUpdateOnlyName() {
            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            var datasetUpdate = factory.manufacturePojo(DatasetUpdate.class)
                    .toBuilder()
                    .description(null)
                    .visibility(null)
                    .build();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(datasetUpdate, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var expectedDataset = dataset.toBuilder().name(datasetUpdate.name())
                    .description(datasetUpdate.description()).build();
            getAndAssertEquals(id, expectedDataset, TEST_WORKSPACE, API_KEY);
        }
    }

    @Nested
    @DisplayName("Delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteDataset {

        @Test
        @DisplayName("Success")
        void deleteDataset() {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            assertThat(actualResponse.hasEntity()).isTrue();
            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
        }

        @Test
        @DisplayName("delete batch datasets")
        void deleteDatasetsBatch() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var ids = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class).stream()
                    .map(dataset -> createAndAssert(dataset, apiKey, workspaceName)).toList();
            var idsToDelete = ids.subList(0, 3);
            var notDeletedIds = ids.subList(3, ids.size());

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("delete-batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new BatchDelete(new HashSet<>(idsToDelete))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .queryParam("size", ids.size())
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualEntity.size()).isEqualTo(notDeletedIds.size());
            assertThat(actualEntity.content().stream().map(Dataset::id).toList())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .isEqualTo(notDeletedIds);
        }

        @Test
        @DisplayName("when dataset does not exists, then return no content")
        void deleteDataset__whenDatasetDoesNotExists__thenReturnNoContent() {
            var id = UUID.randomUUID().toString();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id)
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }

        @Test
        @DisplayName("when deleting by dataset name, then return no content")
        void deleteDataset__whenDeletingByDatasetName__thenReturnNoContent() {
            var dataset = factory.manufacturePojo(Dataset.class);

            var id = createAndAssert(dataset);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            assertThat(actualResponse.hasEntity()).isTrue();
            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
        }

        @Test
        @DisplayName("when deleting by dataset name and dataset does not exist, then return no content")
        void deleteDataset__whenDeletingByDatasetNameAndDatasetDoesNotExist__thenReturnNotFound() {
            var dataset = factory.manufacturePojo(Dataset.class);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
            }

        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when deleting dataset should update optimization dataset_deleted")
        void deletingDataset__shouldUpdateOptimizationDatasetDeleted__thenReturnNotFound(
                Consumer<Dataset> datasetDeleteAction) {
            var dataset = factory.manufacturePojo(Dataset.class);
            createAndAssert(dataset);

            var optimizations = IntStream.range(0, 10)
                    .mapToObj(i -> {
                        var optimization = optimizationResourceClient.createPartialOptimization()
                                .datasetName(dataset.name())
                                .build();
                        var optimizationId = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE);

                        return optimization.toBuilder().id(optimizationId).datasetName(null).build();
                    })
                    .toList();

            // Check that we do not have optimizations with deleted datasets
            var page = optimizationResourceClient.find(API_KEY, TEST_WORKSPACE, 1, optimizations.size(), null, null,
                    true, 200);
            assertThat(page.size()).isEqualTo(0);

            // Delete dataset
            datasetDeleteAction.accept(dataset);

            // Check that now we have one optimization with deleted datasets
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        var pageWithDeleted = optimizationResourceClient.find(API_KEY, TEST_WORKSPACE, 1,
                                optimizations.size(), null,
                                null, true, 200);
                        assertThat(pageWithDeleted.size()).isEqualTo(optimizations.size());

                        assertThat(pageWithDeleted.content())
                                .usingRecursiveComparison()
                                .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .isEqualTo(optimizations.reversed());
                    });

            // Clean up
            optimizationResourceClient.delete(optimizations.stream().map(Optimization::id).collect(toSet()), API_KEY,
                    TEST_WORKSPACE);
        }

        private Stream<Consumer<Dataset>> deletingDataset__shouldUpdateOptimizationDatasetDeleted__thenReturnNotFound() {
            return Stream.of(
                    dataset -> datasetResourceClient.deleteDataset(dataset.id(), API_KEY, TEST_WORKSPACE),
                    dataset -> datasetResourceClient.deleteDatasetByName(dataset.name(), API_KEY, TEST_WORKSPACE),
                    dataset -> datasetResourceClient.deleteDatasetsBatch(Set.of(dataset.id()), API_KEY,
                            TEST_WORKSPACE));
        }
    }

    @Nested
    @DisplayName("Create dataset items:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateDatasetItems {

        @Test
        @DisplayName("Success")
        void createDatasetItem() {
            var item1 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .build();

            var item2 = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(null)
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item1, item2))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when item id is null, then return no content and create item")
        void createDatasetItem__whenItemIdIsNull__thenReturnNoContentAndCreateItem() {
            var item = factory.manufacturePojo(DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            getItemAndAssert(item, TEST_WORKSPACE, API_KEY);
        }

        @ParameterizedTest
        @MethodSource("invalidDatasetItemBatches")
        @DisplayName("when dataset item batch is not valid, then return 422")
        void createDatasetItem__whenDatasetItemIsNotValid__thenReturn422(DatasetItemBatch batch, String errorMessage) {
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(batch))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }
        }

        public Stream<Arguments> invalidDatasetItemBatches() {
            return Stream.of(
                    arguments(
                            factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                                    .items(List.of()).build(),
                            "items size must be between 1 and 1000"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(null).build(),
                            "items must not be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .datasetName(null)
                            .datasetId(null)
                            .build(),
                            "The request body must provide either a dataset_name or a dataset_id"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .datasetName("")
                            .datasetId(null)
                            .build(),
                            "datasetName must not be blank"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .datasetId(null)
                            .items(IntStream.range(0, 1001).mapToObj(i -> factory.manufacturePojo(DatasetItem.class))
                                    .toList())
                            .build(),
                            "items size must be between 1 and 1000"));
        }

        @Test
        @DisplayName("when dataset id not found, then return 404")
        void createDatasetItem__whenDatasetIdNotFound__thenReturn404() {

            var batch = factory.manufacturePojo(DatasetItemBatch.class);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(batch, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset not found");
            }
        }

        @Test
        @DisplayName("when dataset item id not valid, then return bad request")
        void createDatasetItem__whenDatasetItemIdIsNotValid__thenReturnBadRequest() {

            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .id(UUID.randomUUID())
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(batch))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains("dataset_item id must be a version 7 UUID");
            }
        }

        @Test
        @DisplayName("when dataset item already exists, then return no content and update item")
        void createDatasetItem__whenDatasetItemAlreadyExists__thenReturnNoContentAndUpdateItem() {
            var item = factory.manufacturePojo(DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            getItemAndAssert(item, TEST_WORKSPACE, API_KEY);

            var newItem = factory.manufacturePojo(DatasetItem.class)
                    .toBuilder()
                    .id(item.id())
                    .build();

            putAndAssert(batch.toBuilder()
                    .items(List.of(newItem))
                    .build(), TEST_WORKSPACE, API_KEY);

            getItemAndAssert(newItem, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when dataset item support null values for data fields, then return no content and create item")
        void createDatasetItem__whenDatasetItemSupportNullValuesForDataFields__thenReturnNoContentAndCreateItem() {
            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .data(Map.of("test", NullNode.getInstance()))
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            getItemAndAssert(item, TEST_WORKSPACE, API_KEY);
        }

        @ParameterizedTest
        @MethodSource("invalidDatasetItems")
        @DisplayName("when dataset item batch contains duplicate items, then return 422")
        void createDatasetItem__whenDatasetItemBatchContainsDuplicateItems__thenReturn422(DatasetItemBatch batch,
                String errorMessage) {

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.entity(batch, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }
        }

        @Test
        @DisplayName("when dataset multiple items, then return no content and create items")
        void createDatasetItem__whenDatasetMultipleItems__thenReturnNoContentAndCreateItems() {
            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            items.forEach(item -> DatasetsResourceTest.this.getItemAndAssert(item, TEST_WORKSPACE, API_KEY));
        }

        public Stream<Arguments> invalidDatasetItems() {
            return Stream.of(
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .data(null)
                                    .build()))
                            .build(),
                            "items[0].data must not be empty"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .data(Map.of())
                                    .build()))
                            .build(),
                            "items[0].data must not be empty"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(null)
                                    .build()))
                            .build(),
                            "items[0].source must not be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.MANUAL)
                                    .spanId(factory.manufacturePojo(UUID.class))
                                    .traceId(null)
                                    .build()))
                            .build(),
                            "items[0].source when it is manual, span_id must be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.MANUAL)
                                    .spanId(null)
                                    .traceId(factory.manufacturePojo(UUID.class))
                                    .build()))
                            .build(),
                            "items[0].source when it is manual, trace_id must be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.SDK)
                                    .spanId(factory.manufacturePojo(UUID.class))
                                    .traceId(null)
                                    .build()))
                            .build(),
                            "items[0].source when it is sdk, span_id must be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.SDK)
                                    .traceId(factory.manufacturePojo(UUID.class))
                                    .spanId(null)
                                    .build()))
                            .build(),
                            "items[0].source when it is sdk, trace_id must be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.SPAN)
                                    .spanId(null)
                                    .traceId(factory.manufacturePojo(UUID.class))
                                    .build()))
                            .build(),
                            "items[0].source when it is span, span_id must not be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.SPAN)
                                    .traceId(null)
                                    .spanId(factory.manufacturePojo(UUID.class))
                                    .build()))
                            .build(),
                            "items[0].source when it is span, trace_id must not be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.TRACE)
                                    .spanId(factory.manufacturePojo(UUID.class))
                                    .traceId(factory.manufacturePojo(UUID.class))
                                    .build()))
                            .build(),
                            "items[0].source when it is trace, span_id must be null"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                            .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                    .source(DatasetItemSource.TRACE)
                                    .spanId(null)
                                    .traceId(null)
                                    .build()))
                            .build(),
                            "items[0].source when it is trace, trace_id must not be null"));
        }

        @Test
        @DisplayName("when dataset item batch has max size, then return no content and create")
        void createDatasetItem__whenDatasetItemBatchHasMaxSize__thenReturnNoContentAndCreate() {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            UUID id = createAndAssert(dataset);

            var items = IntStream.range(0, 1000)
                    .mapToObj(__ -> factory.manufacturePojo(DatasetItem.class).toBuilder()
                            .experimentItems(null)
                            .createdAt(null)
                            .lastUpdatedAt(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(id)
                    .datasetName(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when dataset item workspace and trace workspace does not match, then return conflict")
        void createDatasetItem__whenDatasetItemWorkspaceAndTraceWorkspaceDoesNotMatch__thenReturnConflict() {

            String workspaceName2 = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName2, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);

            var datasetId = createAndAssert(dataset, apiKey, workspaceName2);

            UUID traceId = createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(UUID.randomUUID().toString())
                    .build(), API_KEY, TEST_WORKSPACE);

            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .traceId(traceId)
                    .spanId(null)
                    .source(DatasetItemSource.TRACE)
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(datasetId)
                    .build();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName2)
                    .put(Entity.entity(batch, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(
                        "trace workspace and dataset item workspace does not match");
            }
        }

        @Test
        @DisplayName("when dataset item workspace and span workspace does not match, then return conflict")
        void createDatasetItem__whenDatasetItemWorkspaceAndSpanWorkspaceDoesNotMatch__thenReturnConflict() {

            String workspaceName1 = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            String projectName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName1, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);

            var datasetId = createAndAssert(dataset, apiKey, workspaceName1);

            UUID traceId = createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build(), apiKey, workspaceName1);

            UUID spanId = createSpan(factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .build(), API_KEY, TEST_WORKSPACE);

            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .spanId(spanId)
                    .traceId(traceId)
                    .source(DatasetItemSource.SPAN)
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(datasetId)
                    .build();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName1)
                    .put(Entity.json(batch))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(
                        "span workspace and dataset item workspace does not match");
            }
        }
    }

    private UUID createTrace(Trace trace, String apiKey, String workspaceName) {
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private UUID createSpan(Span span, String apiKey, String workspaceName) {
        return spanResourceClient.createSpan(span, apiKey, workspaceName);
    }

    @Nested
    @DisplayName("Get dataset items {id}:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetDatasetItem {

        @Test
        @DisplayName("Success")
        void getDatasetItemById() {

            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            getItemAndAssert(item, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when dataset item not found, then return 404")
        void getDatasetItemById__whenDatasetItemNotFound__thenReturn404() {
            String id = UUID.randomUUID().toString();

            var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path(id)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            assertThat(actualResponse.hasEntity()).isTrue();
            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset item not found");
        }

    }

    @Nested
    @DisplayName("Stream dataset items by {datasetId}:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamDatasetItems {

        @Test
        @DisplayName("when streaming dataset items, then return items sorted by created date")
        void streamDataItems__whenStreamingDatasetItems__thenReturnItemsSortedByCreatedDate() {

            var items = IntStream.range(0, 10)
                    .mapToObj(i -> factory.manufacturePojo(DatasetItem.class))
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            var streamRequest = DatasetItemStreamRequest.builder()
                    .datasetName(batch.datasetName())
                    .build();

            try (Response response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(streamRequest))) {

                assertThat(response.getStatus()).isEqualTo(200);

                List<DatasetItem> actualItems = getStreamedItems(response);

                assertPage(items.reversed(), actualItems);
            }
        }

        @Test
        @DisplayName("when streaming dataset items with filters, then return items sorted by created date")
        void streamDataItems__whenStreamingDatasetItemsWithFilters__thenReturnItemsSortedByCreatedDate() {

            var items = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(DatasetItem.class))
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            var streamRequest = DatasetItemStreamRequest.builder()
                    .datasetName(batch.datasetName())
                    .lastRetrievedId(items.reversed().get(1).id())
                    .build();

            try (Response response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(streamRequest))) {

                assertThat(response.getStatus()).isEqualTo(200);

                List<DatasetItem> actualItems = getStreamedItems(response);

                assertPage(items.reversed().subList(2, 5), actualItems);
            }
        }

        @Test
        @DisplayName("when streaming has max steamLimit, then return items sorted by created date")
        void streamDataItems__whenStreamingHasMaxSize__thenReturnItemsSortedByCreatedDate() {

            var items = IntStream.range(0, 1000)
                    .mapToObj(i -> factory.manufacturePojo(DatasetItem.class).toBuilder()
                            .experimentItems(null)
                            .createdAt(null)
                            .lastUpdatedAt(null)
                            .build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            List<DatasetItem> expectedFirstPage = items.reversed().subList(0, 500);

            var streamRequest = DatasetItemStreamRequest.builder()
                    .datasetName(batch.datasetName()).build();

            try (Response response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(streamRequest))) {

                assertThat(response.getStatus()).isEqualTo(200);

                List<DatasetItem> actualItems = getStreamedItems(response);

                assertPage(expectedFirstPage, actualItems);
            }

            streamRequest = DatasetItemStreamRequest.builder()
                    .datasetName(batch.datasetName())
                    .lastRetrievedId(expectedFirstPage.get(499).id())
                    .build();

            try (Response response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(streamRequest))) {

                assertThat(response.getStatus()).isEqualTo(200);

                List<DatasetItem> actualItems = getStreamedItems(response);

                assertPage(items.reversed().subList(500, 1000), actualItems);
            }
        }
    }

    private void getItemAndAssert(DatasetItem expectedDatasetItem, String workspaceName, String apiKey) {
        var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path("items")
                .path(expectedDatasetItem.id().toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        var actualEntity = actualResponse.readEntity(DatasetItem.class);
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

        assertThat(actualEntity.id()).isEqualTo(expectedDatasetItem.id());
        assertThat(actualEntity).usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_DATA_ITEM)
                .isEqualTo(expectedDatasetItem);

        assertThat(actualEntity.createdAt()).isInThePast();
        assertThat(actualEntity.lastUpdatedAt()).isInThePast();
    }

    @Nested
    @DisplayName("Delete items:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteDatasetItems {

        @Test
        @DisplayName("Success")
        void deleteDatasetItem() {
            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            var itemIds = items.stream().map(DatasetItem::id).toList();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetItemsDelete(itemIds)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            }

            for (var item : items) {
                var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                        .path("items")
                        .path(item.id().toString())
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .get();

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Dataset item not found");
            }
        }

        @Test
        @DisplayName("when dataset item does not exists, then return no content")
        void deleteDatasetItem__whenDatasetItemDoesNotExists__thenReturnNoContent() {
            var id = UUID.randomUUID().toString();
            var itemIds = List.of(UUID.fromString(id));

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("delete")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetItemsDelete(itemIds)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }

        @ParameterizedTest
        @MethodSource("invalidDatasetItemBatches")
        @DisplayName("when dataset item batch is not valid, then return 422")
        void deleteDatasetItem__whenDatasetItemIsNotValid__thenReturn422(List<UUID> itemIds, String errorMessage) {
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(new DatasetItemsDelete(itemIds)))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(errorMessage);
            }
        }

        public Stream<Arguments> invalidDatasetItemBatches() {
            return Stream.of(
                    arguments(List.of(),
                            "itemIds size must be between 1 and 1000"),
                    arguments(null,
                            "itemIds must not be null"),
                    arguments(IntStream.range(1, 10001).mapToObj(__ -> UUID.randomUUID()).toList(),
                            "itemIds size must be between 1 and 1000"));
        }
    }

    @Nested
    @DisplayName("Get dataset items by dataset id:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetDatasetItemsByDatasetId {

        @Test
        @DisplayName("Success")
        void getDatasetItemsByDatasetId() {

            UUID datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();

            List<Map<String, JsonNode>> data = batch.items()
                    .stream()
                    .map(DatasetItem::data)
                    .toList();

            Set<Column> columns = getColumns(data);

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                assertDatasetItemPage(actualEntity, items.reversed(), columns, 1);
            }
        }

        @Test
        @DisplayName("when defining page size, then return page with limit respected")
        void getDatasetItemsByDatasetId__whenDefiningPageSize__thenReturnPageWithLimitRespected() {

            UUID datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();

            List<Map<String, JsonNode>> data = batch.items()
                    .stream()
                    .map(DatasetItem::data)
                    .toList();

            Set<Column> columns = getColumns(data);

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .queryParam("size", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                List<DatasetItem> expectedContent = List.of(items.reversed().getFirst());

                assertDatasetItemPage(actualEntity, expectedContent, 5, columns, 1);
            }
        }

        @Test
        @DisplayName("when items were updated, then return correct items count")
        void getDatasetItemsByDatasetId__whenItemsWereUpdated__thenReturnCorrectItemsCount() {

            UUID datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            var updatedItems = items
                    .stream()
                    .map(item -> item.toBuilder()
                            .data(Map.of(factory.manufacturePojo(String.class),
                                    factory.manufacturePojo(JsonNode.class)))
                            .build())
                    .toList();

            var updatedBatch = batch.toBuilder()
                    .items(updatedItems)
                    .build();

            putAndAssert(updatedBatch, TEST_WORKSPACE, API_KEY);

            List<Map<String, JsonNode>> data = updatedBatch.items()
                    .stream()
                    .map(DatasetItem::data)
                    .toList();

            Set<Column> columns = getColumns(data);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                assertDatasetItemPage(actualEntity, updatedItems.reversed(), columns, 1);
            }
        }

        @Test
        @DisplayName("when items have data with same keys and different types, then return columns types and count")
        void getDatasetItemsByDatasetId__whenItemsHaveDataWithSameKeysAndDifferentTypes__thenReturnColumnsTypesAndCount() {

            UUID datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var item = factory.manufacturePojo(DatasetItem.class);

            var item2 = item.toBuilder()
                    .id(factory.manufacturePojo(UUID.class))
                    .data(item.data()
                            .keySet()
                            .stream()
                            .map(key -> Map.entry(key, NullNode.getInstance()))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .build();

            var item3 = item.toBuilder()
                    .id(factory.manufacturePojo(UUID.class))
                    .data(item.data()
                            .keySet()
                            .stream()
                            .map(key -> Map.entry(key, TextNode.valueOf(RandomStringUtils.randomAlphanumeric(10))))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item, item2, item3))
                    .datasetId(datasetId)
                    .build();

            List<Map<String, JsonNode>> data = batch.items()
                    .stream()
                    .map(DatasetItem::data)
                    .toList();

            Set<Column> columns = getColumns(data);

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                assertDatasetItemPage(actualEntity, batch.items().reversed(), columns, 1);
            }

        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void getDatasetItemsByDatasetId_withTruncation(JsonNode original, JsonNode expected, boolean truncate) {

            UUID datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build());

            var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                    .map(item -> item.toBuilder().data(ImmutableMap.of("image", original)).build())
                    .toList();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();

            List<Map<String, JsonNode>> data = batch.items()
                    .stream()
                    .map(DatasetItem::data)
                    .toList();

            Set<Column> columns = getColumns(data);

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            var expectedDatasetItems = items.stream()
                    .map(item -> item.toBuilder().data(ImmutableMap.of("image", expected)).build())
                    .toList().reversed();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .queryParam("truncate", truncate)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                assertDatasetItemPage(actualEntity, expectedDatasetItems, columns, 1);
            }
        }
    }

    private void assertDatasetItemPage(DatasetItemPage actualPage, List<DatasetItem> expected, Set<Column> columns,
            int page) {
        assertDatasetItemPage(actualPage, expected, expected.size(), columns, page);
    }

    private void assertDatasetItemPage(DatasetItemPage actualPage, List<DatasetItem> expected, int total,
            Set<Column> columns, int page) {
        assertThat(actualPage.size()).isEqualTo(expected.size());
        assertThat(actualPage.content()).hasSize(expected.size());
        assertThat(actualPage.page()).isEqualTo(page);
        assertThat(actualPage.total()).isEqualTo(total);
        assertThat(actualPage.columns()).isEqualTo(columns);

        assertPage(expected, actualPage.content());
    }

    private void assertPage(List<DatasetItem> expectedItems, List<DatasetItem> actualItems) {

        List<String> ignoredFields = new ArrayList<>(Arrays.asList(IGNORED_FIELDS_DATA_ITEM));
        ignoredFields.add("data");

        assertThat(actualItems)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ignoredFields.toArray(String[]::new))
                .isEqualTo(expectedItems);

        assertThat(actualItems).hasSize(expectedItems.size());
        for (int i = 0; i < actualItems.size(); i++) {
            var actualDatasetItem = actualItems.get(i);
            var expectedDatasetItem = expectedItems.get(i);

            assertThat(actualDatasetItem.data()).isEqualTo(expectedDatasetItem.data());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindDatasetItemsWithExperimentItems {

        @Test
        void find() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Creating two traces with input, output and scores
            var trace1 = factory.manufacturePojo(Trace.class);
            createAndAssert(trace1, workspaceName, apiKey);

            var trace2 = factory.manufacturePojo(Trace.class);
            createAndAssert(trace2, workspaceName, apiKey);
            var traces = List.of(trace1, trace2);

            // Creating 5 scores peach each of the two traces above
            var scores1 = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                            .id(trace1.id())
                            .projectName(trace1.projectName())
                            .value(factory.manufacturePojo(BigDecimal.class))
                            .build())
                    .toList();

            var scores2 = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                            .id(trace2.id())
                            .projectName(trace2.projectName())
                            .value(factory.manufacturePojo(BigDecimal.class))
                            .build())
                    .toList();

            var traceIdToScoresMap = Stream.concat(scores1.stream(), scores2.stream())
                    .collect(groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = factory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream)).toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            // Add comments to random trace
            List<Comment> expectedComments = IntStream.range(0, 5)
                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(trace1.id(), apiKey, workspaceName,
                            201))
                    .toList();

            // Creating a trace without input, output and scores
            var traceMissingFields = factory.manufacturePojo(Trace.class).toBuilder()
                    .input(null)
                    .output(null)
                    .build();
            createAndAssert(traceMissingFields, workspaceName, apiKey);

            // Creating the dataset
            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            // Creating 5 dataset items for the dataset above
            var datasetItemBatch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            putAndAssert(datasetItemBatch, workspaceName, apiKey);

            // Creating 5 different experiment ids
            var expectedDatasetItems = datasetItemBatch.items().subList(0, 4).reversed();
            var experimentIds = IntStream.range(0, 5).mapToObj(__ -> GENERATOR.generate()).toList();

            // Dataset items 0 and 1 cover the general case.
            // Per each dataset item there are 10 experiment items, so 2 experiment items per each of the 5 experiments.
            // The first 5 experiment items are related to trace 1, the other 5 to trace 2.
            var datasetItemIdToExperimentItemMap = expectedDatasetItems.subList(0, 2).stream()
                    .flatMap(datasetItem -> IntStream.range(0, 10)
                            .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .experimentId(experimentIds.get(i / 2))
                                    .datasetItemId(datasetItem.id())
                                    .traceId(traces.get(i / 5).id())
                                    .input(traces.get(i / 5).input())
                                    .output(traces.get(i / 5).output())
                                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                            traces.get(i / 5).startTime(), traces.get(i / 5).endTime()))
                                    .feedbackScores(traceIdToScoresMap.get(traces.get(i / 5).id()).stream()
                                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                            .toList())
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .collect(groupingBy(ExperimentItem::datasetItemId));

            // Dataset item 2 covers the case of experiments items related to a trace without input, output and scores.
            // It also has 2 experiment items per each of the 5 experiments.
            datasetItemIdToExperimentItemMap.put(expectedDatasetItems.get(2).id(), experimentIds.stream()
                    .flatMap(experimentId -> IntStream.range(0, 2)
                            .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .experimentId(experimentId)
                                    .datasetItemId(expectedDatasetItems.get(2).id())
                                    .traceId(traceMissingFields.id())
                                    .input(traceMissingFields.input())
                                    .output(traceMissingFields.output())
                                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                            traceMissingFields.startTime(), traceMissingFields.endTime()))
                                    .feedbackScores(null)
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList());

            // Dataset item 3 covers the case of experiments items related to an un-existing trace id.
            // It also has 2 experiment items per each of the 5 experiments.
            datasetItemIdToExperimentItemMap.put(expectedDatasetItems.get(3).id(), experimentIds.stream()
                    .flatMap(experimentId -> IntStream.range(0, 2)
                            .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                                    .experimentId(experimentId)
                                    .datasetItemId(expectedDatasetItems.get(3).id())
                                    .input(null)
                                    .output(null)
                                    .feedbackScores(null)
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .duration(null)
                                    .build()))
                    .toList());

            // Dataset item 4 covers the case of not matching experiment items.

            // When storing the experiment items in batch, adding some more unrelated random ones
            var experimentItemsBatch = factory.manufacturePojo(ExperimentItemsBatch.class);
            experimentItemsBatch = experimentItemsBatch.toBuilder()
                    .experimentItems(Stream.concat(experimentItemsBatch.experimentItems().stream()
                            .map(item -> item.toBuilder()
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .duration(null)
                                    .build()),
                            datasetItemIdToExperimentItemMap.values().stream().flatMap(Collection::stream))
                            .collect(toUnmodifiableSet()))
                    .build();
            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            List<Map<String, JsonNode>> data = datasetItemBatch.items().stream()
                    .map(DatasetItem::data)
                    .toList();

            Set<Column> columns = getColumns(data);

            var page = 1;
            var pageSize = 5;
            // Filtering by experiments 1 and 3.
            var experimentIdsQueryParm = JsonUtils
                    .writeValueAsString(List.of(experimentIds.get(1), experimentIds.get(3)));

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("page", page)
                    .queryParam("size", pageSize)
                    .queryParam("experiment_ids", experimentIdsQueryParm)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                var actualPage = actualResponse.readEntity(DatasetItemPage.class);

                assertThat(actualPage.page()).isEqualTo(page);
                assertThat(actualPage.size()).isEqualTo(expectedDatasetItems.size());
                assertThat(actualPage.total()).isEqualTo(expectedDatasetItems.size());
                assertThat(actualPage.columns()).isEqualTo(columns);

                var actualDatasetItems = actualPage.content();

                assertPage(expectedDatasetItems, actualPage.content());

                for (var i = 0; i < actualDatasetItems.size(); i++) {
                    var actualDatasetItem = actualDatasetItems.get(i);
                    var expectedDatasetItem = expectedDatasetItems.get(i);

                    // Filtering by those related to experiments 1 and 3
                    var experimentItems = datasetItemIdToExperimentItemMap.get(expectedDatasetItem.id());
                    var expectedExperimentItems = List.of(
                            experimentItems.get(2),
                            experimentItems.get(3),
                            experimentItems.get(6),
                            experimentItems.get(7)).reversed();

                    assertThat(actualDatasetItem.experimentItems())
                            .usingRecursiveComparison()
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                            .ignoringFields(IGNORED_FIELDS_LIST)
                            .isEqualTo(expectedExperimentItems);

                    for (var j = 0; j < actualDatasetItem.experimentItems().size(); j++) {
                        var actualExperimentItem = assertFeedbackScoresIgnoredFieldsAndSetThemToNull(
                                actualDatasetItem.experimentItems().get(j), USER);
                        var expectedExperimentItem = expectedExperimentItems.get(j);

                        assertThat(actualExperimentItem.feedbackScores())
                                .usingRecursiveComparison()
                                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                .ignoringCollectionOrder()
                                .isEqualTo(expectedExperimentItem.feedbackScores());

                        assertThat(actualExperimentItem.createdAt())
                                .isAfter(expectedExperimentItem.createdAt());
                        assertThat(actualExperimentItem.lastUpdatedAt())
                                .isAfter(expectedExperimentItem.lastUpdatedAt());

                        assertThat(actualExperimentItem.createdBy())
                                .isEqualTo(USER);
                        assertThat(actualExperimentItem.lastUpdatedBy())
                                .isEqualTo(USER);

                        // Check comments
                        if (actualExperimentItem.traceId().equals(trace1.id())) {
                            assertThat(expectedComments)
                                    .usingRecursiveComparison()
                                    .ignoringFields(IGNORED_FIELDS_COMMENTS)
                                    .isEqualTo(actualExperimentItem.comments());
                        }
                    }

                    assertThat(actualDatasetItem.createdAt()).isAfter(expectedDatasetItem.createdAt());
                    assertThat(actualDatasetItem.lastUpdatedAt()).isAfter(expectedDatasetItem.lastUpdatedAt());
                }
            }
        }

        @Test
        void find__whenExperimentsHaveSpansWithLLMCalls__thenIncludeSpanData() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Creating two traces with input, output and scores

            var trace1 = factory.manufacturePojo(Trace.class);
            createAndAssert(trace1, workspaceName, apiKey);

            var trace2 = factory.manufacturePojo(Trace.class);
            createAndAssert(trace2, workspaceName, apiKey);

            var traces = List.of(trace1, trace2);

            Map<UUID, List<Span>> spansMap = traces.stream().map(trace -> {
                Span span1 = createSpan(trace, apiKey, workspaceName);
                Span span2 = createSpan(trace, apiKey, workspaceName);

                return Map.entry(trace.id(), List.of(span1, span2));
            }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            // Creating dataset and experiment items

            var dataset = factory.manufacturePojo(Dataset.class);

            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            // Creating 5 dataset items for the dataset above
            var datasetItemBatch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .datasetName(dataset.name())
                    .datasetId(datasetId)
                    .build();

            putAndAssert(datasetItemBatch, workspaceName, apiKey);

            var experiment = factory.manufacturePojo(Experiment.class).toBuilder()
                    .datasetName(dataset.name())
                    .promptVersion(null)
                    .promptVersions(null)
                    .build();

            createAndAssert(experiment, apiKey, workspaceName);

            // Creating 5 different experiment ids
            var experimentItems = IntStream
                    .range(0, 2).mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experiment.id())
                            .traceId(traces.get(i).id())
                            .input(traces.get(i).input())
                            .output(traces.get(i).output())
                            .usage(getUsage(spansMap, traces.get(i)))
                            .totalEstimatedCost(getTotalEstimatedCost(spansMap, traces.get(i)))
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traces.get(i).startTime(), traces.get(i).endTime()))
                            .datasetItemId(datasetItemBatch.items().get(i).id())
                            .comments(null)
                            .feedbackScores(null)
                            .build())
                    .toList();

            createAndAssert(new ExperimentItemsBatch(Set.copyOf(experimentItems)), apiKey, workspaceName);

            var otherExperimentItems = IntStream.range(0, 3)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experiment.id())
                            .usage(null)
                            .totalEstimatedCost(null)
                            .duration(null)
                            .datasetItemId(datasetItemBatch.items().get(i + 2).id())
                            .comments(null)
                            .feedbackScores(null)
                            .output(null)
                            .input(null)
                            .build())
                    .toList();

            createAndAssert(new ExperimentItemsBatch(Set.copyOf(otherExperimentItems)), apiKey, workspaceName);

            Set<Column> columns = datasetItemBatch.items()
                    .stream()
                    .flatMap(item -> item.data().entrySet().stream())
                    .map(column -> new Column(column.getKey(), Set.of(getType(column)), "data"))
                    .collect(toSet());

            List<DatasetItem> datasetItems = datasetItemBatch.items()
                    .stream()
                    .sorted(Comparator.comparing(DatasetItem::id).reversed())
                    .toList();

            List<ExperimentItem> expectedExperimentItems = new ArrayList<>();

            expectedExperimentItems.addAll(otherExperimentItems.reversed());
            expectedExperimentItems.addAll(experimentItems.reversed());

            assertPageAndContent(datasetId, List.of(experiment.id()), apiKey, workspaceName, expectedExperimentItems,
                    columns, datasetItems);
        }

        private void assertPageAndContent(UUID datasetId, List<UUID> experimentIds, String apiKey, String workspaceName,
                List<ExperimentItem> expectedExperimentItems, Set<Column> columns, List<DatasetItem> datasetItems) {
            var experimentIdsQueryParm = JsonUtils.writeValueAsString(experimentIds);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("experiment_ids", experimentIdsQueryParm)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                var actualPage = actualResponse.readEntity(DatasetItemPage.class);

                assertThat(actualPage.page()).isEqualTo(1);
                assertThat(actualPage.size()).isEqualTo(datasetItems.size());
                assertThat(actualPage.total()).isEqualTo(datasetItems.size());
                assertThat(actualPage.columns()).isEqualTo(columns);

                var actualDatasetItems = actualPage.content();

                assertPage(datasetItems, actualPage.content());

                for (var i = 0; i < actualDatasetItems.size(); i++) {
                    var actualDatasetItem = actualDatasetItems.get(i);
                    var expectedDatasetItem = datasetItems.get(i);
                    var expectedExperimentItem = expectedExperimentItems.get(i);

                    assertThat(actualDatasetItem.experimentItems())
                            .usingRecursiveComparison()
                            .ignoringFields(IGNORED_FIELDS_LIST)
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                            .isEqualTo(List.of(expectedExperimentItem));

                    for (var j = 0; j < actualDatasetItem.experimentItems().size(); j++) {
                        var actualExperimentItem = assertFeedbackScoresIgnoredFieldsAndSetThemToNull(
                                actualDatasetItem.experimentItems().get(j), USER);

                        assertThat(actualExperimentItem.feedbackScores())
                                .usingRecursiveComparison()
                                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                                .ignoringCollectionOrder()
                                .isEqualTo(expectedExperimentItem.feedbackScores());

                        assertThat(actualExperimentItem.createdAt())
                                .isAfter(expectedExperimentItem.createdAt());
                        assertThat(actualExperimentItem.lastUpdatedAt())
                                .isAfter(expectedExperimentItem.lastUpdatedAt());

                        assertThat(actualExperimentItem.createdBy())
                                .isEqualTo(USER);
                        assertThat(actualExperimentItem.lastUpdatedBy())
                                .isEqualTo(USER);
                    }

                    assertThat(actualDatasetItem.createdAt()).isAfter(expectedDatasetItem.createdAt());
                    assertThat(actualDatasetItem.lastUpdatedAt()).isAfter(expectedDatasetItem.lastUpdatedAt());
                }
            }
        }

        private Span createSpan(Trace trace, String apiKey, String workspaceName) {
            Span span = factory.manufacturePojo(Span.class).toBuilder()
                    .totalEstimatedCost(BigDecimal.valueOf(PodamUtils.getIntegerInRange(0, 10)))
                    .feedbackScores(null)
                    .totalEstimatedCostVersion(null)
                    .type(SpanType.llm)
                    .errorInfo(null)
                    .comments(null)
                    .traceId(trace.id())
                    .projectName(trace.projectName())
                    .build();

            spanResourceClient.createSpan(span, apiKey, workspaceName);

            return span;
        }

        private BigDecimal getTotalEstimatedCost(Map<UUID, List<Span>> spansMap, Trace trace) {
            return Optional.ofNullable(spansMap.get(trace.id()))
                    .stream()
                    .flatMap(List::stream)
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal::add)
                    .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                    .orElse(null);
        }

        private static Map<String, Long> getUsage(Map<UUID, List<Span>> spansMap, Trace trace) {
            return Optional.ofNullable(spansMap.get(trace.id()))
                    .map(spans -> StatsUtils.calculateUsage(
                            spans.stream()
                                    .map(it -> it.usage().entrySet()
                                            .stream()
                                            .collect(Collectors.toMap(
                                                    Map.Entry::getKey,
                                                    entry -> entry.getValue().longValue())))
                                    .toList()))
                    .orElse(null);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Creating traces with images to be truncated
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> trace.toBuilder()
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Creating the dataset
            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            // Creating 5 dataset items for the dataset above
            var datasetItemBatch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .datasetId(datasetId)
                    .build();
            var datasetItemBatchWithImage = datasetItemBatch.toBuilder()
                    .items(datasetItemBatch.items().stream()
                            .map(item -> item.toBuilder()
                                    .data(ImmutableMap.of("image", original)).build())
                            .toList())
                    .build();

            putAndAssert(datasetItemBatchWithImage, workspaceName, apiKey);

            // Creating 5 different experiment ids
            var experimentIds = IntStream.range(0, 5).mapToObj(__ -> GENERATOR.generate()).toList();
            List<ExperimentItem> experimentItems = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experimentIds.get(i))
                            .traceId(traces.get(i).id())
                            .usage(null)
                            .totalEstimatedCost(null)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                    traces.get(i).startTime(), traces.get(i).endTime()))
                            .datasetItemId(datasetItemBatchWithImage.items().get(i).id()).build())
                    .toList();

            var experimentItemsBatch = ExperimentItemsBatch.builder()
                    .experimentItems(Set.copyOf(experimentItems)).build();

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            List<List<ExperimentItem>> expectedExperimentItems = experimentItems.stream()
                    .map(item -> List.of(item.toBuilder()
                            .input(expected)
                            .output(expected).build()))
                    .toList();
            var expectedDatasetItems = IntStream.range(0, 5).mapToObj(i -> datasetItemBatchWithImage.items().get(i)
                    .toBuilder()
                    .data(ImmutableMap.of("image", expected))
                    .experimentItems(expectedExperimentItems.get(i))
                    .build()).toList().reversed();

            var page = 1;
            var pageSize = 5;
            var experimentIdsQueryParm = JsonUtils.writeValueAsString(experimentIds);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("page", page)
                    .queryParam("size", pageSize)
                    .queryParam("experiment_ids", experimentIdsQueryParm)
                    .queryParam("truncate", truncate)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var actualPage = actualResponse.readEntity(DatasetItemPage.class);

                assertThat(actualPage.page()).isEqualTo(page);
                assertThat(actualPage.size()).isEqualTo(expectedDatasetItems.size());
                assertThat(actualPage.total()).isEqualTo(expectedDatasetItems.size());

                assertPage(expectedDatasetItems, actualPage.content());

                assertThat(actualPage.content()).hasSize(expectedDatasetItems.size());
                for (int i = 0; i < expectedExperimentItems.size(); i++) {
                    assertThat(actualPage.content().get(i).experimentItems())
                            .usingRecursiveComparison()
                            .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                            .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                            .ignoringFields(IGNORED_FIELDS_LIST)
                            .isEqualTo(expectedExperimentItems.reversed().get(i));
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"[wrong_payload]", "[0191377d-06ee-7026-8f63-cc5309d1f54b]"})
        void findInvalidExperimentIds(String experimentIds) {
            var expectedErrorMessage = new io.dropwizard.jersey.errors.ErrorMessage(
                    400, "Invalid query param ids '%s'".formatted(experimentIds));

            var datasetId = GENERATOR.generate();
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("experiment_ids", experimentIds)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);

                var actualErrorMessage = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

                assertThat(actualErrorMessage).isEqualTo(expectedErrorMessage);
            }
        }

        @Test
        void find__whenNoMatchFound__thenReturnEmptyPageWithAlColumnsFromDataset() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            List<DatasetItem> items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

            var batch = DatasetItemBatch.builder()
                    .items(items)
                    .datasetId(datasetId)
                    .build();
            putAndAssert(batch, workspaceName, apiKey);

            String projectName = RandomStringUtils.randomAlphanumeric(20);
            List<Trace> traces = new ArrayList<>();
            createTraces(items, projectName, workspaceName, apiKey, traces);

            UUID experimentId = GENERATOR.generate();

            List<FeedbackScoreBatchItem> scores = new ArrayList<>();
            createScores(traces, projectName, scores);
            createScoreAndAssert(new FeedbackScoreBatch(scores), apiKey, workspaceName);

            List<ExperimentItem> experimentItems = new ArrayList<>();
            createExperimentItems(items, traces, scores, experimentId, experimentItems);

            createAndAssert(
                    ExperimentItemsBatch.builder()
                            .experimentItems(Set.copyOf(experimentItems))
                            .build(),
                    apiKey,
                    workspaceName);

            Set<Column> columns = getColumns(items.stream().map(DatasetItem::data).toList());

            List<Filter> filters = List.of(ExperimentsComparisonFilter.builder()
                    .type(FieldType.STRING)
                    .value(RandomStringUtils.randomAlphanumeric(16))
                    .field(RandomStringUtils.randomAlphanumeric(22))
                    .operator(Operator.EQUAL)
                    .build());

            assertDatasetExperimentPage(datasetId, experimentId, filters, apiKey, workspaceName, columns, List.of());
        }

        @ParameterizedTest
        @ValueSource(strings = {"$..test", "$meta_field", "[", "]", "[..]",})
        void findInvalidJsonPaths(String path) {

            var datasetId = GENERATOR.generate();
            var experimentId = GENERATOR.generate();
            var field = RandomStringUtils.randomAlphanumeric(5);

            var filters = List
                    .of(new ExperimentsComparisonFilter(field, FieldType.DICTIONARY, Operator.EQUAL, path, "10"));
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("experiment_ids", JsonUtils.writeValueAsString(List.of(experimentId)))
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                var datasetItemPage = actualResponse.readEntity(DatasetItemPage.class);

                assertThat(datasetItemPage.content()).isEmpty();
                assertThat(datasetItemPage.total()).isZero();
                assertThat(datasetItemPage.size()).isZero();
                assertThat(datasetItemPage.page()).isOne();
            }
        }

        @ParameterizedTest
        @MethodSource
        void find__whenFilteringBySupportedFields__thenReturnMatchingRows(Filter filter) {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            List<DatasetItem> datasetItems = new ArrayList<>();
            createDatasetItems(datasetItems);
            var batch = DatasetItemBatch.builder()
                    .items(datasetItems)
                    .datasetId(datasetId)
                    .build();
            putAndAssert(batch, workspaceName, apiKey);

            String projectName = RandomStringUtils.randomAlphanumeric(20);
            List<Trace> traces = new ArrayList<>();
            createTraces(datasetItems, projectName, workspaceName, apiKey, traces);

            UUID experimentId = GENERATOR.generate();

            List<FeedbackScoreBatchItem> scores = new ArrayList<>();
            createScores(traces, projectName, scores);
            createScoreAndAssert(new FeedbackScoreBatch(scores), apiKey, workspaceName);

            List<ExperimentItem> experimentItems = new ArrayList<>();
            createExperimentItems(datasetItems, traces, scores, experimentId, experimentItems);

            createAndAssert(
                    ExperimentItemsBatch.builder()
                            .experimentItems(Set.copyOf(experimentItems))
                            .build(),
                    apiKey,
                    workspaceName);

            Set<Column> columns = getColumns(datasetItems.stream().map(DatasetItem::data).toList());

            List<Filter> filters = List.of(filter);

            var expectedDatasetItems = filter.operator() != Operator.NOT_EQUAL
                    ? List.of(datasetItems.getFirst())
                    : datasetItems.subList(1, datasetItems.size()).reversed();
            var expectedExperimentItems = filter.operator() != Operator.NOT_EQUAL
                    ? List.of(experimentItems.getFirst())
                    : experimentItems.subList(1, experimentItems.size()).reversed();

            var actualPage = assertDatasetExperimentPage(datasetId, experimentId, filters, apiKey, workspaceName,
                    columns, expectedDatasetItems);

            assertDatasetItemExperiments(actualPage, expectedDatasetItems, expectedExperimentItems);
        }

        Stream<Arguments> find__whenFilteringBySupportedFields__thenReturnMatchingRows() {
            return Stream.of(
                    arguments(new ExperimentsComparisonFilter("sql_tag",
                            FieldType.STRING, Operator.EQUAL, null, "sql_test")),
                    arguments(new ExperimentsComparisonFilter("sql_tag",
                            FieldType.STRING, Operator.NOT_EQUAL, null, "sql_test")),
                    arguments(new ExperimentsComparisonFilter("sql_tag",
                            FieldType.STRING, Operator.CONTAINS, null, "sql_")),
                    arguments(new ExperimentsComparisonFilter("json_node",
                            FieldType.DICTIONARY, Operator.EQUAL, "test2", "12338")),
                    arguments(new ExperimentsComparisonFilter("json_node",
                            FieldType.DICTIONARY, Operator.NOT_EQUAL, "test2", "12338")),
                    arguments(new ExperimentsComparisonFilter("sql_rate",
                            FieldType.NUMBER, Operator.LESS_THAN, null, "101")),
                    arguments(new ExperimentsComparisonFilter("sql_rate",
                            FieldType.NUMBER, Operator.GREATER_THAN, null, "99")),
                    arguments(new ExperimentsComparisonFilter("feedback_scores",
                            FieldType.FEEDBACK_SCORES_NUMBER, Operator.EQUAL, "sql_cost", "10")),
                    arguments(new ExperimentsComparisonFilter("feedback_scores",
                            FieldType.FEEDBACK_SCORES_NUMBER, Operator.NOT_EQUAL, "sql_cost", "10")),
                    arguments(new ExperimentsComparisonFilter("output",
                            FieldType.STRING, Operator.CONTAINS, null, "sql_cost")),
                    arguments(new ExperimentsComparisonFilter("expected_output",
                            FieldType.DICTIONARY, Operator.CONTAINS, "output", "sql_cost")),
                    arguments(new ExperimentsComparisonFilter("metadata",
                            FieldType.DICTIONARY, Operator.EQUAL, "sql_cost", "10")),
                    arguments(new ExperimentsComparisonFilter("meta_field",
                            FieldType.DICTIONARY, Operator.CONTAINS, "version[*]", "10")),
                    arguments(new ExperimentsComparisonFilter("releases",
                            FieldType.DICTIONARY, Operator.CONTAINS, "$[1].version", "1.1")),
                    arguments(new ExperimentsComparisonFilter("meta_field",
                            FieldType.DICTIONARY, Operator.CONTAINS, ".version[1]", "11")));

        }

        private void createExperimentItems(List<DatasetItem> items, List<Trace> traces,
                List<FeedbackScoreBatchItem> scores, UUID experimentId, List<ExperimentItem> experimentItems) {
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                var trace = traces.get(i);
                var score = scores.get(i);

                var experimentItem = ExperimentItem.builder()
                        .id(GENERATOR.generate())
                        .datasetItemId(item.id())
                        .traceId(trace.id())
                        .experimentId(experimentId)
                        .input(trace.input())
                        .output(trace.output())
                        .feedbackScores(score == null
                                ? null
                                : Stream.of(score)
                                        .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                        .toList())
                        .build();

                experimentItems.add(experimentItem);
            }
        }

        private void createScores(List<Trace> traces, String projectName, List<FeedbackScoreBatchItem> scores) {
            for (int i = 0; i < traces.size(); i++) {
                var trace = traces.get(i);

                var score = FeedbackScoreBatchItem.builder()
                        .name("sql_cost")
                        .value(BigDecimal.valueOf(i == 0 ? 10 : i))
                        .source(ScoreSource.SDK)
                        .id(trace.id())
                        .projectName(projectName)
                        .build();

                scores.add(score);
            }
        }

        private void createTraces(List<DatasetItem> items, String projectName, String workspaceName, String apiKey,
                List<Trace> traces) {
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                var trace = Trace.builder()
                        .id(GENERATOR.generate())
                        .input(item.data().get("input"))
                        .output(item.data().get("expected_output"))
                        .projectName(projectName)
                        .startTime(Instant.now())
                        .name("trace-" + i)
                        .build();

                createAndAssert(trace, workspaceName, apiKey);
                traces.add(trace);
            }
        }

        private void createDatasetItems(List<DatasetItem> items) {
            for (int i = 0; i < 5; i++) {
                if (i == 0) {
                    DatasetItem item = factory.manufacturePojo(DatasetItem.class)
                            .toBuilder()
                            .source(DatasetItemSource.SDK)
                            .data(new HashMap<>() {
                                {
                                    put("sql_tag", JsonUtils.readTree("sql_test"));
                                    put("sql_rate", JsonUtils.readTree(100));
                                    put("input", JsonUtils
                                            .getJsonNodeFromString(
                                                    JsonUtils.writeValueAsString(Map.of("input", "sql_cost"))));
                                    put("expected_output", JsonUtils
                                            .getJsonNodeFromString(
                                                    JsonUtils.writeValueAsString(Map.of("output", "sql_cost"))));
                                    put("metadata", JsonUtils
                                            .getJsonNodeFromString(
                                                    JsonUtils.writeValueAsString(Map.of("sql_cost", 10))));
                                    put("meta_field",
                                            JsonUtils.readTree(Map.of("version", new String[]{"10", "11", "12"})));
                                    put("releases", JsonUtils.readTree(
                                            List.of(
                                                    Map.of("fixes", new String[]{"10", "11", "12"}, "version", "1.0"),
                                                    Map.of("fixes", new String[]{"10", "11", "12"}, "version", "1.1"),
                                                    Map.of("fixes", new String[]{"10", "45", "30"}, "version",
                                                            "1.2"))));
                                    put("json_node", JsonUtils.readTree(Map.of("test", "1233", "test2", "12338")));
                                    put(RandomStringUtils.randomAlphanumeric(5),
                                            BigIntegerNode.valueOf(new BigInteger("18446744073709551615")));
                                    put(RandomStringUtils.randomAlphanumeric(5), DoubleNode.valueOf(132432432.79995));
                                    put(RandomStringUtils.randomAlphanumeric(5),
                                            DoubleNode.valueOf(1.1844674407370955444555));
                                    put(RandomStringUtils.randomAlphanumeric(5), IntNode.valueOf(100000000));
                                    put(RandomStringUtils.randomAlphanumeric(5), BooleanNode.valueOf(true));
                                }
                            })
                            .traceId(null)
                            .spanId(null)
                            .build();

                    items.add(item);
                } else {
                    var item = factory.manufacturePojo(DatasetItem.class);

                    items.add(item);
                }
            }
        }

        private void createScoreAndAssert(FeedbackScoreBatch feedbackScoreBatch, String apiKey, String workspaceName) {
            try (var actualResponse = client.target(getTracesPath())
                    .path("feedback-scores")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(feedbackScoreBatch))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }

        @Test
        void find__whenFilteringFeedbackScoresEmpty__thenReturnMatchingRows() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            List<DatasetItem> datasetItems = new ArrayList<>();
            createDatasetItems(datasetItems);
            var batch = DatasetItemBatch.builder()
                    .items(datasetItems)
                    .datasetId(datasetId)
                    .build();
            putAndAssert(batch, workspaceName, apiKey);

            String projectName = RandomStringUtils.randomAlphanumeric(20);
            List<Trace> traces = new ArrayList<>();
            createTraces(datasetItems, projectName, workspaceName, apiKey, traces);

            UUID experimentId = GENERATOR.generate();

            List<FeedbackScoreBatchItem> scores = new ArrayList<>();
            createScores(traces.subList(0, traces.size() - 1), projectName, scores);
            scores = scores.stream()
                    .map(score -> score.toBuilder()
                            .name(factory.manufacturePojo(String.class))
                            .build())
                    .toList();
            createScoreAndAssert(new FeedbackScoreBatch(scores), apiKey, workspaceName);

            List<ExperimentItem> experimentItems = new ArrayList<>();
            createExperimentItems(datasetItems, traces, Stream.concat(scores.stream(),
                    Stream.of((FeedbackScoreBatchItem) null)).collect(toList()),
                    experimentId, experimentItems);

            createAndAssert(
                    ExperimentItemsBatch.builder()
                            .experimentItems(Set.copyOf(experimentItems))
                            .build(),
                    apiKey,
                    workspaceName);

            Set<Column> columns = getColumns(datasetItems.stream().map(DatasetItem::data).toList());

            var isNotEmptyFilter = List.of(
                    ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES.getQueryParamField())
                            .operator(Operator.IS_NOT_EMPTY)
                            .key(scores.getFirst().name())
                            .value("")
                            .build());

            var actualPageIsNotEmpty = assertDatasetExperimentPage(datasetId, experimentId, isNotEmptyFilter, apiKey,
                    workspaceName, columns, List.of(datasetItems.getFirst()));

            assertDatasetItemExperiments(actualPageIsNotEmpty, List.of(datasetItems.getFirst()),
                    List.of(experimentItems.getFirst()));

            var isEmptyFilter = List.of(
                    ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES.getQueryParamField())
                            .operator(Operator.IS_EMPTY)
                            .key(scores.getFirst().name())
                            .value("")
                            .build());

            var actualPageIsEmpty = assertDatasetExperimentPage(datasetId, experimentId, isEmptyFilter, apiKey,
                    workspaceName, columns, datasetItems.subList(1, datasetItems.size()).reversed());

            assertDatasetItemExperiments(actualPageIsEmpty, datasetItems.subList(1, datasetItems.size()).reversed(),
                    experimentItems.subList(1, datasetItems.size()).reversed());
        }

        @ParameterizedTest
        @MethodSource
        void find__whenFilterInvalidOperatorForFieldType__thenReturn400(ExperimentsComparisonFilter filter) {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));

            var datasetId = GENERATOR.generate();
            var experimentId = GENERATOR.generate();
            var filters = List.of(filter);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("experiment_ids", JsonUtils.writeValueAsString(List.of(experimentId)))
                    .queryParam("filters", toURLEncodedQueryParam(filters))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

        }

        static Stream<Arguments> find__whenFilterInvalidOperatorForFieldType__thenReturn400() {
            return Stream.of(
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("feedback_scores")
                            .type(FieldType.FEEDBACK_SCORES_NUMBER)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("feedback_scores")
                            .type(FieldType.FEEDBACK_SCORES_NUMBER)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("feedback_scores")
                            .type(FieldType.FEEDBACK_SCORES_NUMBER)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("feedback_scores")
                            .type(FieldType.FEEDBACK_SCORES_NUMBER)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("input")
                            .type(FieldType.STRING)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("input")
                            .type(FieldType.STRING)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("input")
                            .type(FieldType.STRING)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("input")
                            .type(FieldType.STRING)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("output")
                            .type(FieldType.STRING)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("output")
                            .type(FieldType.STRING)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("output")
                            .type(FieldType.STRING)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("output")
                            .type(FieldType.STRING)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("expected_output")
                            .type(FieldType.STRING)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("expected_output")
                            .type(FieldType.STRING)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("expected_output")
                            .type(FieldType.STRING)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field("expected_output")
                            .type(FieldType.STRING)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()));
        }

        @ParameterizedTest
        @MethodSource
        void find__whenFilterInvalidFieldTypeForDynamicFields__thenReturn400(String filters) {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid filters query parameter '%s'".formatted(filters));

            var datasetId = GENERATOR.generate();
            var experimentId = GENERATOR.generate();

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .queryParam("experiment_ids", JsonUtils.writeValueAsString(List.of(experimentId)))
                    .queryParam("filters", URLEncoder.encode(filters, StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

        }

        static Stream<Arguments> find__whenFilterInvalidFieldTypeForDynamicFields__thenReturn400() {
            String template = "[{\"field\":\"%s\",\"type\":\"%s\",\"operator\":\"%s\",\"value\":\"%s\"}]";
            String field = RandomStringUtils.randomAlphanumeric(5);
            return Stream.of(
                    Arguments.of(template.formatted(
                            field,
                            FieldType.FEEDBACK_SCORES_NUMBER.getQueryParamType(),
                            Operator.EQUAL.getQueryParamOperator(),
                            RandomStringUtils.randomAlphanumeric(10))),
                    Arguments.of(template.formatted(
                            field,
                            FieldType.DATE_TIME.getQueryParamType(),
                            Operator.EQUAL.getQueryParamOperator(),
                            Instant.now().toString())),
                    Arguments.of(template.formatted(
                            field,
                            FieldType.LIST.getQueryParamType(),
                            Operator.CONTAINS.getQueryParamOperator(),
                            RandomStringUtils.randomAlphanumeric(10))));
        }
    }

    private DatasetItemPage assertDatasetExperimentPage(UUID datasetId, UUID experimentId,
            List<? extends Filter> filters,
            String apiKey, String workspaceName, Set<Column> columns, List<DatasetItem> datasetItems) {
        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path(datasetId.toString())
                .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                .queryParam("experiment_ids", JsonUtils.writeValueAsString(List.of(experimentId)))
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualPage = actualResponse.readEntity(DatasetItemPage.class);

            assertDatasetItemPage(actualPage, datasetItems, columns, 1);

            return actualPage;
        }
    }

    @DisplayName("Get experiment items output columns test")
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetExperimentItemsOutputColumnsTest {

        private List<Trace> createTrace() {
            var trace = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .metadata(JsonUtils
                            .getJsonNodeFromString(JsonUtils.writeValueAsString(Map.of("sql_cost", 10))))
                    .output(JsonUtils.readTree(Map.of(
                            "sql_tag", JsonUtils.readTree("sql_test"),
                            "sql_rate", JsonUtils.readTree(100),
                            "meta_field", JsonUtils.readTree(Map.of("version", new String[]{"10", "11", "12"})),
                            "releases", JsonUtils.readTree(
                                    List.of(
                                            Map.of("fixes", new String[]{"10", "11", "12"}, "version", "1.0"),
                                            Map.of("fixes", new String[]{"10", "11", "12"}, "version", "1.1"),
                                            Map.of("fixes", new String[]{"10", "45", "30"}, "version", "1.2"))),
                            "json_node", JsonUtils.readTree(Map.of("test", "1233", "test2", "12338")),
                            RandomStringUtils.randomAlphanumeric(5),
                            BigIntegerNode.valueOf(new BigInteger("18446744073709551615")),
                            RandomStringUtils.randomAlphanumeric(5), DoubleNode.valueOf(132432432.79995),
                            RandomStringUtils.randomAlphanumeric(5),
                            DoubleNode.valueOf(1.1844674407370955444555),
                            RandomStringUtils.randomAlphanumeric(5), IntNode.valueOf(100000000),
                            RandomStringUtils.randomAlphanumeric(5), BooleanNode.valueOf(true))))

                    .build();

            var trace2 = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .metadata(JsonUtils
                            .getJsonNodeFromString(JsonUtils.writeValueAsString(Map.of("sql_cost", 10))))
                    .output(JsonUtils.readTree(Map.of(
                            "sql_tag", JsonUtils.readTree(Set.of(
                                    RandomStringUtils.randomAlphanumeric(5),
                                    RandomStringUtils.randomAlphanumeric(5),
                                    RandomStringUtils.randomAlphanumeric(5),
                                    RandomStringUtils.randomAlphanumeric(5),
                                    RandomStringUtils.randomAlphanumeric(5))),
                            "sql_rate", JsonUtils.readTree("100"),
                            "meta_field", JsonUtils.readTree("version"),
                            "json_node", JsonUtils.readTree(Map.of("test", "1233", "test2", "12338")),
                            "releases", DoubleNode.valueOf(132432432.79995))))
                    .build();

            var trace3 = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .output(JsonUtils.readTree(RandomStringUtils.randomAlphanumeric(10)))
                    .build();

            var trace4 = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .output(null)
                    .build();

            var trace5 = factory.manufacturePojo(Trace.class)
                    .toBuilder()
                    .output(JsonUtils.readTree(Set.of("test", "1233", "test2", "12338")))
                    .build();

            return List.of(trace, trace2, trace3, trace4, trace5);
        }

        @Test
        void getExperimentItemsOutputColumns__whenFetchingByDatasetId__thenReturnColumns() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphanumeric(20);

            List<Trace> traces = createTrace().stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .build())
                    .toList();

            traces.parallelStream().forEach(trace -> createAndAssert(trace, workspaceName, apiKey));

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            var datasetItemBatch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            putAndAssert(datasetItemBatch, workspaceName, apiKey);

            // Creating 5 different experiment ids
            var experimentIds = IntStream.range(0, 5).mapToObj(__ -> GENERATOR.generate()).toList();

            List<ExperimentItem> experimentItems = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experimentIds.get(i))
                            .traceId(traces.get(i).id())
                            .datasetItemId(datasetItemBatch.items().get(i).id()).build())
                    .toList();

            var experimentItemsBatch = ExperimentItemsBatch.builder()
                    .experimentItems(Set.copyOf(experimentItems)).build();

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            Set<Column> expectedOutput = getOutputDynamicColumns(traces);

            assertColumns(datasetId, apiKey, workspaceName, null, expectedOutput);

        }

        @Test
        void getExperimentItemsOutputColumns__whenFetchingByDatasetIdAndExperimentIds__thenReturnColumns() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphanumeric(20);

            List<Trace> traces = createTrace().stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .build())
                    .toList();

            traces.parallelStream().forEach(trace -> createAndAssert(trace, workspaceName, apiKey));

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            var datasetItemBatch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .datasetId(datasetId)
                    .build();

            putAndAssert(datasetItemBatch, workspaceName, apiKey);

            // Creating 5 different experiment ids
            var experimentIds = IntStream.range(0, 2).mapToObj(__ -> GENERATOR.generate()).toList();

            List<ExperimentItem> experimentItems = IntStream.range(0, 2)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experimentIds.get(i))
                            .traceId(traces.get(i).id())
                            .datasetItemId(datasetItemBatch.items().get(i).id()).build())
                    .toList();

            var experimentItemsBatch = ExperimentItemsBatch.builder()
                    .experimentItems(Set.copyOf(experimentItems)).build();

            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            List<Trace> otherTraces = IntStream.range(0, 3)
                    .mapToObj(trace -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .build())
                    .toList();

            otherTraces.parallelStream().forEach(trace -> createAndAssert(trace, workspaceName, apiKey));

            var otherExperimentIds = IntStream.range(0, 3).mapToObj(__ -> GENERATOR.generate()).toList();

            List<DatasetItem> otherDatasetItems = datasetItemBatch.items().subList(2, 5);

            List<ExperimentItem> otherExperimentItems = IntStream.range(0, 3)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(otherExperimentIds.get(i))
                            .traceId(otherTraces.get(i).id())
                            .datasetItemId(otherDatasetItems.get(i).id()).build())
                    .toList();

            var otherExperimentItemsBatch = ExperimentItemsBatch.builder()
                    .experimentItems(Set.copyOf(otherExperimentItems)).build();

            createAndAssert(otherExperimentItemsBatch, apiKey, workspaceName);

            Set<Column> expectedOutput = getOutputDynamicColumns(traces);

            assertColumns(datasetId, apiKey, workspaceName, Set.copyOf(experimentIds), expectedOutput);
        }

        private void assertColumns(UUID datasetId, String apiKey, String workspaceName, Set<UUID> experimentIds,
                Set<Column> expectedOutput) {

            WebTarget request = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path(DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH)
                    .path("output/columns");

            if (experimentIds != null) {
                request = request.queryParam("experiment_ids", JsonUtils.writeValueAsString(experimentIds));
            }

            try (var actualResponse = request
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualColumns = actualResponse.readEntity(PageColumns.class);

                assertThat(actualColumns.columns())
                        .containsExactlyInAnyOrderElementsOf(expectedOutput);
            }
        }

    }

    private Set<Column> getOutputDynamicColumns(List<Trace> traces) {
        Map<String, List<JsonNode>> outputProperties = traces
                .stream()
                .map(Trace::output)
                .filter(Objects::nonNull)
                .filter(JsonNode::isObject)
                .map(JsonNode::fields)
                .flatMap(sourceIterator -> StreamSupport
                        .stream(Spliterators.spliteratorUnknownSize(sourceIterator, Spliterator.ORDERED), false))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));

        return outputProperties
                .entrySet()
                .stream()
                .map(entry -> {
                    Set<ColumnType> types = entry.getValue()
                            .stream()
                            .map(value -> getType(Map.entry(entry.getKey(), value)))
                            .collect(toSet());

                    return Column.builder().name(entry.getKey()).types(types).filterFieldPrefix("output").build();
                })
                .collect(toSet());
    }

    private Set<Column> getColumns(List<Map<String, JsonNode>> data) {

        HashSet<Column> columns = data
                .stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .map(entry -> Column.builder()
                        .name(entry.getKey())
                        .types(Set.of(getType(entry)))
                        .filterFieldPrefix("data")
                        .build())
                .collect(toCollection(HashSet::new));

        Map<String, Set<ColumnType>> results = columns.stream()
                .collect(groupingBy(Column::name, mapping(Column::types, flatMapping(Set::stream, toSet()))));

        return results.entrySet()
                .stream()
                .map(entry -> Column.builder().name(entry.getKey()).types(entry.getValue()).filterFieldPrefix("data")
                        .build())
                .collect(toSet());
    }

    private ColumnType getType(Map.Entry<String, JsonNode> entry) {
        return switch (entry.getValue().getNodeType()) {
            case NUMBER -> ColumnType.NUMBER;
            case STRING -> ColumnType.STRING;
            case BOOLEAN -> ColumnType.BOOLEAN;
            case ARRAY -> ColumnType.ARRAY;
            case OBJECT -> ColumnType.OBJECT;
            case NULL -> ColumnType.NULL;
            default -> ColumnType.NULL;
        };
    }

    private void assertDatasetItemExperiments(DatasetItemPage actualPage, List<DatasetItem> datasetItems,
            List<ExperimentItem> experimentItems) {

        var actualDatasetItems = actualPage.content();
        assertPage(datasetItems, actualDatasetItems);
        assertThat(actualDatasetItems.size()).isEqualTo(experimentItems.size());

        for (int i = 0; i < actualDatasetItems.size(); i++) {
            var actualExperimentItems = actualDatasetItems.get(i).experimentItems();
            assertThat(actualExperimentItems).hasSize(1);
            assertThat(actualExperimentItems.getFirst())
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS_LIST)
                    .isEqualTo(experimentItems.get(i));

            var actualFeedbackScores = assertFeedbackScoresIgnoredFieldsAndSetThemToNull(
                    actualExperimentItems.getFirst(), USER).feedbackScores();

            if (ListUtils.emptyIfNull(experimentItems.get(i).feedbackScores()).isEmpty()) {
                assertThat(actualFeedbackScores).isNull();
                continue;
            }

            assertThat(actualFeedbackScores).hasSize(1);

            assertThat(actualFeedbackScores.getFirst())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(experimentItems.get(i).feedbackScores().getFirst());
        }
    }

    private void putAndAssert(DatasetItemBatch batch, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path("items")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(batch))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private void createAndAssert(ExperimentItemsBatch request, String apiKey, String workspaceName) {
        experimentResourceClient.createExperimentItem(request.experimentItems(), apiKey, workspaceName);
    }

    private void createAndAssert(Trace trace, String workspaceName, String apiKey) {
        traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private String getTracesPath() {
        return URL_TEMPLATE_TRACES.formatted(baseURI);
    }

    private List<DatasetItem> getStreamedItems(Response response) {
        List<DatasetItem> items = new ArrayList<>();
        try (var inputStream = response.readEntity(new GenericType<ChunkedInput<String>>() {
        })) {
            while (true) {
                var item = inputStream.read();
                if (null == item) {
                    break;
                }
                items.add(JsonUtils.readValue(item, new TypeReference<DatasetItem>() {
                }));
            }
        }

        return items;
    }

    private void mockGetWorkspaceIdByName(String workspaceName, String workspaceId) {
        AuthTestUtils.mockGetWorkspaceIdByName(wireMock.server(), workspaceName, workspaceId);
    }

    private List<Dataset> prepareDatasetsListWithOnePublic() {
        var datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class).stream()
                .map(project -> project.toBuilder()
                        .visibility(PRIVATE)
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
        datasets.set(0, datasets.getFirst().toBuilder().visibility(PUBLIC).build());

        return datasets;
    }
}
