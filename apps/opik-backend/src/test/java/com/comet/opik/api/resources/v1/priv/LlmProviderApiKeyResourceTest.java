package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Page;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthCheck;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.domain.LlmProviderApiKeyDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.EncryptionUtils.decrypt;
import static com.comet.opik.infrastructure.EncryptionUtils.maskApiKey;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.llm.customllm.CustomLlmModelNameChecker.CUSTOM_LLM_MODEL_PREFIX;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Proxy Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class LlmProviderApiKeyResourceTest {
    private static final String USER = UUID.randomUUID().toString();
    public static final String[] IGNORED_FIELDS = {"createdAt", "lastUpdatedAt", "apiKey"};

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

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

    private TransactionTemplate mySqlTemplate;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplate mySqlTemplate) throws SQLException {

        this.mySqlTemplate = mySqlTemplate;
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(client);

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
    @DisplayName("Required permissions")
    class RequiredPermissionsTest {

        @Test
        @DisplayName("Store LLM provider API key passes required permissions to auth endpoint")
        void storeLlmProviderApiKeyPassesRequiredPermissionsToAuthEndpoint() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var providerApiKey = createProviderApiKey();

            wireMock.server().resetRequests();
            llmProviderApiKeyResourceClient.callCreateProviderApiKey(providerApiKey, apiKey, workspaceName).close();

            wireMock.server().verify(
                    postRequestedFor(urlPathEqualTo("/opik/auth"))
                            .withRequestBody(matchingJsonPath("$.requiredPermissions[0]",
                                    equalTo(WorkspaceUserPermission.AI_PROVIDER_UPDATE.getValue()))));
        }

        @Test
        @DisplayName("Update LLM provider API key passes required permissions to auth endpoint")
        void updateLlmProviderApiKeyPassesRequiredPermissionsToAuthEndpoint() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var providerApiKey = createProviderApiKey();
            var created = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey, workspaceName,
                    201);

            var update = ProviderApiKeyUpdate.builder()
                    .apiKey(UUID.randomUUID().toString())
                    .build();

            wireMock.server().resetRequests();
            llmProviderApiKeyResourceClient.callUpdateProviderApiKey(created.id(), update, apiKey, workspaceName)
                    .close();

            wireMock.server().verify(
                    postRequestedFor(urlPathEqualTo("/opik/auth"))
                            .withRequestBody(matchingJsonPath("$.requiredPermissions[0]",
                                    equalTo(WorkspaceUserPermission.AI_PROVIDER_UPDATE.getValue()))));
        }

        @Test
        @DisplayName("Delete LLM provider API keys passes required permissions to auth endpoint")
        void deleteLlmProviderApiKeysPassesRequiredPermissionsToAuthEndpoint() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var providerApiKey = createProviderApiKey();
            var created = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey, workspaceName,
                    201);

            wireMock.server().resetRequests();
            llmProviderApiKeyResourceClient.callDeleteProviderApiKeys(Set.of(created.id()), apiKey, workspaceName)
                    .close();

            wireMock.server().verify(
                    postRequestedFor(urlPathEqualTo("/opik/auth"))
                            .withRequestBody(matchingJsonPath("$.requiredPermissions[0]",
                                    equalTo(WorkspaceUserPermission.AI_PROVIDER_UPDATE.getValue()))));
        }

        @Test
        @DisplayName("Store LLM provider API key returns 403 when permission is denied")
        void storeLlmProviderApiKeyReturnsForbiddenWhenPermissionDenied() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();

            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    WorkspaceUserPermission.AI_PROVIDER_UPDATE.getValue());

            var providerApiKey = createProviderApiKey();

            try (var response = llmProviderApiKeyResourceClient.callCreateProviderApiKey(providerApiKey, apiKey,
                    workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        }

        @Test
        @DisplayName("Update LLM provider API key returns 403 when permission is denied")
        void updateLlmProviderApiKeyReturnsForbiddenWhenPermissionDenied() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();

            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    WorkspaceUserPermission.AI_PROVIDER_UPDATE.getValue());

            var update = ProviderApiKeyUpdate.builder()
                    .apiKey(UUID.randomUUID().toString())
                    .build();

            try (var response = llmProviderApiKeyResourceClient.callUpdateProviderApiKey(UUID.randomUUID(), update,
                    apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        }

        @Test
        @DisplayName("Delete LLM provider API keys returns 403 when permission is denied")
        void deleteLlmProviderApiKeysReturnsForbiddenWhenPermissionDenied() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();

            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    WorkspaceUserPermission.AI_PROVIDER_UPDATE.getValue());

            try (var response = llmProviderApiKeyResourceClient.callDeleteProviderApiKeys(
                    Set.of(UUID.randomUUID()), apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        }
    }

    @Test
    @DisplayName("Create provider Api Key")
    void testCreateProviderApiKey() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        ProviderApiKey providerApiKey = createProviderApiKey();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);
        getAndAssertProviderApiKey(expectedProviderApiKey, apiKey, workspaceName);
        checkEncryption(expectedProviderApiKey.id(), workspaceId, providerApiKey.apiKey());
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Update provider Api Key")
    void testUpdateProviderApiKey(ProviderApiKeyUpdate update, Function<ProviderApiKey, ProviderApiKey> getExpected) {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        ProviderApiKey providerApiKey = createProviderApiKey();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var createdProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, HttpStatus.SC_CREATED);

        llmProviderApiKeyResourceClient.updateProviderApiKey(createdProviderApiKey.id(), update, apiKey,
                workspaceName, HttpStatus.SC_NO_CONTENT);

        getAndAssertProviderApiKey(getExpected.apply(createdProviderApiKey), apiKey, workspaceName);

        checkEncryption(createdProviderApiKey.id(), workspaceId,
                update.apiKey() == null ? providerApiKey.apiKey() : update.apiKey());
    }

    private Stream<Arguments> testUpdateProviderApiKey() {
        var updateAll = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder()
                .providerName(null)
                .build();

        Function<ProviderApiKey, ProviderApiKey> getExpectedAll = (ProviderApiKey original) -> original.toBuilder()
                .name(updateAll.name())
                .apiKey(updateAll.apiKey())
                .headers(updateAll.headers())
                .configuration(updateAll.configuration())
                .baseUrl(updateAll.baseUrl())
                .build();

        Function<ProviderApiKey, ProviderApiKey> getExpectedName = (ProviderApiKey original) -> original.toBuilder()
                .name(updateAll.name())
                .build();

        Function<ProviderApiKey, ProviderApiKey> getExpectedApiKey = (ProviderApiKey original) -> original.toBuilder()
                .apiKey(updateAll.apiKey())
                .build();

        Function<ProviderApiKey, ProviderApiKey> getExpectedBaseUrl = (ProviderApiKey original) -> original.toBuilder()
                .baseUrl(updateAll.baseUrl())
                .build();

        Function<ProviderApiKey, ProviderApiKey> getExpectedHeaders = (ProviderApiKey original) -> original.toBuilder()
                .headers(updateAll.headers())
                .build();

        Function<ProviderApiKey, ProviderApiKey> getExpectedConfig = (ProviderApiKey original) -> original.toBuilder()
                .configuration(updateAll.configuration())
                .build();

        return Stream.of(
                arguments(named("all fields", updateAll), getExpectedAll),
                arguments(named("only name", updateAll.toBuilder().apiKey(null).headers(null).configuration(null)
                        .baseUrl(null).build()), getExpectedName),
                arguments(named("only apiKey", updateAll.toBuilder().name(null).headers(null).configuration(null)
                        .baseUrl(null).build()), getExpectedApiKey),
                arguments(named("only baseUrl", updateAll.toBuilder().apiKey(null).name(null).headers(null)
                        .configuration(null).build()), getExpectedBaseUrl),
                arguments(named("only headers", updateAll.toBuilder().name(null).apiKey(null).configuration(null)
                        .baseUrl(null).build()), getExpectedHeaders),
                arguments(named("only configuration", updateAll.toBuilder().name(null).apiKey(null).headers(null)
                        .baseUrl(null).build()), getExpectedConfig));
    }

    @Test
    @DisplayName("Update provider Api Key - only name and apiKey")
    void testUpdateProviderApiKeyOnlyNameAndApiKey() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        ProviderApiKey providerApiKey = createProviderApiKey()
                .toBuilder()
                .headers(null)
                .configuration(null)
                .baseUrl(null)
                .build();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);

        var providerApiKeyUpdate = factory.manufacturePojo(ProviderApiKeyUpdate.class)
                .toBuilder()
                .headers(null)
                .configuration(null)
                .baseUrl(null)
                .providerName(null)
                .build();
        llmProviderApiKeyResourceClient.updateProviderApiKey(expectedProviderApiKey.id(), providerApiKeyUpdate, apiKey,
                workspaceName, 204);

        var expectedUpdatedProviderApiKey = expectedProviderApiKey.toBuilder()
                .apiKey(providerApiKeyUpdate.apiKey())
                .name(providerApiKeyUpdate.name())
                .build();
        getAndAssertProviderApiKey(expectedUpdatedProviderApiKey, apiKey, workspaceName);

        checkEncryption(expectedProviderApiKey.id(), workspaceId, providerApiKeyUpdate.apiKey());
    }

    private ProviderApiKey createProviderApiKey() {
        return factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .provider(LlmProvider.OPEN_AI) // avoid using a custom provider as it has additional requirements
                .providerName(null)
                .build();
    }

    @Test
    @DisplayName("Create and update provider Api Key for invalid name")
    void createAndUpdateProviderApiKeyForInvalidName() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        ProviderApiKey invalidNameProviderApiKey = createProviderApiKey().toBuilder()
                .name(StringUtils.repeat('x', 160))
                .build();

        llmProviderApiKeyResourceClient.createProviderApiKey(invalidNameProviderApiKey, apiKey, workspaceName, 422);

        ProviderApiKey providerApiKey = createProviderApiKey();

        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);

        var providerApiKeyUpdate = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder()
                .name(StringUtils.repeat('x', 160))
                .build();

        llmProviderApiKeyResourceClient.updateProviderApiKey(expectedProviderApiKey.id(), providerApiKeyUpdate, apiKey,
                workspaceName, 422);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Create provider Api Key with invalid payload")
    void createAndUpdateProviderApiKeyInvalidPayload(String body, String errorMsg) {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        try (var actualResponse = llmProviderApiKeyResourceClient.createProviderApiKey(body, apiKey, workspaceName,
                400)) {
            var actualError = actualResponse.readEntity(ErrorMessage.class);

            assertThat(actualError.getMessage()).startsWith(errorMsg);
        }
    }

    @ParameterizedTest
    @EmptySource
    @NullSource
    @DisplayName("Create and update provider with empty apiKey is allowed for custom provider")
    void createUpdateCustomProviderWithEmptyApiKeyIsAllowed(String emptyString) {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var testProvider = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .name(CUSTOM_LLM_MODEL_PREFIX + "some_model_name")
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName(UUID.randomUUID().toString())
                .apiKey(emptyString)
                .build();
        var createdProvider = llmProviderApiKeyResourceClient.createProviderApiKey(testProvider, apiKey, workspaceName,
                HttpStatus.SC_CREATED);

        var testProviderUpdate = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder()
                .apiKey(emptyString).providerName(null).build();
        llmProviderApiKeyResourceClient.updateProviderApiKey(createdProvider.id(), testProviderUpdate, apiKey,
                workspaceName, HttpStatus.SC_NO_CONTENT);

        var actual = llmProviderApiKeyResourceClient.getById(createdProvider.id(), workspaceName, apiKey,
                HttpStatus.SC_OK);
        var actualApiKey = Optional.ofNullable(actual.apiKey()).map(EncryptionUtils::decrypt).orElse(null);
        assertThat(actualApiKey).isEqualTo(emptyString);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Create provider Api Key with invalid payload 422")
    void createAndUpdateProviderApiKeyInvalidPayload422(String body, String errorMsg) {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        try (var actualResponse = llmProviderApiKeyResourceClient.createProviderApiKey(body, apiKey, workspaceName,
                422)) {
            var actualError = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);

            assertThat(actualError.errors()).contains(errorMsg);
        }
    }

    Stream<Arguments> createAndUpdateProviderApiKeyInvalidPayload422() {
        ProviderApiKey providerApiKey = factory.manufacturePojo(ProviderApiKey.class);
        return Stream.of(
                arguments(
                        JsonUtils.writeValueAsString(providerApiKey.toBuilder().baseUrl("").build()),
                        "baseUrl must not be blank"),
                arguments(
                        JsonUtils.writeValueAsString(providerApiKey.toBuilder()
                                .provider(LlmProvider.GEMINI)
                                .apiKey("")
                                .build()),
                        "apiKey must not be blank"),
                arguments(
                        JsonUtils.writeValueAsString(providerApiKey.toBuilder()
                                .provider(LlmProvider.GEMINI)
                                .apiKey(null)
                                .build()),
                        "apiKey must not be blank"),
                arguments(
                        JsonUtils.writeValueAsString(providerApiKey.toBuilder()
                                .name(RandomStringUtils.secure().nextAlphabetic(200)).build()),
                        "name size must be between 0 and 150"));
    }

    Stream<Arguments> createAndUpdateProviderApiKeyInvalidPayload() {
        String body = "qwerty12345";
        ProviderApiKey providerApiKey = factory.manufacturePojo(ProviderApiKey.class);
        return Stream.of(
                arguments(body,
                        "Unable to process JSON. Unrecognized token '%s': was expecting (JSON String, Number (or 'NaN'/'+INF'/'-INF'), Array, Object or token 'null', 'true' or 'false')"
                                .formatted(body)),
                arguments(
                        JsonUtils.writeValueAsString(providerApiKey).replace(providerApiKey.provider().getValue(),
                                "something"),
                        "Unable to process JSON. Cannot construct instance of `com.comet.opik.api.LlmProvider`, problem: Unknown llm provider 'something'"));
    }

    @Test
    @DisplayName("Create and batch delete provider Api Keys")
    void createAndBatchDeleteProviderApiKeys() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        ProviderApiKey providerApiKey = createProviderApiKey();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var createdProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);

        // Delete
        llmProviderApiKeyResourceClient.batchDeleteProviderApiKey(Set.of(createdProviderApiKey.id()), apiKey,
                workspaceName);

        // Delete one more time for non existing key, should return same 204 response
        llmProviderApiKeyResourceClient.batchDeleteProviderApiKey(Set.of(createdProviderApiKey.id()), apiKey,
                workspaceName);

        // Check that it was deleted
        llmProviderApiKeyResourceClient.getById(createdProviderApiKey.id(), workspaceName, apiKey, 404);
    }

    @Test
    @DisplayName("Create provider Api Key for existing provider should fail")
    void createProviderApiKeyForExistingProviderShouldFail() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        ProviderApiKey providerApiKey = createProviderApiKey();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey, workspaceName, 201);
        llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey, workspaceName, 409);
    }

    @Test
    @DisplayName("Update provider Api Key for non-existing Id")
    void updateProviderFail() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var providerApiKeyUpdate = factory.manufacturePojo(ProviderApiKeyUpdate.class);
        // for non-existing id
        llmProviderApiKeyResourceClient.updateProviderApiKey(UUID.randomUUID(), providerApiKeyUpdate, apiKey,
                workspaceName,
                404);
    }

    @Test
    @DisplayName("Create and get provider Api Keys List")
    void createAndGetProviderApiKeyList() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        ProviderApiKey providerApiKey = createProviderApiKey();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // No LLM Provider api keys, expect empty response
        var actualProviderApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
        assertPage(actualProviderApiKeyPage, List.of());

        // Create LLM Provider api key
        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);
        actualProviderApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
        assertPage(actualProviderApiKeyPage, List.of(expectedProviderApiKey));
    }

    @Test
    @DisplayName("Create and get provider Api Keys List With Minimal Fields")
    void createAndGetProviderApiKeyListWithMinimalFields() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        ProviderApiKey providerApiKey = createProviderApiKey().toBuilder()
                .headers(null)
                .baseUrl(null)
                .build();

        // No LLM Provider api keys, expect empty response
        var actualProviderApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
        assertPage(actualProviderApiKeyPage, List.of());

        // Create LLM Provider api key
        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);
        actualProviderApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
        assertPage(actualProviderApiKeyPage, List.of(expectedProviderApiKey));
    }

    @Nested
    @DisplayName("Auth config:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AuthConfigTest {

        private static final String SECRET_VALUE = "super-s3cr3t";

        private ProviderAuthConfig tokenAuthConfig() {
            return ProviderAuthConfig.builder()
                    .tokenUrl("https://auth.example.com/oauth/token")
                    .sendAs(ProviderAuthConfig.SendAs.BASIC)
                    .credentials(List.of(
                            credential("grant_type", "client_credentials", false),
                            credential("client_id", "opik-prod", false),
                            credential("client_secret", SECRET_VALUE, true)))
                    .tokenField("access_token")
                    .expiresField("expires_in")
                    .fallbackTtlSeconds(3600L)
                    .build();
        }

        private ProviderAuthConfig.Credential credential(String key, String value, boolean secret) {
            return ProviderAuthConfig.Credential.builder().key(key).value(value).secret(secret).build();
        }

        private ProviderApiKey customProviderWithAuthConfig() {
            return factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(UUID.randomUUID().toString())
                    .apiKey(null)
                    .authConfig(tokenAuthConfig())
                    .build();
        }

        private ProviderAuthConfig storedAuthConfig(UUID id, String workspaceId) {
            return mySqlTemplate.inTransaction(READ_ONLY,
                    handle -> handle.attach(LlmProviderApiKeyDAO.class).findById(id, workspaceId).authConfig());
        }

        @Test
        @DisplayName("create stores the recipe encrypted and reads it back masked")
        void createAndGetMasksSecrets() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var created = llmProviderApiKeyResourceClient.createProviderApiKey(
                    customProviderWithAuthConfig(), apiKey, workspaceName, HttpStatus.SC_CREATED);

            var actual = llmProviderApiKeyResourceClient.getById(created.id(), workspaceName, apiKey,
                    HttpStatus.SC_OK);
            assertThat(actual.authConfig()).isEqualTo(tokenAuthConfig().mask());
            assertThat(actual.authConfig().credentials().getLast().value())
                    .isEqualTo(ProviderAuthConfig.SECRET_SENTINEL);

            var page = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
            assertThat(page.content().getFirst().authConfig()).isEqualTo(tokenAuthConfig().mask());

            String rawColumn = mySqlTemplate.inTransaction(READ_ONLY, handle -> handle
                    .createQuery("SELECT auth_config FROM llm_provider_api_key WHERE id = :id")
                    .bind("id", created.id().toString())
                    .mapTo(String.class)
                    .one());
            assertThat(rawColumn).doesNotContain(SECRET_VALUE, "client_id", "token_url");
            assertThat(EncryptionUtils.decryptGcm(rawColumn)).contains(SECRET_VALUE);
        }

        @Test
        @DisplayName("update with the sentinel keeps the stored secret, and a lock can't be removed")
        void updateWithSentinelKeepsStoredSecret() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var created = llmProviderApiKeyResourceClient.createProviderApiKey(
                    customProviderWithAuthConfig(), apiKey, workspaceName, HttpStatus.SC_CREATED);

            // sentinel for the secret (also trying to unlock it), a new value for client_id
            var update = ProviderApiKeyUpdate.builder()
                    .authConfig(tokenAuthConfig().toBuilder()
                            .credentials(List.of(
                                    credential("grant_type", "client_credentials", false),
                                    credential("client_id", "rotated-id", false),
                                    credential("client_secret", ProviderAuthConfig.SECRET_SENTINEL, false)))
                            .build())
                    .build();
            llmProviderApiKeyResourceClient.updateProviderApiKey(created.id(), update, apiKey, workspaceName,
                    HttpStatus.SC_NO_CONTENT);

            var stored = storedAuthConfig(created.id(), workspaceId);
            var storedByKey = stored.credentials().stream()
                    .collect(java.util.stream.Collectors
                            .toMap(ProviderAuthConfig.Credential::key, Function.identity()));
            assertThat(storedByKey.get("client_secret").value()).isEqualTo(SECRET_VALUE);
            assertThat(storedByKey.get("client_secret").secret()).isTrue();
            assertThat(storedByKey.get("client_id").value()).isEqualTo("rotated-id");
        }

        @Test
        @DisplayName("sentinel on a key without a stored secret is rejected")
        void updateSentinelUnknownKeyIsRejected() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var created = llmProviderApiKeyResourceClient.createProviderApiKey(
                    customProviderWithAuthConfig(), apiKey, workspaceName, HttpStatus.SC_CREATED);

            var update = ProviderApiKeyUpdate.builder()
                    .authConfig(tokenAuthConfig().toBuilder()
                            .credentials(List.of(
                                    credential("brand_new_key", ProviderAuthConfig.SECRET_SENTINEL, true)))
                            .build())
                    .build();
            try (var response = llmProviderApiKeyResourceClient.callUpdateProviderApiKey(created.id(), update,
                    apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(response.readEntity(ErrorMessage.class).getMessage()).contains("brand_new_key");
            }
        }

        @Test
        @DisplayName("empty object clears the auth config, allowing the switch back to a static key")
        void clearAuthConfigAndSwitchToStaticKey() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var created = llmProviderApiKeyResourceClient.createProviderApiKey(
                    customProviderWithAuthConfig(), apiKey, workspaceName, HttpStatus.SC_CREATED);

            var update = ProviderApiKeyUpdate.builder()
                    .apiKey("brand-new-static-key")
                    .authConfig(ProviderAuthConfig.builder().build())
                    .build();
            llmProviderApiKeyResourceClient.updateProviderApiKey(created.id(), update, apiKey, workspaceName,
                    HttpStatus.SC_NO_CONTENT);

            var actual = llmProviderApiKeyResourceClient.getById(created.id(), workspaceName, apiKey,
                    HttpStatus.SC_OK);
            assertThat(actual.authConfig()).isNull();
            checkEncryption(created.id(), workspaceId, "brand-new-static-key");
        }

        @Test
        @DisplayName("switching a keyed provider to token auth clears the stored static key")
        void switchFromStaticKeyToTokenAuthClearsTheStoredKey() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var created = llmProviderApiKeyResourceClient.createProviderApiKey(
                    customProviderWithAuthConfig().toBuilder()
                            .apiKey("legacy-static-key")
                            .authConfig(null)
                            .build(),
                    apiKey, workspaceName, HttpStatus.SC_CREATED);

            // the dialog hides the api_key field in token mode, so the update carries none
            var update = ProviderApiKeyUpdate.builder().authConfig(tokenAuthConfig()).build();
            llmProviderApiKeyResourceClient.updateProviderApiKey(created.id(), update, apiKey, workspaceName,
                    HttpStatus.SC_NO_CONTENT);

            var actual = llmProviderApiKeyResourceClient.getById(created.id(), workspaceName, apiKey,
                    HttpStatus.SC_OK);
            assertThat(actual.authConfig()).isNotNull();
            checkEncryption(created.id(), workspaceId, "");
        }

        @Test
        @DisplayName("setting a static key while token auth is configured is rejected")
        void updateApiKeyWhileTokenModeActiveIsRejected() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var created = llmProviderApiKeyResourceClient.createProviderApiKey(
                    customProviderWithAuthConfig(), apiKey, workspaceName, HttpStatus.SC_CREATED);

            var update = ProviderApiKeyUpdate.builder().apiKey("some-static-key").build();
            try (var response = llmProviderApiKeyResourceClient.callUpdateProviderApiKey(created.id(), update,
                    apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("auth config on a provider without provider_name is rejected")
        void authConfigOnStandardProviderIsRejected() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // create
            llmProviderApiKeyResourceClient.createProviderApiKey(
                    createProviderApiKey().toBuilder().authConfig(tokenAuthConfig()).build(),
                    apiKey, workspaceName, HttpStatus.SC_UNPROCESSABLE_CONTENT);

            // update
            var created = llmProviderApiKeyResourceClient.createProviderApiKey(createProviderApiKey(), apiKey,
                    workspaceName, HttpStatus.SC_CREATED);
            var update = ProviderApiKeyUpdate.builder().authConfig(tokenAuthConfig()).build();
            try (var response = llmProviderApiKeyResourceClient.callUpdateProviderApiKey(created.id(), update,
                    apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("create rejects the sentinel, both credentials set, and an invalid token URL")
        void createInvalidAuthConfigIsRejected() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // sentinel on create: nothing stored to keep
            var withSentinel = customProviderWithAuthConfig().toBuilder()
                    .authConfig(tokenAuthConfig().toBuilder()
                            .credentials(List.of(
                                    credential("client_secret", ProviderAuthConfig.SECRET_SENTINEL, true)))
                            .build())
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(withSentinel, apiKey, workspaceName,
                    HttpStatus.SC_UNPROCESSABLE_CONTENT);

            // static key and token auth at once
            var withBoth = customProviderWithAuthConfig().toBuilder().apiKey("static-key").build();
            llmProviderApiKeyResourceClient.createProviderApiKey(withBoth, apiKey, workspaceName,
                    HttpStatus.SC_UNPROCESSABLE_CONTENT);

            // token URL that isn't an absolute URI
            var withBadUrl = customProviderWithAuthConfig().toBuilder()
                    .authConfig(tokenAuthConfig().toBuilder().tokenUrl("not a uri").build())
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(withBadUrl, apiKey, workspaceName,
                    HttpStatus.SC_UNPROCESSABLE_CONTENT);
        }
    }

    @Nested
    @DisplayName("Auth config check endpoint:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AuthConfigCheckEndpoint {

        private static final String SECRET_VALUE = "endpoint-s3cr3t";

        /** unique path per test: the wiremock server is shared with the auth mocks, never reset here */
        private String stubTokenEndpoint(String body, int status) {
            String tokenPath = "/provider-auth-token/" + UUID.randomUUID();
            wireMock.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(tokenPath))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withStatus(status)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
            return tokenPath;
        }

        /** plain-http base: baseUrl() prefers the https port, whose self-signed cert the fetcher rejects */
        private String tokenUrl(String tokenPath) {
            return "http://localhost:" + wireMock.server().port() + tokenPath;
        }

        private ProviderAuthConfig recipe(String tokenUrl, String secretValue) {
            return ProviderAuthConfig.builder()
                    .tokenUrl(tokenUrl)
                    .credentials(List.of(
                            credential("grant_type", "client_credentials", false),
                            credential("client_id", "opik-prod", false),
                            credential("client_secret", secretValue, true)))
                    .build();
        }

        private ProviderAuthConfig.Credential credential(String key, String value, boolean secret) {
            return ProviderAuthConfig.Credential.builder().key(key).value(value).secret(secret).build();
        }

        private ProviderApiKey createCustomProvider(ProviderAuthConfig authConfig, String apiKey,
                String workspaceName) {
            var provider = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(UUID.randomUUID().toString())
                    .apiKey(null)
                    .authConfig(authConfig)
                    .build();
            return llmProviderApiKeyResourceClient.createProviderApiKey(provider, apiKey, workspaceName,
                    HttpStatus.SC_CREATED);
        }

        @Test
        @DisplayName("submitted values are tested and the lifetime is reported, never the token")
        void testWithSubmittedAuthConfig() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String tokenPath = stubTokenEndpoint("{\"access_token\": \"tok-endpoint\", \"expires_in\": 1800}", 200);
            var request = ProviderAuthCheck.builder()
                    .authConfig(recipe(tokenUrl(tokenPath), SECRET_VALUE))
                    .build();

            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(request, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                String body = response.readEntity(String.class);
                var result = JsonUtils.readValue(body, ProviderAuthCheck.Result.class);
                assertThat(result.lifetimeSeconds()).isEqualTo(1800);
                assertThat(body).doesNotContain("tok-endpoint");
            }
        }

        @Test
        @DisplayName("by provider id, the stored recipe is used with its real secrets, server-side")
        void testWithStoredAuthConfig() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String tokenPath = stubTokenEndpoint("{\"access_token\": \"tok\", \"expires_in\": 60}", 200);
            var created = createCustomProvider(recipe(tokenUrl(tokenPath), SECRET_VALUE), apiKey, workspaceName);

            var request = ProviderAuthCheck.builder().providerId(created.id()).build();
            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(request, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }

            wireMock.server().verify(postRequestedFor(urlPathEqualTo(tokenPath))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                            .containing("client_secret=" + SECRET_VALUE)));
        }

        @Test
        @DisplayName("sentinels in submitted values resolve against the stored recipe when the id is given")
        void testResolvesSentinelsAgainstStoredConfig() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String tokenPath = stubTokenEndpoint("{\"access_token\": \"tok\", \"expires_in\": 60}", 200);
            var created = createCustomProvider(recipe(tokenUrl(tokenPath), SECRET_VALUE), apiKey, workspaceName);

            var request = ProviderAuthCheck.builder()
                    .providerId(created.id())
                    .authConfig(recipe(tokenUrl(tokenPath), ProviderAuthConfig.SECRET_SENTINEL))
                    .build();
            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(request, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }

            wireMock.server().verify(postRequestedFor(urlPathEqualTo(tokenPath))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                            .containing("client_secret=" + SECRET_VALUE)));
        }

        @Test
        @DisplayName("sentinels without a provider id are rejected: there is nothing stored to resolve against")
        void testSentinelWithoutIdIsRejected() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var request = ProviderAuthCheck.builder()
                    .authConfig(recipe("https://auth.example.com/token", ProviderAuthConfig.SECRET_SENTINEL))
                    .build();
            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(request, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(response.readEntity(ErrorMessage.class).getMessage()).contains("client_secret");
            }
        }

        @Test
        @DisplayName("upstream auth failures surface status and body with credential values redacted")
        void testSurfacesUpstreamErrorsRedacted() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String tokenPath = stubTokenEndpoint(
                    "{\"error\": \"invalid_client\", \"echo\": \"%s\"}".formatted(SECRET_VALUE), 401);
            var request = ProviderAuthCheck.builder()
                    .authConfig(recipe(tokenUrl(tokenPath), SECRET_VALUE))
                    .build();

            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(request, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                String message = response.readEntity(ErrorMessage.class).getMessage();
                assertThat(message).contains("401").contains("invalid_client").doesNotContain(SECRET_VALUE);
            }
        }

        @Test
        @DisplayName("a request with neither id nor auth config, or an id without a stored recipe, is rejected")
        void testInvalidRequestsAreRejected() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(
                    ProviderAuthCheck.builder().build(), apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_CONTENT);
                assertThat(response.readEntity(String.class)).contains("either provider_id or auth_config");
            }

            var staticProvider = llmProviderApiKeyResourceClient.createProviderApiKey(
                    createProviderApiKey(), apiKey, workspaceName, HttpStatus.SC_CREATED);
            try (var response = llmProviderApiKeyResourceClient.callTestAuthConfig(
                    ProviderAuthCheck.builder().providerId(staticProvider.id()).build(),
                    apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(response.readEntity(ErrorMessage.class).getMessage()).contains("no auth_config");
            }
        }
    }

    private void getAndAssertProviderApiKey(ProviderApiKey expected, String apiKey, String workspaceName) {
        var actualEntity = llmProviderApiKeyResourceClient.getById(expected.id(), workspaceName, apiKey, 200);

        assertThat(actualEntity)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(expected);

        // We should decrypt api key in order to compare, since it encrypts on deserialization
        assertThat(decrypt(actualEntity.apiKey())).isEqualTo(maskApiKey(expected.apiKey()));
        assertThat(actualEntity.createdAt()).isAfter(expected.createdAt());
        assertThat(actualEntity.createdBy()).isEqualTo(expected.createdBy());
        assertThat(actualEntity.lastUpdatedAt()).isAfter(expected.lastUpdatedAt());
        assertThat(actualEntity.lastUpdatedBy()).isEqualTo(expected.lastUpdatedBy());
    }

    private void checkEncryption(UUID id, String workspaceId, String expectedApiKey) {
        String actualEncryptedApiKey = mySqlTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(LlmProviderApiKeyDAO.class);
            return repository.findById(id, workspaceId).apiKey();
        });
        assertThat(decrypt(actualEncryptedApiKey)).isEqualTo(expectedApiKey);
    }

    private void assertPage(Page<ProviderApiKey> actual, List<ProviderApiKey> expected) {
        assertThat(actual.content()).hasSize(expected.size());
        assertThat(actual.page()).isZero();
        assertThat(actual.total()).isEqualTo(expected.size());
        assertThat(actual.size()).isEqualTo(expected.size());

        assertThat(actual.content())
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(expected);

        for (int i = 0; i < expected.size(); i++) {
            ProviderApiKey actualEntity = actual.content().get(i);
            ProviderApiKey expectedEntity = expected.get(i);

            // We should decrypt api key in order to compare, since it encrypts on deserialization
            assertThat(decrypt(actualEntity.apiKey())).isEqualTo(maskApiKey(expectedEntity.apiKey()));
            assertThat(actualEntity.createdAt()).isAfter(expectedEntity.createdAt());
            assertThat(actualEntity.createdBy()).isEqualTo(expectedEntity.createdBy());
            assertThat(actualEntity.lastUpdatedAt()).isAfter(expectedEntity.lastUpdatedAt());
            assertThat(actualEntity.lastUpdatedBy()).isEqualTo(expectedEntity.lastUpdatedBy());
        }
    }

    @ParameterizedTest
    @EmptySource
    @NullSource
    @DisplayName("Create custom provider with blank providerName should fail")
    void createCustomProviderWithBlankProviderNameShouldFail(String blankProviderName) {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var customProvider = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName(blankProviderName)
                .apiKey("")
                .build();

        try (var actualResponse = llmProviderApiKeyResourceClient.createProviderApiKey(
                JsonUtils.writeValueAsString(customProvider), apiKey, workspaceName, 422)) {
            var actualError = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);
            assertThat(actualError.errors())
                    .contains("providerName provider_name is required for custom LLM and Bedrock providers");
        }
    }

    @Test
    @DisplayName("Create multiple custom providers with different providerNames should succeed")
    void createMultipleCustomProvidersWithDifferentNamesShouldSucceed() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var customProvider1 = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName("ollama")
                .apiKey("")
                .build();

        var customProvider2 = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName("vllm")
                .apiKey("")
                .build();

        // Both should succeed because they have different providerNames
        var created1 = llmProviderApiKeyResourceClient.createProviderApiKey(customProvider1, apiKey, workspaceName,
                201);
        var created2 = llmProviderApiKeyResourceClient.createProviderApiKey(customProvider2, apiKey, workspaceName,
                201);

        assertThat(created1.providerName()).isEqualTo("ollama");
        assertThat(created2.providerName()).isEqualTo("vllm");
    }

    @Test
    @DisplayName("Create duplicate custom provider with same providerName should fail")
    void createDuplicateCustomProviderWithSameNameShouldFail() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var customProvider = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName("ollama")
                .apiKey("")
                .build();

        // First creation should succeed
        llmProviderApiKeyResourceClient.createProviderApiKey(customProvider, apiKey, workspaceName, 201);

        // Second creation with the same providerName should fail
        llmProviderApiKeyResourceClient.createProviderApiKey(customProvider, apiKey, workspaceName, 409);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Create non-custom provider with providerName should succeed (providerName is ignored)")
    void createNonCustomProviderWithProviderNameShouldSucceed(LlmProvider provider) {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var providerWithName = factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(provider)
                .providerName("should-be-ignored")
                .build();

        // Should succeed - providerName is simply ignored for non-custom providers
        var created = llmProviderApiKeyResourceClient.createProviderApiKey(providerWithName, apiKey, workspaceName,
                201);

        // Fetch the actual object from DB to verify providerName was set to null
        var actual = llmProviderApiKeyResourceClient.getById(created.id(), workspaceName, apiKey, 200);

        // Verify the provider was created but providerName was ignored (should be null for non-custom providers)
        assertThat(actual.provider()).isEqualTo(provider);
        // The providerName should be null since it's ignored for non-custom providers
        assertThat(actual.providerName()).isNull();
    }

    private Stream<Arguments> createNonCustomProviderWithProviderNameShouldSucceed() {
        return Stream.of(
                arguments(LlmProvider.OPEN_AI),
                arguments(LlmProvider.ANTHROPIC),
                arguments(LlmProvider.GEMINI),
                arguments(LlmProvider.OPEN_ROUTER),
                arguments(LlmProvider.VERTEX_AI));
    }
}
