package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Environment;
import com.comet.opik.api.EnvironmentUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.EnvironmentsResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Environments Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class EnvironmentsResourceTest {

    private static final String[] ENVIRONMENT_IGNORED_FIELDS = new String[]{
            "id", "createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String USER = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        MigrationUtils.runMysqlDbMigration(MYSQL);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(), null,
                wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final TimeBasedEpochGenerator idGenerator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private EnvironmentsResourceClient environmentsClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        environmentsClient = new EnvironmentsResourceClient(client, baseURI);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private static String randomEnvName() {
        return "env-" + RandomStringUtils.secure().nextAlphanumeric(12);
    }

    private static Environment buildEnvironment() {
        return Environment.builder()
                .name(randomEnvName())
                .description("desc-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .color("default")
                .position(0)
                .build();
    }

    private void mockIsolatedWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId,
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));
    }

    private void assertCreatedMatches(Environment actual, Environment expected) {
        assertThat(actual)
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withIgnoredFields(ENVIRONMENT_IGNORED_FIELDS)
                        .build())
                .isEqualTo(expected);
        assertThat(actual.id()).isNotNull();
        assertThat(actual.createdBy()).isEqualTo(USER);
        assertThat(actual.lastUpdatedBy()).isEqualTo(USER);
        assertThat(actual.createdAt()).isNotNull().isInstanceOf(Instant.class);
        assertThat(actual.lastUpdatedAt()).isNotNull().isInstanceOf(Instant.class);
    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Create {

        @Test
        @DisplayName("create with all fields succeeds and returns 201 with Location header")
        void createSucceeds() {
            var input = Environment.builder()
                    .name(randomEnvName())
                    .description("staging traffic")
                    .color("yellow")
                    .position(3)
                    .build();

            try (var response = environmentsClient.callCreate(input, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                assertThat(response.getLocation()).isNotNull();

                UUID id = TestUtils.getIdFromLocation(response.getLocation());

                try (var getResponse = environmentsClient.callGet(id, API_KEY, WORKSPACE_NAME)) {
                    assertThat(getResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    var fetched = getResponse.readEntity(Environment.class);

                    assertCreatedMatches(fetched, input);
                    assertThat(fetched.id()).isEqualTo(id);
                }
            }
        }

        @Test
        @DisplayName("create with only name uses defaults: color='default', position=0, description=null")
        void createWithDefaults() {
            var input = Environment.builder().name(randomEnvName()).build();
            var expected = input.toBuilder().color("default").position(0).build();

            UUID id = environmentsClient.createEnvironment(input, API_KEY, WORKSPACE_NAME);

            try (var response = environmentsClient.callGet(id, API_KEY, WORKSPACE_NAME)) {
                var fetched = response.readEntity(Environment.class);
                assertCreatedMatches(fetched, expected);
            }
        }

        @Test
        @DisplayName("create with client-provided id reuses that id")
        void createReusesProvidedId() {
            UUID providedId = idGenerator.generate();
            var input = buildEnvironment().toBuilder().id(providedId).build();

            UUID returnedId = environmentsClient.createEnvironment(input, API_KEY, WORKSPACE_NAME);

            assertThat(returnedId).isEqualTo(providedId);

            try (var response = environmentsClient.callGet(providedId, API_KEY, WORKSPACE_NAME)) {
                var fetched = response.readEntity(Environment.class);
                assertCreatedMatches(fetched, input);
                assertThat(fetched.id()).isEqualTo(providedId);
            }
        }

        @Test
        @DisplayName("create with duplicate name in same workspace returns 409")
        void createDuplicateNameConflicts() {
            var first = buildEnvironment();
            environmentsClient.createEnvironment(first, API_KEY, WORKSPACE_NAME);

            var duplicate = Environment.builder().name(first.name()).build();
            try (var response = environmentsClient.callCreate(duplicate, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("create with same name in different workspace succeeds (workspace isolation)")
        void createSameNameInDifferentWorkspace() {
            String otherApiKey = UUID.randomUUID().toString();
            String otherWorkspaceName = UUID.randomUUID().toString();
            String otherWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(otherApiKey, otherWorkspaceName, otherWorkspaceId);

            var first = buildEnvironment();
            environmentsClient.createEnvironment(first, API_KEY, WORKSPACE_NAME);

            var sameNameDifferentWorkspace = Environment.builder().name(first.name()).build();
            try (var response = environmentsClient.callCreate(sameNameDifferentWorkspace, otherApiKey,
                    otherWorkspaceName)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
            }
        }

        Stream<Arguments> invalidCreatePayloads() {
            return Stream.of(
                    arguments("name with disallowed characters",
                            Environment.builder().name("has spaces").build()),
                    arguments("blank name",
                            Environment.builder().name("").build()),
                    arguments("name longer than 150 characters",
                            Environment.builder().name(RandomStringUtils.secure().nextAlphanumeric(151)).build()),
                    arguments("description longer than 500 characters",
                            buildEnvironment().toBuilder()
                                    .description(RandomStringUtils.secure().nextAlphanumeric(501))
                                    .build()),
                    arguments("color longer than 20 characters",
                            buildEnvironment().toBuilder()
                                    .color(RandomStringUtils.secure().nextAlphanumeric(21))
                                    .build()));
        }

        @ParameterizedTest(name = "create with {0} returns 422")
        @MethodSource("invalidCreatePayloads")
        void createValidationRejected(String description, Environment input) {
            try (var response = environmentsClient.callCreate(input, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode())
                        .isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        @Test
        @DisplayName("create when workspace already has the configured maximum returns 409")
        void createBeyondMaxReturnsConflict() {
            String capApiKey = UUID.randomUUID().toString();
            String capWorkspaceName = UUID.randomUUID().toString();
            String capWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(capApiKey, capWorkspaceName, capWorkspaceId);

            // default max is 20 — fill the workspace then attempt one more
            IntStream.range(0, 20).forEach(i -> environmentsClient.createEnvironment(
                    buildEnvironment(), capApiKey, capWorkspaceName));

            try (var response = environmentsClient.callCreate(buildEnvironment(), capApiKey, capWorkspaceName)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }
    }

    @Nested
    @DisplayName("Get by id:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetById {

        @Test
        @DisplayName("get unknown id returns 404")
        void getUnknownReturnsNotFound() {
            try (var response = environmentsClient.callGet(UUID.randomUUID(), API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("get from a different workspace returns 404")
        void getCrossWorkspaceReturnsNotFound() {
            String otherApiKey = UUID.randomUUID().toString();
            String otherWorkspaceName = UUID.randomUUID().toString();
            String otherWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(otherApiKey, otherWorkspaceName, otherWorkspaceId);

            UUID id = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);

            try (var response = environmentsClient.callGet(id, otherApiKey, otherWorkspaceName)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("Find:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Find {

        @Test
        @DisplayName("find on empty workspace returns empty page")
        void findEmpty() {
            String emptyApiKey = UUID.randomUUID().toString();
            String emptyWorkspaceName = UUID.randomUUID().toString();
            String emptyWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(emptyApiKey, emptyWorkspaceName, emptyWorkspaceId);

            try (var response = environmentsClient.callFind(emptyApiKey, emptyWorkspaceName)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                var page = response.readEntity(Environment.EnvironmentPage.class);

                assertThat(page).isEqualTo(Environment.EnvironmentPage.empty());
            }
        }

        @Test
        @DisplayName("find returns all environments scoped to the workspace")
        void findReturnsWorkspaceEnvironments() {
            String findApiKey = UUID.randomUUID().toString();
            String findWorkspaceName = UUID.randomUUID().toString();
            String findWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(findApiKey, findWorkspaceName, findWorkspaceId);

            String otherApiKey = UUID.randomUUID().toString();
            String otherWorkspaceName = UUID.randomUUID().toString();
            String otherWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(otherApiKey, otherWorkspaceName, otherWorkspaceId);

            List<UUID> mine = IntStream.range(0, 3)
                    .mapToObj(i -> environmentsClient.createEnvironment(
                            buildEnvironment(), findApiKey, findWorkspaceName))
                    .toList();

            // pollute another workspace — must not appear in find
            environmentsClient.createEnvironment(buildEnvironment(), otherApiKey, otherWorkspaceName);

            try (var response = environmentsClient.callFind(findApiKey, findWorkspaceName)) {
                var page = response.readEntity(Environment.EnvironmentPage.class);

                assertThat(page.page()).isEqualTo(1);
                assertThat(page.size()).isEqualTo(3);
                assertThat(page.total()).isEqualTo(3);
                assertThat(page.content()).extracting(Environment::id)
                        .containsExactlyInAnyOrderElementsOf(mine);
                assertThat(page.sortableBy()).contains("created_at");
            }
        }
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Update {

        @Test
        @DisplayName("update partial fields returns 204 and persists changes")
        void updatePartial() {
            var input = buildEnvironment();
            UUID id = environmentsClient.createEnvironment(input, API_KEY, WORKSPACE_NAME);

            var update = EnvironmentUpdate.builder()
                    .description("updated desc")
                    .color("green")
                    .build();

            try (var response = environmentsClient.callUpdate(id, update, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            var expected = input.toBuilder()
                    .description("updated desc")
                    .color("green")
                    .build();

            try (var response = environmentsClient.callGet(id, API_KEY, WORKSPACE_NAME)) {
                var fetched = response.readEntity(Environment.class);
                assertThat(fetched)
                        .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                .withIgnoredFields(ENVIRONMENT_IGNORED_FIELDS)
                                .build())
                        .isEqualTo(expected);
                assertThat(fetched.id()).isEqualTo(id);
            }
        }

        @Test
        @DisplayName("update unknown id returns 404")
        void updateUnknown() {
            var update = EnvironmentUpdate.builder().description("x").build();

            try (var response = environmentsClient.callUpdate(UUID.randomUUID(), update, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("update name to a value already taken by another env returns 409")
        void updateNameConflict() {
            UUID firstId = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);
            var second = buildEnvironment();
            environmentsClient.createEnvironment(second, API_KEY, WORKSPACE_NAME);

            var update = EnvironmentUpdate.builder().name(second.name()).build();

            try (var response = environmentsClient.callUpdate(firstId, update, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("update with invalid name characters returns 422")
        void updateInvalidNameRejected() {
            UUID id = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);
            var update = EnvironmentUpdate.builder().name("has spaces").build();

            try (var response = environmentsClient.callUpdate(id, update, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode())
                        .isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        @Test
        @DisplayName("update from a different workspace returns 404")
        void updateCrossWorkspaceReturnsNotFound() {
            String otherApiKey = UUID.randomUUID().toString();
            String otherWorkspaceName = UUID.randomUUID().toString();
            String otherWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(otherApiKey, otherWorkspaceName, otherWorkspaceId);

            UUID id = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);

            try (var response = environmentsClient.callUpdate(id,
                    EnvironmentUpdate.builder().description("x").build(), otherApiKey, otherWorkspaceName)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("Batch delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchDeletion {

        @Test
        @DisplayName("batch delete returns 204 and removes the environments")
        void deleteBatch() {
            UUID a = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);
            UUID b = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);

            try (var response = environmentsClient.callBatchDelete(Set.of(a, b), API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            try (var response = environmentsClient.callGet(a, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
            try (var response = environmentsClient.callGet(b, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("batch delete is idempotent — missing ids are silently ignored")
        void deleteBatchIdempotent() {
            UUID existing = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);
            UUID missing = UUID.randomUUID();

            try (var response = environmentsClient.callBatchDelete(Set.of(existing, missing), API_KEY,
                    WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }
        }

        @Test
        @DisplayName("batch delete with empty ids returns 422")
        void deleteBatchEmptyRejected() {
            try (var response = environmentsClient.callBatchDelete(new BatchDelete(new HashSet<>()), API_KEY,
                    WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode())
                        .isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        @Test
        @DisplayName("batch delete from another workspace does not affect this workspace's environments")
        void deleteBatchWorkspaceIsolation() {
            String otherApiKey = UUID.randomUUID().toString();
            String otherWorkspaceName = UUID.randomUUID().toString();
            String otherWorkspaceId = UUID.randomUUID().toString();
            mockIsolatedWorkspace(otherApiKey, otherWorkspaceName, otherWorkspaceId);

            UUID id = environmentsClient.createEnvironment(buildEnvironment(), API_KEY, WORKSPACE_NAME);

            try (var response = environmentsClient.callBatchDelete(Set.of(id), otherApiKey, otherWorkspaceName)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }

            try (var response = environmentsClient.callGet(id, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            }
        }
    }
}
