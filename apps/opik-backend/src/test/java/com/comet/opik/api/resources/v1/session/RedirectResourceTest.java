package com.comet.opik.api.resources.v1.session;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.ReactServiceErrorResponse;
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
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.RedirectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Redirect Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedirectResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/session/redirect";

    private static final String USER = UUID.randomUUID().toString();
    private static final String NON_EXISTING_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String NON_EXISTING_WORKSPACE_NAME = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = UUID.randomUUID().toString();
    private static final String TRACE_REDIRECT_URL = "%s/%s/projects/%s/traces?tab=logs&logsType=traces&trace=%s";
    private static final String DATASET_REDIRECT_URL = "%s/%s/datasets/%s/items";
    private static final String EXPERIMENT_REDIRECT_URL = "%s/%s/experiments/%s/compare?experiments=%s";
    private static final String OPTIMIZATION_REDIRECT_URL = "%s/%s/optimizations/%s/compare?optimizations=%s";
    public static final String NO_SUCH_WORKSPACE = "No such workspace: %s";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private RedirectResourceClient redirectResourceClient;
    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.redirectResourceClient = new RedirectResourceClient(client);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);

        ClientSupportUtils.config(client);
    }

    @BeforeEach
    void setUp() {
        mockTargetWorkspace(API_KEY, WORKSPACE_NAME, WORKSPACE_ID);
        mockTargetWorkspace(API_KEY, NON_EXISTING_WORKSPACE_NAME, NON_EXISTING_WORKSPACE_ID);

        wireMock.server().stubFor(
                get(urlPathEqualTo("/api/workspaces/workspace-name"))
                        .withQueryParam("id", equalTo(WORKSPACE_ID))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(WORKSPACE_NAME)));

        wireMock.server().stubFor(
                get(urlPathEqualTo("/api/workspaces/workspace-name"))
                        .withQueryParam("id", equalTo(NON_EXISTING_WORKSPACE_ID))
                        .willReturn(WireMock.badRequest().withHeader("Content-Type", "application/json")
                                .withJsonBody(JsonUtils.readTree(
                                        new ReactServiceErrorResponse(
                                                NO_SUCH_WORKSPACE.formatted(NON_EXISTING_WORKSPACE_ID),
                                                400)))));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Session Token Cookie Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SessionTokenCookie {

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(UUID.randomUUID().toString(), 303),
                    arguments(null, 403));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void create__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, int expectedStatus) {
            var trace = factory.manufacturePojo(Trace.class);
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
            redirectResourceClient.projectsRedirect(trace.id(), sessionToken, WORKSPACE_NAME, getBaseUrlEncoded(),
                    expectedStatus);
        }
    }

    @ParameterizedTest
    @MethodSource("parameters")
    @DisplayName("Create project redirect URL")
    void projectRedirectTest(String workspaceName, String workspaceNameForRedirectRequest, int expectedStatus) {

        var trace = factory.manufacturePojo(Trace.class);
        traceResourceClient.createTrace(trace, API_KEY, workspaceName);
        trace = traceResourceClient.getById(trace.id(), workspaceName, API_KEY);
        var redirectURL = redirectResourceClient.projectsRedirect(trace.id(), UUID.randomUUID().toString(),
                workspaceNameForRedirectRequest, getBaseUrlEncoded(), expectedStatus);
        if (expectedStatus == 303) {
            assertThat(redirectURL).isEqualTo(
                    TRACE_REDIRECT_URL.formatted(wireMock.runtimeInfo().getHttpBaseUrl(), workspaceName,
                            trace.projectId(), trace.id()));
        }
    }

    @Test
    void projectRedirectUrlNoTrace() {
        redirectResourceClient.projectsRedirect(UUID.randomUUID(), UUID.randomUUID().toString(), WORKSPACE_NAME, "path",
                404);
    }

    @ParameterizedTest
    @MethodSource("parameters")
    @DisplayName("Create dataset redirect URL")
    void datasetsRedirectTest(String workspaceName, String workspaceNameForRedirectRequest, int expectedStatus) {
        var dataset = factory.manufacturePojo(Dataset.class);
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, workspaceName);

        var redirectURL = redirectResourceClient.datasetsRedirect(datasetId, UUID.randomUUID().toString(),
                workspaceNameForRedirectRequest, getBaseUrlEncoded(), expectedStatus);
        if (expectedStatus == 303) {
            assertThat(redirectURL)
                    .isEqualTo(DATASET_REDIRECT_URL.formatted(wireMock.runtimeInfo().getHttpBaseUrl(), workspaceName,
                            datasetId));
        }
    }

    @Test
    void datasetsRedirectUrlNoDataset() {
        redirectResourceClient.datasetsRedirect(UUID.randomUUID(), UUID.randomUUID().toString(), null, "path", 404);
    }

    @ParameterizedTest
    @MethodSource("parameters")
    @DisplayName("Create experiment redirect URL")
    void experimentsRedirectTest(String workspaceName, String workspaceNameForRedirectRequest, int expectedStatus) {
        var dataset = factory.manufacturePojo(Dataset.class);
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, workspaceName);

        var experimentId = UUID.randomUUID();

        var redirectURL = redirectResourceClient.experimentsRedirect(datasetId, experimentId,
                UUID.randomUUID().toString(), workspaceNameForRedirectRequest, getBaseUrlEncoded(), expectedStatus);
        if (expectedStatus == 303) {
            var experimentIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(experimentId), StandardCharsets.UTF_8);
            assertThat(redirectURL).isEqualTo(EXPERIMENT_REDIRECT_URL.formatted(wireMock.runtimeInfo().getHttpBaseUrl(),
                    workspaceName, datasetId, experimentIdEncoded));
        }
    }

    @ParameterizedTest
    @MethodSource("parameters")
    @DisplayName("Create optimization redirect URL")
    void optimizationsRedirectTest(String workspaceName, String workspaceNameForRedirectRequest, int expectedStatus) {
        var dataset = factory.manufacturePojo(Dataset.class);
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, workspaceName);

        var optimizationId = UUID.randomUUID();

        var redirectURL = redirectResourceClient.optimizationsRedirect(datasetId, optimizationId,
                UUID.randomUUID().toString(), workspaceNameForRedirectRequest, getBaseUrlEncoded(), expectedStatus);
        if (expectedStatus == 303) {
            var optimizationIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(optimizationId), StandardCharsets.UTF_8);
            assertThat(redirectURL)
                    .isEqualTo(OPTIMIZATION_REDIRECT_URL.formatted(wireMock.runtimeInfo().getHttpBaseUrl(),
                            workspaceName, datasetId, optimizationIdEncoded));
        }
    }

    @Test
    void experimentsRedirectUrlNoDataset() {
        redirectResourceClient.datasetsRedirect(UUID.randomUUID(), UUID.randomUUID().toString(), null, "path", 404);
    }

    Stream<Arguments> parameters() {
        return Stream.of(
                arguments(WORKSPACE_NAME, WORKSPACE_NAME, 303),
                arguments(WORKSPACE_NAME, null, 303),
                arguments(NON_EXISTING_WORKSPACE_NAME, null, 404));
    }

    private String getBaseUrlEncoded() {
        String path = wireMock.runtimeInfo().getHttpBaseUrl() + "/api";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes());
    }
}
