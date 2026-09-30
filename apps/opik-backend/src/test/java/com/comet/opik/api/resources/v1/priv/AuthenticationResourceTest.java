package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AuthDetailsHolder;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.WorkspaceNameHolder;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
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

import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CheckAccess Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AuthenticationResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/auth";

    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String UNAUTHORISED_WORKSPACE_NAME = UUID.randomUUID().toString();
    // EM signals "not a member of organization" with 404, not 403 (OPIK-8554).
    private static final String NOT_A_MEMBER_WORKSPACE_NAME = UUID.randomUUID().toString();
    // A 404 that did not come from EM — an ingress or proxy answering, i.e. a misconfiguration.
    private static final String MISROUTED_WORKSPACE_NAME = UUID.randomUUID().toString();
    private static final String NOT_A_MEMBER_MESSAGE = "User is not a member of organization";

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

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
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
                    arguments(okApikey, 204, ""),
                    arguments(fakeApikey, 401, FAKE_API_KEY_MESSAGE),
                    arguments("", 401, MISSING_API_KEY));
        }

        @BeforeEach
        void setUp() {

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(fakeApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE, 401)))));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(okApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(UNAUTHORISED_WORKSPACE_NAME)))
                            .willReturn(WireMock.forbidden()));

            // EM's actual answer for a non-member: 404 carrying its error envelope.
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(okApikey))
                            .withRequestBody(
                                    matchingJsonPath("$.workspaceName", matching(NOT_A_MEMBER_WORKSPACE_NAME)))
                            .willReturn(WireMock.notFound().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(NOT_A_MEMBER_MESSAGE, 404)))));

            // A 404 from something that is not EM, i.e. the route is wrong rather than the user unentitled.
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(okApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(MISROUTED_WORKSPACE_NAME)))
                            .willReturn(WireMock.notFound().withHeader("Content-Type", "text/html")
                                    .withBody("<html><body>404 Not Found</body></html>")));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("check access: when api key is present, then return proper response")
        void checkAccess__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, int expectedStatus,
                String errorMessage) {

            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            checkProjectAccess(apiKey, workspaceName, expectedStatus, errorMessage);
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("check access for default workspace: when api key is present, then return proper response")
        void checkAccessForDefaultWorkspace__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                int expectedStatus,
                String errorMessage) {

            mockTargetWorkspace(okApikey, DEFAULT_WORKSPACE_NAME, WORKSPACE_ID);

            checkProjectAccess(apiKey, DEFAULT_WORKSPACE_NAME, expectedStatus, errorMessage);
        }

        @ParameterizedTest
        @MethodSource
        void useInvalidWorkspace__thenReturnForbiddenResponse(String invalidWorkspaceName, String errorMessage) {

            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            checkProjectAccess(okApikey, invalidWorkspaceName, 403, errorMessage);
        }

        private Stream<Arguments> useInvalidWorkspace__thenReturnForbiddenResponse() {
            return Stream.of(
                    arguments("", MISSING_WORKSPACE),
                    arguments(UNAUTHORISED_WORKSPACE_NAME, NOT_ALLOWED_TO_ACCESS_WORKSPACE),
                    // The whole point of OPIK-8554, asserted at the HTTP boundary: EM's 404 has to reach the
                    // caller as 403 through AuthFilter and the exception mapper, not as the 500 it used to be.
                    arguments(NOT_A_MEMBER_WORKSPACE_NAME, NOT_ALLOWED_TO_ACCESS_WORKSPACE));
        }

        @DisplayName("check access: when a 404 did not come from EM, then it stays a server error")
        @Test
        void useMisroutedReactService__thenReturnServerError() {
            // The 404 -> 403 mapping is narrowed to EM's error envelope on purpose. A misconfigured
            // reactService URL must not be reported as an entitlement decision: 403 counts as "not
            // authenticated", which would let the PUBLIC_ENDPOINTS reads continue with Visibility.PUBLIC.
            // The message is Dropwizard's generated one and carries an incident id, so only the status is
            // asserted here.
            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var request = AuthDetailsHolder.builder().build();
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, okApikey)
                    .header(WORKSPACE_HEADER, MISROUTED_WORKSPACE_NAME)
                    .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(500);
            }
        }

        @ParameterizedTest
        @MethodSource
        void getWorkspaceName(String apiKey, int expectedStatus,
                String errorMessage) {
            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            getAndAsserWorkspaceName(apiKey, workspaceName, expectedStatus, errorMessage);
        }

        private Stream<Arguments> getWorkspaceName() {
            return Stream.of(
                    arguments(okApikey, 200, ""),
                    arguments(fakeApikey, 401, FAKE_API_KEY_MESSAGE),
                    arguments("", 401, MISSING_API_KEY));
        }

        private void checkProjectAccess(String apiKey,
                String workspaceName,
                int expectedStatus,
                String expectedErrorMessage) {
            var request = AuthDetailsHolder.builder().build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

                if (expectedStatus == 204) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
                    var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(actualError.getMessage()).isEqualTo(expectedErrorMessage);
                }
            }
        }

        private void getAndAsserWorkspaceName(String apiKey,
                String workspaceName,
                int expectedStatus,
                String expectedErrorMessage) {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("workspace")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
                if (expectedStatus == 200) {
                    assertThat(actualResponse.readEntity(WorkspaceNameHolder.class).workspaceName())
                            .isEqualTo(workspaceName);
                } else {
                    var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(actualError.getMessage()).isEqualTo(expectedErrorMessage);
                }
            }
        }
    }
}