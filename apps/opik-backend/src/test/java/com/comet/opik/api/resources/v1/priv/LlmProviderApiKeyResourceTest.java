package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Page;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
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
