package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.auth.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
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
@DisplayName("Project Resource Test")
class ProjectsResourceTest {

    public static final String URL_PATTERN = "http://.*/v1/private/projects/.{8}-.{4}-.{4}-.{4}-.{12}";
    public static final String URL_TEMPLATE = "%s/v1/private/projects";
    public static final String[] IGNORED_FIELDS = {"createdBy", "lastUpdatedBy", "createdAt", "lastUpdatedAt"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        MYSQL.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), null, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
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

    private UUID createProject(Project project) {
        return createProject(project, API_KEY, TEST_WORKSPACE);
    }

    private UUID createProject(Project project, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
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
        @DisplayName("create project: when api key is present, then return proper response")
        void createProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            var project = factory.manufacturePojo(Project.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.entity(project, MediaType.APPLICATION_JSON_TYPE))) {

                if (success) {
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
        @DisplayName("get project by id: when api key is present, then return proper response")
        void getProjectById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var id = createProject(factory.manufacturePojo(Project.class));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
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
        @DisplayName("update project: when api key is present, then return proper response")
        void updateProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var id = createProject(factory.manufacturePojo(Project.class), okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(factory.manufacturePojo(ProjectUpdate.class)))) {

                if (success) {
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
        @DisplayName("delete project: when api key is present, then return proper response")
        void deleteProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var id = createProject(factory.manufacturePojo(Project.class), okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
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
        @DisplayName("get projects: when api key is present, then return proper response")
        void getProjects__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            var workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var projects = PodamFactoryUtils.manufacturePojoList(factory, Project.class);

            projects.forEach(project -> createProject(project, okApikey, workspaceName));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);
                    assertThat(actualEntity.content()).hasSize(projects.size());
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

        @BeforeAll
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
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create project: when session token is present, then return proper response")
        void createProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var project = factory.manufacturePojo(Project.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.entity(project, MediaType.APPLICATION_JSON_TYPE))) {

                if (success) {
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
        @DisplayName("get project by id: when session token is present, then return proper response")
        void getProjectById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var id = createProject(factory.manufacturePojo(Project.class));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
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
        @DisplayName("update project: when session token is present, then return proper response")
        void updateProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var id = createProject(factory.manufacturePojo(Project.class));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(factory.manufacturePojo(ProjectUpdate.class)))) {

                if (success) {
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
        @DisplayName("delete project: when session token is present, then return proper response")
        void deleteProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var id = createProject(factory.manufacturePojo(Project.class));

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
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
        @DisplayName("get projects: when session token is present, then return proper response")
        void getProjects__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {

            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projects = PodamFactoryUtils.manufacturePojoList(factory, Project.class);

            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            mockSessionCookieTargetWorkspace(this.sessionToken, workspaceName, workspaceId);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);
                    assertThat(actualEntity.content()).hasSize(projects.size());
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
    @DisplayName("Get:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindProject {

        @Test
        @DisplayName("Success")
        void getProjects() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List.of(
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    Project.builder()
                            .name("The most expressive LLM: " + UUID.randomUUID()
                                    + " \uD83D\uDE05\uD83E\uDD23\uD83D\uDE02\uD83D\uDE42\uD83D\uDE43\uD83E\uDEE0")
                            .description("Emoji Test \uD83E\uDD13\uD83E\uDDD0")
                            .build(),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class))
                    .forEach(project -> ProjectsResourceTest.this.createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(10);
            assertThat(actualEntity.content()).hasSize(10);
            assertThat(actualEntity.page()).isEqualTo(1);
        }

        @Test
        @DisplayName("when limit is 5 but there are 10 projects, then return 5 projects and total 10")
        void getProjects__whenLimitIs5ButThereAre10Projects__thenReturn5ProjectsAndTotal10() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            List.of(
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class),
                    factory.manufacturePojo(Project.class))
                    .forEach(project -> ProjectsResourceTest.this.createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 5)
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(5);
            assertThat(actualEntity.content()).hasSize(5);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(10);
        }

        @Test
        @DisplayName("when fetching all project, then return project sorted by created date")
        void getProjects__whenFetchingAllProject__thenReturnProjectSortedByCreatedDate() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = List.of(
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .build());

            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 5)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.size()).isEqualTo(5);

