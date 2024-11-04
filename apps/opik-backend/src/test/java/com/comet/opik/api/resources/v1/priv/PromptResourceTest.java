package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
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
@DisplayName("Prompt Resource Test")
class PromptResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/prompts";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

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

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
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
        @DisplayName("create prompt: when api key is present, then return proper response")
        void createProject__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            var project = factory.manufacturePojo(Project.class);
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
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
        @DisplayName("find prompt: when api key is present, then return proper response")
        void findPrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
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
        @DisplayName("create prompt: when session token is present, then return proper response")
        void createProject__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var project = factory.manufacturePojo(Project.class);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI)).request()
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
        @DisplayName("find prompt: when session token is present, then return proper response")
        void findPrompt__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }
    }

    private UUID createPrompt(Prompt prompt, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(prompt))) {

            assertThat(response.getStatus()).isEqualTo(201);

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    @Nested
    @DisplayName("Create Prompt")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreatePrompt {

        @Test
        @DisplayName("Success: should create prompt")
        void shouldCreatePrompt() {

            var prompt = factory.manufacturePojo(Prompt.class);

            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            assertThat(promptId).isNotNull();
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when prompt state is invalid, then return conflict")
        void when__promptIsInvalid__thenReturnError(Prompt prompt, int expectedStatusCode, Object expectedBody,
                Class<?> expectedResponseClass) {

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(prompt))) {

                assertThat(response.getStatus()).isEqualTo(expectedStatusCode);

                var actualBody = response.readEntity(expectedResponseClass);

                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> when__promptIsInvalid__thenReturnError() {
            Prompt prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .id(UUID.randomUUID())
                    .build();

            Prompt duplicatedPrompt = factory.manufacturePojo(Prompt.class);
            createPrompt(duplicatedPrompt, API_KEY, TEST_WORKSPACE);

            return Stream.of(
                    Arguments.of(prompt, 400,
                            new ErrorMessage(List.of("prompt id must be a version 7 UUID")),
                            ErrorMessage.class),
                    Arguments.of(duplicatedPrompt.toBuilder().name(UUID.randomUUID().toString()).build(), 409,
                            new io.dropwizard.jersey.errors.ErrorMessage("Prompt id or name already exists"),
                            io.dropwizard.jersey.errors.ErrorMessage.class),
                    Arguments.of(duplicatedPrompt.toBuilder().id(factory.manufacturePojo(UUID.class)).build(), 409,
                            new io.dropwizard.jersey.errors.ErrorMessage("Prompt id or name already exists"),
                            io.dropwizard.jersey.errors.ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().description("").build(), 422,
                            new ErrorMessage(List.of("description must not be blank")),
                            ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().name("").build(), 422,
                            new ErrorMessage(List.of("name must not be blank")), ErrorMessage.class));
        }
    }

    @Nested
    @DisplayName("Find Prompt")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindPrompt {

        @Test
        @DisplayName("Success: should find prompt")
        void shouldFindPrompt() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of(prompt);

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null);
        }

        @Test
        @DisplayName("when search by name, then return prompt matching name")
        void when__searchByName__thenReturnPromptMatchingName() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of(prompt);

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, prompt.name());
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when search by  partial name, then return prompt matching name")
        void when__searchByPartialName__thenReturnPromptMatchingName(String promptName, String partialSearch) {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            IntStream.range(0, 4).forEach(i -> {
                var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build();

                createPrompt(prompt, apiKey, workspaceName);
            });

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .name(promptName)
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of(prompt);
            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, partialSearch);
        }

        Stream<Arguments> when__searchByPartialName__thenReturnPromptMatchingName() {
            return Stream.of(
                    arguments("prompt", "pro"),
                    arguments("prompt", "pt"),
                    arguments("prompt", "om"));
        }

        @Test
        @DisplayName("when fetch prompts, then return prompts sorted by creation time")
        void when__fetchPrompts__thenReturnPromptsSortedByCreationTime() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompts = PodamFactoryUtils.manufacturePojoList(factory, Prompt.class).stream()
                    .map(prompt -> prompt.toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build())
                    .toList();

            prompts.forEach(prompt -> createPrompt(prompt, apiKey, workspaceName));

            List<Prompt> expectedPrompts = prompts.reversed();

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null);
        }

        @Test
        @DisplayName("when fetch prompts using pagination, then return prompts paginated")
        void when__fetchPromptsUsingPagination__thenReturnPromptsPaginated() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompts = IntStream.range(0, 20)
                    .mapToObj(i -> factory.manufacturePojo(Prompt.class).toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build())
                    .toList();

            prompts.forEach(prompt -> createPrompt(prompt, apiKey, workspaceName));

            List<Prompt> promptPage1 = prompts.reversed().subList(0, 10);
            List<Prompt> promptPage2 = prompts.reversed().subList(10, 20);

            findPromptsAndAssertPage(promptPage1, apiKey, workspaceName, prompts.size(), 1, null);
            findPromptsAndAssertPage(promptPage2, apiKey, workspaceName, prompts.size(), 2, null);
        }
    }

    private void findPromptsAndAssertPage(List<Prompt> expectedPrompts, String apiKey, String workspaceName,
            int expectedTotal, int page, String nameSearch) {

        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (nameSearch != null) {
            target = target.queryParam("name", nameSearch);
        }

        if (page > 1) {
            target = target.queryParam("page", page);
        }

        try (var response = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(200);

            var promptPage = response.readEntity(Prompt.PromptPage.class);

            assertThat(promptPage.total()).isEqualTo(expectedTotal);
            assertThat(promptPage.content()).hasSize(expectedPrompts.size());
            assertThat(promptPage.page()).isEqualTo(page);
            assertThat(promptPage.size()).isEqualTo(expectedPrompts.size());

            assertThat(promptPage.content())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields("versionCount", "latestVersion")
                                    .withComparatorForType(this::comparatorForCreateAtAndUpdatedAt, Instant.class)
                                    .build())
                    .isEqualTo(expectedPrompts);
        }
    }

    private int comparatorForCreateAtAndUpdatedAt(Instant actual, Instant expected) {
        var now = Instant.now();

        if (actual.isAfter(now) || actual.equals(now))
            return 1;
        if (actual.isBefore(expected))
            return -1;

        Assertions.assertThat(actual).isBetween(expected, now);
        return 0;
    }
}