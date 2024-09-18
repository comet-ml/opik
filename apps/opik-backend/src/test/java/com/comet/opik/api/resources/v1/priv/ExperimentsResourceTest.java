package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentItemsDelete;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Trace;
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
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.auth.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.utils.ValidationUtils.SCALE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentsResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String ITEMS_PATH = "/items";
    private static final String URL_TEMPLATE_TRACES = "%s/v1/private/traces";

    private static final String API_KEY = UUID.randomUUID().toString();

    private static final String[] EXPERIMENT_IGNORED_FIELDS = new String[]{
            "id", "datasetId", "name", "feedbackScores", "traceCount", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};
    public static final String[] IGNORED_FIELDS = {"input", "output", "feedbackScores", "createdAt", "lastUpdatedAt",
            "createdBy", "lastUpdatedBy"};

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        MY_SQL_CONTAINER.start();
        CLICK_HOUSE_CONTAINER.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void beforeAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private static void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
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
        void getById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class);

            createAndAssert(expectedExperiment, okApikey, workspaceName);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path(expectedExperiment.id().toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    var actualEntity = actualResponse.readEntity(Experiment.class);
                    assertThat(actualEntity.id()).isEqualTo(expectedExperiment.id());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void create__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class);

            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(expectedExperiment))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void find__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class);

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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    var actualEntity = actualResponse.readEntity(Experiment.ExperimentPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteExperimentItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            var workspaceName = UUID.randomUUID().toString();

            var createRequest = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            createAndAssert(createRequest, okApikey, workspaceName);

            createRequest.experimentItems()
                    .forEach(item -> ExperimentsResourceTest.this.getAndAssert(item, workspaceName, okApikey));

            var ids = createRequest.experimentItems().stream().map(ExperimentItem::id).collect(Collectors.toSet());
            var deleteRequest = ExperimentItemsDelete.builder().ids(ids).build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteRequest))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void createExperimentItems__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getExperimentItemById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                    var actualEntity = actualResponse.readEntity(ExperimentItem.class);
                    assertThat(actualEntity.id()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
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
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getById__whenSessionTokenIsPresent__thenReturnProperResponse(String currentSessionToken, boolean success,
                String workspaceName) {
            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class);

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);
            createAndAssert(expectedExperiment, API_KEY, workspaceName);

            try (var actualResponse = client.target(getExperimentsPath())
                    .path(expectedExperiment.id().toString())
                    .request()
                    .cookie(SESSION_COOKIE, currentSessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class);

            mockTargetWorkspace(API_KEY, sessionToken, WORKSPACE_ID);

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(expectedExperiment))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void find__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class);

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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    var actualEntity = actualResponse.readEntity(Experiment.ExperimentPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteExperimentItems__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            mockTargetWorkspace(API_KEY, workspaceName, WORKSPACE_ID);

            var createRequest = podamFactory.manufacturePojo(ExperimentItemsBatch.class);
            createAndAssert(createRequest, API_KEY, workspaceName);
            createRequest.experimentItems()
                    .forEach(item -> ExperimentsResourceTest.this.getAndAssert(item, workspaceName, API_KEY));

            var ids = createRequest.experimentItems().stream().map(ExperimentItem::id).collect(Collectors.toSet());
            var deleteRequest = ExperimentItemsDelete.builder().ids(ids).build();

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteRequest))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void createExperimentItems__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

            try (var actualResponse = client.target(getExperimentItemsPath())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getExperimentItemById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    var actualEntity = actualResponse.readEntity(ExperimentItem.class);
                    assertThat(actualEntity.id()).isEqualTo(expectedExperiment.id());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindExperiments {

        @Test
        void findByDatasetId() {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasetName = RandomStringUtils.randomAlphanumeric(10);
            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class)
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .datasetName(datasetName)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> ExperimentsResourceTest.this.createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(podamFactory.manufacturePojo(Experiment.class));
            unexpectedExperiments.forEach(expectedExperiment -> ExperimentsResourceTest.this
                    .createAndAssert(expectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                    .datasetId();
            String name = null;
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, datasetId, name, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey);
            findAndAssert(workspaceName, 2, pageSize, datasetId, name, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey);
        }

        Stream<Arguments> findByName() {
            var exactName = RandomStringUtils.randomAlphanumeric(10);
            var exactNameIgnoreCase = RandomStringUtils.randomAlphanumeric(10);
            var partialName = RandomStringUtils.randomAlphanumeric(10);
            var partialNameIgnoreCase = RandomStringUtils.randomAlphanumeric(10);
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
                    .map(experiment -> experiment.toBuilder()
                            .name(name)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> ExperimentsResourceTest.this.createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(podamFactory.manufacturePojo(Experiment.class));
            unexpectedExperiments.forEach(expectedExperiment -> ExperimentsResourceTest.this
                    .createAndAssert(expectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            UUID datasetId = null;
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, datasetId, nameQueryParam, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey);
            findAndAssert(workspaceName, 2, pageSize, datasetId, nameQueryParam, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey);
        }

        @Test
        void findByDatasetIdAndName() {

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var datasetName = RandomStringUtils.randomAlphanumeric(10);
            var name = RandomStringUtils.randomAlphanumeric(10);

            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class)
                    .stream()
                    .map(experiment -> experiment.toBuilder()
                            .datasetName(datasetName)
                            .name(name)
                            .metadata(null)
                            .build())
                    .toList();
            experiments.forEach(expectedExperiment -> ExperimentsResourceTest.this.createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var unexpectedExperiments = List.of(podamFactory.manufacturePojo(Experiment.class));
            unexpectedExperiments.forEach(expectedExperiment -> ExperimentsResourceTest.this
                    .createAndAssert(expectedExperiment, apiKey, workspaceName));

            var pageSize = experiments.size() - 2;
            var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                    .datasetId();
            var expectedExperiments1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
            var expectedExperiments2 = experiments.subList(0, pageSize - 1).reversed();
            var expectedTotal = experiments.size();

            findAndAssert(workspaceName, 1, pageSize, datasetId, name, expectedExperiments1, expectedTotal,
                    unexpectedExperiments, apiKey);
            findAndAssert(workspaceName, 2, pageSize, datasetId, name, expectedExperiments2, expectedTotal,
                    unexpectedExperiments, apiKey);
        }

        @Test
        void findAll() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var experiments = PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class);

            experiments.forEach(expectedExperiment -> ExperimentsResourceTest.this.createAndAssert(expectedExperiment,
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
                var actualPage = actualResponse.readEntity(Experiment.ExperimentPage.class);
                var actualExperiments = actualPage.content();

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

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
                    .map(experiment -> experiment.toBuilder()
                            .feedbackScores(null)
                            .build())
                    .toList();

            experiments.forEach(expectedExperiment -> ExperimentsResourceTest.this.createAndAssert(expectedExperiment,
                    apiKey, workspaceName));

            var noScoreExperiment = podamFactory.manufacturePojo(Experiment.class);
            createAndAssert(noScoreExperiment, apiKey, workspaceName);

            var noItemExperiment = podamFactory.manufacturePojo(Experiment.class);
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
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.values().size();
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

            var page = 1;
            var pageSize = experiments.size() + 2; // +2 for the noScoreExperiment and noItemExperiment

            try (var actualResponse = client.target(getExperimentsPath())
                    .queryParam("page", page)
                    .queryParam("size", pageSize)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                var actualPage = actualResponse.readEntity(Experiment.ExperimentPage.class);
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
        void findAllAndTraceDeleted() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
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
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.values().size();

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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                var actualPage = actualResponse.readEntity(Experiment.ExperimentPage.class);
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
    }

    private void deleteTrace(UUID id, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(getTracesPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
        }
    }

    private @NotNull Map<UUID, Map<String, BigDecimal>> getExpectedScoresPerExperiment(List<Experiment> experiments,
            List<ExperimentItem> experimentItems) {
        return experiments.stream()
                .map(experiment -> Map.entry(experiment.id(), experimentItems
                        .stream()
                        .filter(item -> item.experimentId().equals(experiment.id()))
                        .map(ExperimentItem::feedbackScores)
                        .flatMap(Collection::stream)
                        .collect(Collectors.groupingBy(
                                FeedbackScore::name,
                                Collectors.mapping(FeedbackScore::value, Collectors.toList())))
                        .entrySet()
                        .stream()
                        .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void findAndAssert(
            String workspaceName,
            int page,
            int pageSize,
            UUID datasetId,
            String name,
            List<Experiment> expectedExperiments,
            long expectedTotal,
            List<Experiment> unexpectedExperiments, String apiKey) {
        try (var actualResponse = client.target(getExperimentsPath())
                .queryParam("page", page)
                .queryParam("size", pageSize)
                .queryParam("datasetId", datasetId)
                .queryParam("name", name)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            var actualPage = actualResponse.readEntity(Experiment.ExperimentPage.class);
            var actualExperiments = actualPage.content();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            assertThat(actualPage.page()).isEqualTo(page);
            assertThat(actualPage.size()).isEqualTo(expectedExperiments.size());
            assertThat(actualPage.total()).isEqualTo(expectedTotal);

            assertThat(actualExperiments.size()).isEqualTo(expectedExperiments.size());

            assertThat(actualExperiments)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedExperiments);

            assertIgnoredFields(actualExperiments, expectedExperiments, datasetId);

            assertThat(actualExperiments)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedExperiments);
        }
    }

    private void createScoreAndAssert(FeedbackScoreBatch feedbackScoreBatch) {
        createScoreAndAssert(feedbackScoreBatch, API_KEY, TEST_WORKSPACE);
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

    private int customComparator(BigDecimal v1, BigDecimal v2) {
        //TODO This is a workaround to compare BigDecimals and clickhouse floats seems to have some precision issues
        // Compare the integer parts directly
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
            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
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
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::id));

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
        void createAndGetFeedbackAvg() {
            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
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
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::id));

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
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void createWithoutOptionalFieldsAndGet(String name) {
            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(null)
                    .name(name)
                    .metadata(null)
                    .build();
            var expectedId = createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            getAndAssert(expectedId, expectedExperiment, TEST_WORKSPACE, API_KEY);
        }

        @Test
        void createConflict() {
            var experiment = podamFactory.manufacturePojo(Experiment.class);
            var expectedError = new ErrorMessage(
                    409, "Already exists experiment with id '%s'".formatted(experiment.id()));
            createAndAssert(experiment, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(getExperimentsPath())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(experiment))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);

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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);

                var actualError = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @Test
        void getNotFound() {
            UUID id = GENERATOR.generate();
            var expectedError = new ErrorMessage(404, "Not found experiment with id '%s'".formatted(id));
            try (var actualResponse = client.target(getExperimentsPath())
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);

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

            var expectedExperiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
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
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::id));

            // When storing the scores in batch, adding some more unrelated random ones
            var feedbackScoreBatch = podamFactory.manufacturePojo(FeedbackScoreBatch.class);
            feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                    .scores(Stream.concat(
                            feedbackScoreBatch.scores().stream(),
                            traceIdToScoresMap.values().stream().flatMap(List::stream))
                            .toList())
                    .build();

            createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

            int totalNumberOfScores = traceIdToScoresMap.values().size();

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
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

            Experiment experiment = getAndAssert(expectedExperiment.id(), expectedExperiment, workspaceName, apiKey);

            assertThat(experiment.traceCount()).isEqualTo(traces.size()); // decide if we should count deleted traces

            Map<String, BigDecimal> actual = getScoresMap(experiment);

            assertThat(actual)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withComparatorForType(ExperimentsResourceTest.this::customComparator, BigDecimal.class)
                            .build())
                    .isEqualTo(expectedScores);
        }
    }

    private ExperimentItemsBatch addRandomExperiments(List<ExperimentItem> experimentItems) {

        // When storing the experiment items in batch, adding some more unrelated random ones
        var experimentItemsBatch = podamFactory.manufacturePojo(ExperimentItemsBatch.class);
        experimentItemsBatch = experimentItemsBatch.toBuilder()
                .experimentItems(Stream.concat(
                        experimentItemsBatch.experimentItems().stream(),
                        experimentItems.stream())
                        .collect(Collectors.toUnmodifiableSet()))
                .build();
        return experimentItemsBatch;
    }

    private Map<String, BigDecimal> getScoresMap(Experiment experiment) {
        List<FeedbackScoreAverage> feedbackScores = experiment.feedbackScores();

        if (feedbackScores != null) {
            return feedbackScores
                    .stream()
                    .collect(Collectors.toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value));
        }

        return null;
    }

    private Map<String, BigDecimal> getExpectedScores(Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap) {
        return traceIdToScoresMap
                .values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(
                        FeedbackScoreBatchItem::name,
                        Collectors.mapping(FeedbackScoreBatchItem::value, Collectors.toList())))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            var actualHeaderString = actualResponse.getHeaderString("Location");
            assertThat(actualHeaderString).isEqualTo(getTracesPath() + "/" + trace.id());
        }
    }

    private UUID createAndAssert(Experiment expectedExperiment, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(getExperimentsPath())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(expectedExperiment))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            var actualId = TestUtils.getIdFromLocation(actualResponse.getLocation());

            assertThat(actualResponse.hasEntity()).isFalse();

            if (expectedExperiment.id() != null) {
                assertThat(actualId).isEqualTo(expectedExperiment.id());
            } else {
                assertThat(actualId).isNotNull();
            }

            return actualId;
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

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            assertThat(actualExperiment)
                    .usingRecursiveComparison()
                    .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .isEqualTo(expectedExperiment);

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

        assertThat(actualExperiment.createdAt()).isAfter(expectedExperiment.createdAt());
        assertThat(actualExperiment.lastUpdatedAt()).isAfter(expectedExperiment.lastUpdatedAt());
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
    class CreateExperimentsItems {

        @Test
        void createAndGet() {
            var request = podamFactory.manufacturePojo(ExperimentItemsBatch.class);

            createAndAssert(request, API_KEY, TEST_WORKSPACE);

            request.experimentItems()
                    .forEach(item -> ExperimentsResourceTest.this.getAndAssert(item, TEST_WORKSPACE, API_KEY));
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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
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

            UUID experimentId = createAndAssert(podamFactory.manufacturePojo(Experiment.class), API_KEY,
                    TEST_WORKSPACE);

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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).getMessage())
                        .isEqualTo("Upserting experiment item with 'experiment_id' not belonging to the workspace");
            }
        }

        UUID createDatasetItem(String workspaceName, String apiKey) {
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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
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

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(400);

                var actualError = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);

                assertThat(actualError).isEqualTo(expectedError);
            }
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteExperimentsItems {

        @Test
        void delete() {
            var createRequest = podamFactory.manufacturePojo(ExperimentItemsBatch.class).toBuilder()
                    .build();
            createAndAssert(createRequest, API_KEY, TEST_WORKSPACE);
            createRequest.experimentItems()
                    .forEach(item -> ExperimentsResourceTest.this.getAndAssert(item, TEST_WORKSPACE, API_KEY));

            var ids = createRequest.experimentItems().stream().map(ExperimentItem::id).collect(Collectors.toSet());
            var deleteRequest = ExperimentItemsDelete.builder().ids(ids).build();
            try (var actualResponse = client.target(getExperimentItemsPath())
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(deleteRequest))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            ids.forEach(id -> ExperimentsResourceTest.this.getAndAssertNotFound(id, API_KEY, TEST_WORKSPACE));
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

    private void getAndAssert(ExperimentItem expectedExperimentItem, String workspaceName, String apiKey) {
        var id = expectedExperimentItem.id();
        try (var actualResponse = client.target(getExperimentItemsPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualExperimentItem = actualResponse.readEntity(ExperimentItem.class);

            assertThat(actualExperimentItem)
                    .usingRecursiveComparison()
                    .ignoringFields(IGNORED_FIELDS)
                    .isEqualTo(expectedExperimentItem);

            assertThat(actualExperimentItem.input()).isNull();
            assertThat(actualExperimentItem.output()).isNull();
            assertThat(actualExperimentItem.feedbackScores()).isNull();
            assertThat(actualExperimentItem.createdAt()).isAfter(expectedExperimentItem.createdAt());
            assertThat(actualExperimentItem.lastUpdatedAt()).isAfter(expectedExperimentItem.lastUpdatedAt());
            assertThat(actualExperimentItem.createdBy()).isEqualTo(USER);
            assertThat(actualExperimentItem.lastUpdatedBy()).isEqualTo(USER);
        }
    }

    private void getAndAssertNotFound(UUID id, String apiKey, String workspaceName) {
        var expectedError = new ErrorMessage(404, "Not found experiment item with id '%s'".formatted(id));
        try (var actualResponse = client.target(getExperimentItemsPath())
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);

            var actualError = actualResponse.readEntity(ErrorMessage.class);

            assertThat(actualError).isEqualTo(expectedError);
        }
    }

    private String getExperimentsPath() {
        return URL_TEMPLATE.formatted(baseURI);
    }

    private String getExperimentItemsPath() {
        return URL_TEMPLATE.formatted(baseURI) + ITEMS_PATH;
    }
}