            var actualProjects = actualEntity.content();
            assertThat(projects.get(4).name()).isEqualTo(actualProjects.get(0).name());
            assertThat(projects.get(3).name()).isEqualTo(actualProjects.get(1).name());
            assertThat(projects.get(2).name()).isEqualTo(actualProjects.get(2).name());
            assertThat(projects.get(1).name()).isEqualTo(actualProjects.get(3).name());
            assertThat(projects.get(0).name()).isEqualTo(actualProjects.get(4).name());
        }

        @Test
        @DisplayName("when searching by project name, then return full text search result")
        void getProjects__whenSearchingByProjectName__thenReturnFullTextSearchResult() {
            UUID projectSuffix = UUID.randomUUID();
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = List.of(
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("MySQL, realtime chatboot: " + projectSuffix).build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot using mysql: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot MYSQL expert: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot expert (my SQL): " + projectSuffix).build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chatboot expert: " + projectSuffix)
                            .build());

            projects.forEach(project -> createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", "MySql")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.total()).isEqualTo(3);
            assertThat(actualEntity.size()).isEqualTo(3);

            var actualProjects = actualEntity.content();
            assertThat(actualProjects.stream().map(Project::name).toList()).contains(
                    "MySQL, realtime chatboot: " + projectSuffix,
                    "Chatboot using mysql: " + projectSuffix,
                    "Chatboot MYSQL expert: " + projectSuffix);
        }

        @Test
        @DisplayName("when searching by project name fragments, then return full text search result")
        void getProjects__whenSearchingByProjectNameFragments__thenReturnFullTextSearchResult() {
            UUID projectSuffix = UUID.randomUUID();

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Project> projects = List.of(
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("MySQL: " + projectSuffix).build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Chat-boot using mysql: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("MYSQL CHATBOOT expert: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("Expert Chatboot: " + projectSuffix)
                            .build(),
                    factory.manufacturePojo(Project.class).toBuilder()
                            .name("My chat expert: " + projectSuffix)
                            .build());

            projects
                    .forEach(project -> ProjectsResourceTest.this.createProject(project, apiKey, workspaceName));

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", 100)
                    .queryParam("name", "cha")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Project.ProjectPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.total()).isEqualTo(4);
            assertThat(actualEntity.size()).isEqualTo(4);

            var actualProjects = actualEntity.content();

            assertThat(actualProjects.stream().map(Project::name).toList()).contains(
                    "Chat-boot using mysql: " + projectSuffix,
                    "MYSQL CHATBOOT expert: " + projectSuffix,
                    "Expert Chatboot: " + projectSuffix,
                    "My chat expert: " + projectSuffix);
        }

    }

    @Nested
    @DisplayName("Get: {id}")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetProject {

        @Test
        @DisplayName("Success")
        void getProjectById() {

            var now = Instant.now();

            var project = Project.builder().name("Test Project: " + UUID.randomUUID())
                    .description("Simple Test")
                    .lastUpdatedAt(now)
                    .createdAt(now)
                    .build();

            var id = createProject(project);

            assertProject(project.toBuilder().id(id).build());
        }

        @Test
        @DisplayName("when project not found, then return 404")
        void getProjectById__whenProjectNotFound__whenReturn404() {

            var id = UUID.randomUUID().toString();

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id).request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            assertThat(actualResponse.hasEntity()).isTrue();
            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Project not found");
        }

    }

    private void assertProject(Project project) {
        assertProject(project, API_KEY, TEST_WORKSPACE);
    }

    private void assertProject(Project project, String apiKey, String workspaceName) {
        var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path(project.id().toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        var actualEntity = actualResponse.readEntity(Project.class);

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

        assertThat(actualEntity)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(project);

        assertThat(actualEntity.lastUpdatedBy()).isEqualTo(USER);
        assertThat(actualEntity.createdBy()).isEqualTo(USER);

        assertThat(actualEntity.createdAt()).isAfter(project.createdAt());
        assertThat(actualEntity.lastUpdatedAt()).isAfter(project.createdAt());
    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateProject {

        private String name;

        @BeforeEach
        void setUp() {
            this.name = "Test Project: " + UUID.randomUUID();
        }

        @Test
        @DisplayName("Success")
        void create() {

            var project = factory.manufacturePojo(Project.class);

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.entity(project, MediaType.APPLICATION_JSON_TYPE))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertProject(project.toBuilder().id(id)
                    .build());
        }

        @Test
        @DisplayName("when workspace name is specified, then accept the request")
        void create__whenWorkspaceNameIsSpecified__thenAcceptTheRequest() {
            var project = factory.manufacturePojo(Project.class);

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(project))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                id = TestUtils.getIdFromLocation(actualResponse.getLocation());

            }

            assertProject(project.toBuilder().id(id).build(), apiKey, workspaceName);
        }

        @Test
        @DisplayName("when workspace description is multiline, then accept the request")
        void create__whenDescriptionIsMultiline__thenAcceptTheRequest() {
            var project = factory.manufacturePojo(Project.class);

            project = project.toBuilder().description("Test Project\n\nMultiline Description").build();

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(project))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                assertThat(actualResponse.getHeaderString("Location")).matches(Pattern.compile(URL_PATTERN));

                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertProject(project.toBuilder().id(id).build());
        }

        @Test
        @DisplayName("when description is null, then accept the request")
        void create__whenDescriptionIsNull__thenAcceptNameCreate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(Project.builder().name(name).build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                assertThat(actualResponse.getHeaderString("Location")).matches(Pattern.compile(URL_PATTERN));
            }
        }

        @Test
        @DisplayName("when name is null, then reject the request")
        void create__whenNameIsNull__thenRejectNameCreate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(Project.builder().description("Test Project").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("name must not be blank");
            }
        }

        @Test
        @DisplayName("when project name already exists, then reject the request")
        void create__whenProjectNameAlreadyExists__thenRejectNameCreate() {

            String projectName = UUID.randomUUID().toString();

            Project project = Project.builder().name(projectName).build();

            createProject(project);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(project))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Project already exists");
            }
        }

        @Test
        @DisplayName("when projects with same name but different workspace, then accept the request")
        void create__whenProjectsHaveSameNameButDifferentWorkspace__thenAcceptTheRequest() {

            var project1 = factory.manufacturePojo(Project.class);

            String workspaceId = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String apiKey2 = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey2, workspaceName, workspaceId);

            UUID id;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(project1))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            var project2 = project1.toBuilder()
                    .id(factory.manufacturePojo(UUID.class))
                    .build();

            UUID id2;
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey2)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(project2))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();

                id2 = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            assertProject(project1.toBuilder().id(id).build());
            assertProject(project2.toBuilder().id(id2).build(), apiKey2, workspaceName);
        }
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateProject {

        private UUID projectId;
        private String name;

        @BeforeEach
        void setUp() {
            this.name = "Test Project: " + UUID.randomUUID();
            this.projectId = createProject(Project.builder()
                    .name(name)
                    .description("Simple Test")
                    .build());
        }

        @Test
        @DisplayName("Success")
        void update() {
            String name = "Test Project: " + UUID.randomUUID();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().name(name).description("Simple Test 2").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var actualEntity = actualResponse.readEntity(Project.class);

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualEntity.description()).isEqualTo("Simple Test 2");
                assertThat(actualEntity.name()).isEqualTo(name);
            }
        }

        @Test
        @DisplayName("Not Found")
        void update__whenProjectNotFound__thenReturn404() {
            var id = UUID.randomUUID().toString();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI)).path(id)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(ProjectUpdate.builder()
                            .name("Test Project 2")
                            .description("Simple Test 2")
                            .build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("Project not found");
            }
        }

        @Test
        @DisplayName("when description is null, then accept name update")
        void update__whenDescriptionIsNull__thenAcceptNameUpdate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH, Entity.json(ProjectUpdate.builder().name("Test Project xxx").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var actualEntity = actualResponse.readEntity(Project.class);

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualEntity.description()).isEqualTo("Simple Test");
                assertThat(actualEntity.name()).isEqualTo("Test Project xxx");
            }
        }

        @Test
        @DisplayName("when name is null, then accept description update")
        void update__whenNameIsNull__thenAcceptDescriptionUpdate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().description("Simple Test xxx").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                var actualEntity = actualResponse.readEntity(Project.class);

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualEntity.description()).isEqualTo("Simple Test xxx");
                assertThat(actualEntity.name()).isEqualTo(name);
            }
        }

        @Test
        @DisplayName("when description is blank, then reject the update")
        void update__whenDescriptionIsBlank__thenRejectTheUpdate() {

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().name("Test Project").description("").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains("description must not be blank");
            }
        }

        @Test
        @DisplayName("when name is blank, then reject the update")
        void update__whenNameIsBlank__thenRejectTheUpdate() {
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(projectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .method(HttpMethod.PATCH,
                            Entity.json(ProjectUpdate.builder().description("Simple Test: ").name("").build()))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains("name must not be blank");
            }
        }
    }

    @Nested
    @DisplayName("Delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteProject {

        @Test
        @DisplayName("Success")
        void delete() {
            var id = createProject(factory.manufacturePojo(Project.class).toBuilder().build());

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            }
        }

        @Test
        @DisplayName("when trying to delete default project, then return conflict")
        void delete__whenTryingToDeleteDefaultProject__thenReturnConflict() {
            Project project = Project.builder()
                    .name(DEFAULT_PROJECT)
                    .build();

            UUID defaultProjectId = createProject(project);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(defaultProjectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .contains("Cannot delete default project");
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(defaultProjectId.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            }
        }
    }

}
