package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectNameHolder;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
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
class CheckAccessResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/check";
    public static final String URL_TEMPLATE_PROJECTS = ProjectsResourceTest.URL_TEMPLATE;

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String UNAUTHORISED_WORKSPACE_NAME = UUID.randomUUID().toString();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createProject(Project project, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE_PROJECTS.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(project))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            return TestUtils.getIdFromLocation(actualResponse.getLocation());
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
                    arguments(okApikey, 204),
                    arguments(fakeApikey, 401),
                    arguments("", 401));
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

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(okApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(UNAUTHORISED_WORKSPACE_NAME)))
                            .willReturn(WireMock.forbidden()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create project: when api key is present, then return proper response")
        void createProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, int expectedStatus) {

            var project = factory.manufacturePojo(Project.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);
            createProject(project, okApikey, workspaceName);

            checkProjectAccess(apiKey, workspaceName, project.name(), expectedStatus);
        }

        @ParameterizedTest
        @MethodSource
        void useInvalidWorkspace__thenReturnForbiddenResponse(String invalidWorkspaceName) {

            var project = factory.manufacturePojo(Project.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);
            createProject(project, okApikey, workspaceName);

            checkProjectAccess(okApikey, invalidWorkspaceName, project.name(), 403);
        }

        @Test
        void useNonExistingProject__thenReturnNotFoundResponse() {
            var project = factory.manufacturePojo(Project.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);
            createProject(project, okApikey, workspaceName);

            checkProjectAccess(okApikey, workspaceName, factory.manufacturePojo(String.class), 404);
        }

        private Stream<Arguments> useInvalidWorkspace__thenReturnForbiddenResponse() {
            return Stream.of(
                    arguments(""),
                    arguments(UNAUTHORISED_WORKSPACE_NAME),
                    arguments(DEFAULT_WORKSPACE_NAME)
            );
        }

        private void checkProjectAccess(String apiKey,
                                        String workspaceName,
                                        String projectName,
                                        int expectedStatus) {
            var request = ProjectNameHolder.builder().project(projectName).build();


            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("project")
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
                    assertThat(actualResponse.hasEntity()).isTrue();
                }
            }
        }
    }
}