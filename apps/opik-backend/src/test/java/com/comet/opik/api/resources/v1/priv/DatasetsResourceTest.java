package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemInputValue;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.ExperimentsComparisonField;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.glassfish.jersey.client.ChunkedInput;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.auth.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Resource Test")
class DatasetsResourceTest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/datasets";
    private static final String EXPERIMENT_RESOURCE_URI = "%s/v1/private/experiments";
    private static final String DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_PATH = "/items/experiments/items";

    private static final String URL_TEMPLATE_EXPERIMENT_ITEMS = "%s/v1/private/experiments/items";
    private static final String URL_TEMPLATE_TRACES = "%s/v1/private/traces";

    public static final String[] IGNORED_FIELDS_LIST = {"feedbackScores", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};
    public static final String[] IGNORED_FIELDS_DATA_ITEM = {"createdAt", "lastUpdatedAt", "experimentItems",
            "createdBy", "lastUpdatedBy"};
    public static final String[] DATASET_IGNORED_FIELDS = {"id", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "experimentCount", "mostRecentExperimentAt", "experimentCount"};

    public static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockRuntime wireMock;

    static {
        MYSQL.start();
        CLICKHOUSE.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory,
                wireMock.runtimeInfo(),
                REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private static void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
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

            var id = TestUtils.getIdFromLocation(actualResponse.getLocation());

            assertThat(id).isNotNull();
            assertThat(id.version()).isEqualTo(7);

            return id;
        }
    }

    private Dataset getAndAssertEquals(UUID id, Dataset expected, String workspaceName, String apiKey) {
        var actualResponse = client.target("%s/v1/private/datasets".formatted(baseURI))
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
        @DisplayName("create dataset: when api key is present, then return proper response")
        void createDataset__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {
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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get dataset by id: when api key is present, then return proper response")
        void getDatasetById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

            Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
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
                    .get()) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(Dataset.class);

                    assertThat(actualEntity.id()).isEqualTo(id);
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
        @DisplayName("Get dataset by name: when api key is present, then return proper response")
        void getDatasetByName__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(Dataset.class);

                    assertThat(actualEntity.id()).isEqualTo(id);
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
        @DisplayName("Get datasets: when api key is present, then return proper response")
        void getDatasets__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            List<Dataset> expected = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class).stream()
                    .map(dataset -> dataset.toBuilder().build())
                    .toList();

            expected.forEach(dataset -> createAndAssert(dataset, okApikey, workspaceName));

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

                    assertThat(actualEntity.content()).hasSize(expected.size());
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
        @DisplayName("Update dataset: when api key is present, then return proper response")
        void updateDataset__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Delete dataset: when api key is present, then return proper response")
        void deleteDataset__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Create dataset items: when api key is present, then return proper response")
        void createDatasetItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get dataset items by dataset id: when api key is present, then return proper response")
        void getDatasetItemsByDatasetId__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean shouldSucceed) {

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
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

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                    assertThat(actualEntity.content().size()).isEqualTo(items.size());
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
        @DisplayName("Stream dataset items: when api key is present, then return proper response")
        void streamDatasetItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {
            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
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

            var request = new DatasetItemStreamRequest(name, null, null);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .post(Entity.json(request))) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    List<DatasetItem> actualItems = getStreamedItems(actualResponse);
                    assertThat(actualItems.size()).isEqualTo(items.size());

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
        @DisplayName("Delete dataset items: when api key is present, then return proper response")
        void deleteDatasetItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {

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
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get dataset item by id: when api key is present, then return proper response")
        void getDatasetItemById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean shouldSucceed) {
            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
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
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(DatasetItem.class);

                    assertThat(actualEntity.id()).isEqualTo(item.id());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
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
                            .willReturn(WireMock.unauthorized()));
        }

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(sessionToken, true, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, false, UUID.randomUUID().toString()));
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
        @MethodSource("credentials")
        @DisplayName("Get dataset by id: when session token is present, then return proper response")
        void getDatasetById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(Dataset.class);

                    assertThat(actualEntity.id()).isEqualTo(id);
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
        @DisplayName("Get dataset by name: when session token is present, then return proper response")
        void getDatasetByName__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .build();

            var id = createAndAssert(dataset);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("retrieve")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.json(new DatasetIdentifier(dataset.name())))) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(Dataset.class);

                    assertThat(actualEntity.id()).isEqualTo(id);
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
        @DisplayName("Get datasets: when session token is present, then return proper response")
        void getDatasets__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            List<Dataset> expected = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            expected.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(Dataset.DatasetPage.class);

                    assertThat(actualEntity.content()).hasSize(expected.size());
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
        @MethodSource("credentials")
        @DisplayName("Get dataset items by dataset id: when session token is present, then return proper response")
        void getDatasetItemsByDatasetId__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
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

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                    assertThat(actualEntity.content().size()).isEqualTo(items.size());
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
        @DisplayName("Stream dataset items: when session token is present, then return proper response")
        void getDatasetItemsStream__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
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

            var request = new DatasetItemStreamRequest(name, null, null);

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("items")
                    .path("stream")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .post(Entity.json(request))) {

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    List<DatasetItem> actualItems = getStreamedItems(actualResponse);
                    assertThat(actualItems.size()).isEqualTo(items.size());

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
        @MethodSource("credentials")
        @DisplayName("Get dataset item by id: when session token is present, then return proper response")
        void getDatasetItemById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean shouldSucceed, String workspaceName) {

            String name = UUID.randomUUID().toString();

            var datasetId = createAndAssert(factory.manufacturePojo(Dataset.class).toBuilder()
                    .id(null)
                    .name(name)
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

                if (shouldSucceed) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    var actualEntity = actualResponse.readEntity(DatasetItem.class);

                    assertThat(actualEntity.id()).isEqualTo(item.id());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
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
        @DisplayName("when dataset has experiments linked to it, then return dataset with experiment summary")
        void getDatasetById__whenDatasetHasExperimentsLinkedToIt__thenReturnDatasetWithExperimentSummary() {

            var dataset = factory.manufacturePojo(Dataset.class);

            createAndAssert(dataset);

            var experiment1 = factory.manufacturePojo(Experiment.class).toBuilder()
                    .datasetName(dataset.name())
                    .build();

            var experiment2 = factory.manufacturePojo(Experiment.class).toBuilder()
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

    private void createAndAssert(Experiment experiment1, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(EXPERIMENT_RESOURCE_URI.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(experiment1))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
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

            var experiments = PodamFactoryUtils.manufacturePojoList(factory, Experiment.class).stream()
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
                assertThat(dataset.mostRecentExperimentAt()).isAfter(beforeCreateExperimentItems);
            }
        }
    }

    private void findAndAssertPage(Dataset.DatasetPage actualEntity, int expected, int total, int page,
            List<Dataset> expectedContent) {
        assertThat(actualEntity.size()).isEqualTo(expected);
        assertThat(actualEntity.content()).hasSize(expected);
        assertThat(actualEntity.page()).isEqualTo(page);
        assertThat(actualEntity.total()).isGreaterThanOrEqualTo(total);

        assertThat(actualEntity.content())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("id", "createdAt", "lastUpdatedAt",
                        "createdBy", "lastUpdatedBy", "experimentCount", "mostRecentExperimentAt",
                        "workspaceName")
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
        void deleteDataset__whenDeletingByDatasetNameAndDatasetDoesNotExist__thenReturnNoContent() {
            var dataset = factory.manufacturePojo(Dataset.class);

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
                    .put(Entity.entity(batch, MediaType.APPLICATION_JSON_TYPE))) {

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
                                    .input(null)
                                    .inputData(null)
                                    .build()))
                            .build(),
                            "items[0].input must provide either input or input_data"),
                    arguments(factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                                    .items(List.of(factory.manufacturePojo(DatasetItem.class).toBuilder()
                                            .input(null)
                                            .inputData(Map.of())
                                            .build()))
                                    .build(),
                            "items[0].input must provide either input or input_data"),
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

        @Test
        @DisplayName("when input data is null, the accept the request")
        void create__whenInputDataIsNull__thenAcceptTheRequest() {
            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .inputData(null)
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            getItemAndAssert(item, TEST_WORKSPACE, API_KEY);
        }

        @Test
        @DisplayName("when input is null but input data is present, the accept the request")
        void create__whenInputIsNullButInputDataIsPresent__thenAcceptTheRequest() {
            var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                    .input(null)
                    .build();

            var batch = factory.manufacturePojo(DatasetItemBatch.class).toBuilder()
                    .items(List.of(item))
                    .datasetId(null)
                    .build();

            putAndAssert(batch, TEST_WORKSPACE, API_KEY);

            getItemAndAssert(item, TEST_WORKSPACE, API_KEY);
        }

    }

    private UUID createTrace(Trace trace, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(TracesResourceTest.URL_TEMPLATE.formatted(baseURI)).request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(trace, MediaType.APPLICATION_JSON_TYPE))) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            return TestUtils.getIdFromLocation(actualResponse.getLocation());
        }
    }

    private UUID createSpan(Span span, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(SpansResourceTest.URL_TEMPLATE.formatted(baseURI)).request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(span, MediaType.APPLICATION_JSON_TYPE))) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            return TestUtils.getIdFromLocation(actualResponse.getLocation());
        }
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
                            .metadata(null)
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

        Map<String, DatasetItemInputValue<?>> inputData = Optional.ofNullable(expectedDatasetItem.inputData())
                .orElse(Map.of());

        expectedDatasetItem = mergeInputMap(expectedDatasetItem, inputData);

        assertThat(actualEntity.id()).isEqualTo(expectedDatasetItem.id());
        assertThat(actualEntity).usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_DATA_ITEM)
                .isEqualTo(expectedDatasetItem);

        assertThat(actualEntity.createdAt()).isInThePast();
        assertThat(actualEntity.lastUpdatedAt()).isInThePast();
    }

    private static DatasetItem mergeInputMap(DatasetItem expectedDatasetItem,
            Map<String, DatasetItemInputValue<?>> inputData) {

        Map<String, DatasetItemInputValue<?>> newMap = new HashMap<>();

        if (expectedDatasetItem.expectedOutput() != null) {
            newMap.put("expected_output", new DatasetItemInputValue.JsonValue(expectedDatasetItem.expectedOutput()));
        }

        if (expectedDatasetItem.input() != null) {
            newMap.put("input", new DatasetItemInputValue.JsonValue(expectedDatasetItem.input()));
        }

        Map<String, DatasetItemInputValue<?>> mergedMap = Stream
                .concat(inputData.entrySet().stream(), newMap.entrySet().stream())
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> v2 // In case of conflict, use the value from map2
                ));

        expectedDatasetItem = expectedDatasetItem.toBuilder()
                .inputData(mergedMap)
                .build();

        return expectedDatasetItem;
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

            Set<String> columns = new HashSet<>(batch.items()
                    .stream()
                    .flatMap(item -> item.inputData().keySet().stream())
                    .collect(toSet()));

            columns.add("input");
            columns.add("expected_output");

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

                assertThat(actualEntity.size()).isEqualTo(items.size());
                assertThat(actualEntity.content()).hasSize(items.size());
                assertThat(actualEntity.page()).isEqualTo(1);
                assertThat(actualEntity.total()).isEqualTo(items.size());
                assertThat(actualEntity.columns()).isEqualTo(columns);

                assertPage(items.reversed(), actualEntity.content());
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

            Set<String> columns = new HashSet<>(items
                    .stream()
                    .flatMap(item -> item.inputData().keySet().stream())
                    .collect(toSet()));

            columns.add("input");
            columns.add("expected_output");

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

                assertThat(actualEntity.size()).isEqualTo(1);
                assertThat(actualEntity.content()).hasSize(1);
                assertThat(actualEntity.page()).isEqualTo(1);
                assertThat(actualEntity.total()).isEqualTo(items.size());
                assertThat(actualEntity.columns()).isEqualTo(columns);

                assertPage(List.of(items.reversed().getFirst()), actualEntity.content());
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
                    .map(item -> item.toBuilder().input(factory.manufacturePojo(JsonNode.class)).build())
                    .toList();

            var updatedBatch = batch.toBuilder()
                    .items(updatedItems)
                    .build();

            putAndAssert(updatedBatch, TEST_WORKSPACE, API_KEY);

            Set<String> columns = new HashSet<>(updatedBatch.items()
                    .stream()
                    .flatMap(item -> item.inputData().keySet().stream())
                    .collect(toSet()));

            columns.add("input");
            columns.add("expected_output");

            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path(datasetId.toString())
                    .path("items")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                var actualEntity = actualResponse.readEntity(DatasetItemPage.class);

                assertThat(actualEntity.size()).isEqualTo(updatedItems.size());
                assertThat(actualEntity.content()).hasSize(updatedItems.size());
                assertThat(actualEntity.page()).isEqualTo(1);
                assertThat(actualEntity.total()).isEqualTo(updatedItems.size());
                assertThat(actualEntity.columns()).isEqualTo(columns);

                assertPage(updatedItems.reversed(), actualEntity.content());
            }
        }

    }

    private static void assertPage(List<DatasetItem> items, List<DatasetItem> actualItems) {

        List<String> ignoredFields = new ArrayList<>(Arrays.asList(IGNORED_FIELDS_DATA_ITEM));
        ignoredFields.add("inputData");

        assertThat(actualItems)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ignoredFields.toArray(String[]::new))
                .isEqualTo(items);

        for (int i = 0; i < actualItems.size(); i++) {
            var actualDatasetItem = actualItems.get(i);
            var expectedDatasetItem = items.get(i);

            Map<String, DatasetItemInputValue<?>> inputData = Optional.ofNullable(expectedDatasetItem.inputData())
                    .orElse(Map.of());

            expectedDatasetItem = mergeInputMap(expectedDatasetItem, inputData);

            assertThat(actualDatasetItem.inputData()).isEqualTo(expectedDatasetItem.inputData());
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
                                    .feedbackScores(traceIdToScoresMap.get(traces.get(i / 5).id()).stream()
                                            .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                            .toList())
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
                                    .feedbackScores(null)
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
                                    .build()))
                    .toList());

            // Dataset item 4 covers the case of not matching experiment items.

            // When storing the experiment items in batch, adding some more unrelated random ones
            var experimentItemsBatch = factory.manufacturePojo(ExperimentItemsBatch.class);
            experimentItemsBatch = experimentItemsBatch.toBuilder()
                    .experimentItems(Stream.concat(experimentItemsBatch.experimentItems().stream(),
                            datasetItemIdToExperimentItemMap.values().stream().flatMap(Collection::stream))
                            .collect(toUnmodifiableSet()))
                    .build();
            createAndAssert(experimentItemsBatch, apiKey, workspaceName);

            Set<String> columns = expectedDatasetItems
                    .stream()
                    .map(DatasetItem::inputData)
                    .map(Map::keySet)
                    .flatMap(Set::stream)
                    .collect(toSet());

            columns.add("input");
            columns.add("expected_output");

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
                            .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_LIST)
                            .containsExactlyElementsOf(expectedExperimentItems);

                    for (var j = 0; j < actualDatasetItem.experimentItems().size(); j++) {
                        var actualExperimentItem = actualDatasetItem.experimentItems().get(j);
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
                    }

                    assertThat(actualDatasetItem.createdAt()).isAfter(expectedDatasetItem.createdAt());
                    assertThat(actualDatasetItem.lastUpdatedAt()).isAfter(expectedDatasetItem.lastUpdatedAt());
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"[wrong_payload]", "[0191377d-06ee-7026-8f63-cc5309d1f54b]"})
        void findInvalidExperimentIds(String experimentIds) {
            var expectedErrorMessage = new io.dropwizard.jersey.errors.ErrorMessage(
                    400, "Invalid query param experiment ids '%s'".formatted(experimentIds));

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

        @ParameterizedTest
        @MethodSource
        void find__whenFilteringBySupportedFields__thenReturnMatchingRows(Filter filter) {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, workspaceName);

            List<DatasetItem> items = new ArrayList<>();
            createDatasetItems(items);
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

            Set<String> columns = new HashSet<>(items.getFirst().inputData().keySet());
            columns.add("input");
            columns.add("expected_output");

            List<Filter> filters = List.of(filter);

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

                assertThat(actualPage.size()).isEqualTo(1);
                assertThat(actualPage.total()).isEqualTo(1);
                assertThat(actualPage.page()).isEqualTo(1);
                assertThat(actualPage.content()).hasSize(1);
                assertThat(actualPage.columns()).isEqualTo(columns);

                assertDatasetItemPage(actualPage, items, experimentItems);
            }
        }

        Stream<Arguments> find__whenFilteringBySupportedFields__thenReturnMatchingRows() {
            return Stream.of(
                    arguments(new ExperimentsComparisonFilter(ExperimentsComparisonField.FEEDBACK_SCORES,
                            Operator.EQUAL, "sql_cost", "10")),
                    arguments(new ExperimentsComparisonFilter(ExperimentsComparisonField.INPUT, Operator.CONTAINS, null,
                            "sql_cost")),
                    arguments(new ExperimentsComparisonFilter(ExperimentsComparisonField.OUTPUT, Operator.CONTAINS,
                            null, "sql_cost")),
                    arguments(new ExperimentsComparisonFilter(ExperimentsComparisonField.EXPECTED_OUTPUT,
                            Operator.CONTAINS, null, "sql_cost")),
                    arguments(new ExperimentsComparisonFilter(ExperimentsComparisonField.METADATA, Operator.EQUAL,
                            "sql_cost", "10")));
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
                        .feedbackScores(Stream.of(score)
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
                        .input(item.input())
                        .output(item.expectedOutput())
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
                            .input(JsonUtils
                                    .getJsonNodeFromString(JsonUtils.writeValueAsString(Map.of("input", "sql_cost"))))
                            .expectedOutput(JsonUtils
                                    .getJsonNodeFromString(JsonUtils.writeValueAsString(Map.of("output", "sql_cost"))))
                            .metadata(JsonUtils
                                    .getJsonNodeFromString(JsonUtils.writeValueAsString(Map.of("sql_cost", 10))))
                            .source(DatasetItemSource.SDK)
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

        private String toURLEncodedQueryParam(List<? extends Filter> filters) {
            return CollectionUtils.isEmpty(filters)
                    ? null
                    : URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
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
                            .field(ExperimentsComparisonField.FEEDBACK_SCORES)
                            .operator(Operator.CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.FEEDBACK_SCORES)
                            .operator(Operator.NOT_CONTAINS)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.FEEDBACK_SCORES)
                            .operator(Operator.STARTS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.FEEDBACK_SCORES)
                            .operator(Operator.ENDS_WITH)
                            .value(RandomStringUtils.randomAlphanumeric(10))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.INPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.INPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.INPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.INPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.OUTPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.OUTPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.OUTPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.OUTPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.EXPECTED_OUTPUT)
                            .operator(Operator.GREATER_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.EXPECTED_OUTPUT)
                            .operator(Operator.LESS_THAN)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.EXPECTED_OUTPUT)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()),
                    Arguments.of(ExperimentsComparisonFilter.builder()
                            .field(ExperimentsComparisonField.EXPECTED_OUTPUT)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(RandomStringUtils.randomNumeric(3))
                            .build()));
        }
    }

    private void assertDatasetItemPage(DatasetItemPage actualPage, List<DatasetItem> items,
            List<ExperimentItem> experimentItems) {

        assertPage(List.of(items.getFirst()), List.of(actualPage.content().getFirst()));

        var actualExperimentItems = actualPage.content().getFirst().experimentItems();
        assertThat(actualExperimentItems).hasSize(1);
        assertThat(actualExperimentItems.getFirst())
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_LIST)
                .isEqualTo(experimentItems.getFirst());

        var actualFeedbackScores = actualExperimentItems.getFirst().feedbackScores();
        assertThat(actualFeedbackScores).hasSize(1);

        assertThat(actualFeedbackScores.getFirst())
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(experimentItems.getFirst().feedbackScores().getFirst());
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
        try (var actualResponse = client.target(getExperimentItemsPath())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private void createAndAssert(Trace trace, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(getTracesPath())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            var actualHeaderString = actualResponse.getHeaderString("Location");
            assertThat(actualHeaderString).isEqualTo(getTracesPath() + "/" + trace.id());
        }
    }

    private String getExperimentItemsPath() {
        return URL_TEMPLATE_EXPERIMENT_ITEMS.formatted(baseURI);
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

}
