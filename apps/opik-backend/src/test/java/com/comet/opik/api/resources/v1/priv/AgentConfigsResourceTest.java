package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvSetByName;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.AgentConfigRemoveValues;
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
import com.comet.opik.api.resources.utils.resources.AgentConfigsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.domain.AgentBlueprint;
import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import com.comet.opik.domain.AgentConfigEnv;
import com.comet.opik.domain.AgentConfigValue;
import com.comet.opik.domain.AgentConfigValue.ValueType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AgentConfigValueAssertionUtils.assertConfigValue;
import static com.comet.opik.api.resources.utils.AgentConfigValueAssertionUtils.assertConfigValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Agent Config Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AgentConfigsResourceTest {

    record TestSetupData(
            UUID projectId,
            UUID blueprint1Id,
            UUID blueprint2Id,
            UUID blueprint3Id,
            UUID maskId) {
    }

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    public static final String[] BLUEPRINT_PAYLOAD_IGNORED_FIELDS = {"envs", "createdBy", "createdAt",
            "lastUpdatedBy", "lastUpdatedAt"};

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
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, ClickHouseContainerUtils.DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private AgentConfigsResourceClient agentConfigsResourceClient;
    private ProjectResourceClient projectResourceClient;
    private PromptResourceClient promptResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        String baseUrl = TestUtils.getBaseUrl(client);
        this.agentConfigsResourceClient = new AgentConfigsResourceClient(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);
        this.promptResourceClient = new PromptResourceClient(client, baseUrl, factory);

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Create Optimizer Config:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateAgentConfig {

        @Test
        @DisplayName("Success: should create optimizer config with blueprint")
        void createAgentConfig() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var values = List.of(
                    AgentConfigValue.builder()
                            .key("model")
                            .value("gpt-4")
                            .type(ValueType.STRING)
                            .description("LLM model to use")
                            .build(),
                    AgentConfigValue.builder()
                            .key("temperature")
                            .value("0.7")
                            .type(ValueType.FLOAT)
                            .description("Sampling temperature")
                            .build(),
                    AgentConfigValue.builder()
                            .key("stream")
                            .value("true")
                            .type(ValueType.BOOLEAN)
                            .build());

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Initial configuration")
                    .values(values)
                    .build();

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint)
                    .build();

            var blueprintId = agentConfigsResourceClient.createAgentConfig(request, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var prodBlueprint = agentConfigsResourceClient.getBlueprintByEnv("prod", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(prodBlueprint).isNotNull();
            assertThat(prodBlueprint.id()).isEqualTo(blueprintId);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when request is invalid, then return validation error")
        void createAgentConfig__whenRequestIsInvalid__thenReturnValidationError(
                AgentConfigCreate request, int expectedStatusCode, Object expectedBody,
                Class<?> expectedResponseClass) {

            try (var actualResponse = agentConfigsResourceClient.createAgentConfigWithResponse(request, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatusCode);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualBody = actualResponse.readEntity(expectedResponseClass);
                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> createAgentConfig__whenRequestIsInvalid__thenReturnValidationError() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var validValues = List.of(
                    AgentConfigValue.builder()
                            .key("model")
                            .value("gpt-4")
                            .type(ValueType.STRING)
                            .build());

            var validBlueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test")
                    .values(validValues)
                    .build();

            return Stream.of(
                    // Blueprint validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(null)
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint must not be null")),
                            ErrorMessage.class),

                    // Blueprint type validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder().type(null).build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.type must not be null")),
                            ErrorMessage.class),

                    // Values validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder().values(null).build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values must not be null")),
                            ErrorMessage.class),

                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder().values(List.of()).build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values blueprint must have between 1 and 250 values")),
                            ErrorMessage.class),

                    // Value key validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    AgentConfigValue.builder()
                                                            .key(null)
                                                            .value("test")
                                                            .type(ValueType.STRING)
                                                            .build()))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values[0].key must not be blank")),
                            ErrorMessage.class),

                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    AgentConfigValue.builder()
                                                            .key("")
                                                            .value("test")
                                                            .type(ValueType.STRING)
                                                            .build()))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values[0].key must not be blank")),
                            ErrorMessage.class),

                    // Value type validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    AgentConfigValue.builder()
                                                            .key("test")
                                                            .value("value")
                                                            .type(null)
                                                            .build()))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values[0].type must not be null")),
                            ErrorMessage.class),

                    // Description max length validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .description("a".repeat(256))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.description cannot exceed 255 characters")),
                            ErrorMessage.class),

                    // Duplicate key validation
                    arguments(
                            AgentConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    AgentConfigValue.builder()
                                                            .key("model")
                                                            .value("gpt-4")
                                                            .type(ValueType.STRING)
                                                            .build(),
                                                    AgentConfigValue.builder()
                                                            .key("model")
                                                            .value("claude")
                                                            .type(ValueType.STRING)
                                                            .build()))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint. Duplicate configuration keys are not allowed")),
                            ErrorMessage.class));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success: should create and retrieve config value for each value type")
        void createAgentConfig__perValueType(ValueType valueType, String value) {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var configValue = AgentConfigValue.builder()
                    .key(RandomStringUtils.insecure().nextAlphanumeric(10))
                    .value(value)
                    .type(valueType)
                    .build();

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .values(List.of(configValue))
                    .build();

            var blueprintId = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var retrieved = agentConfigsResourceClient.getLatestBlueprint(projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(retrieved).isNotNull();
            assertThat(retrieved.id()).isEqualTo(blueprintId);
            assertThat(retrieved.values()).hasSize(1);

            var retrievedValue = retrieved.values().getFirst();
            assertConfigValue(configValue, retrievedValue);
            assertThat(retrievedValue.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("Success: blueprint names auto-increment as v1, v2, v3")
        void createAgentConfig__nameAutoIncrements() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint1Id = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .description("First")
                                    .values(List.of(AgentConfigValue.builder()
                                            .key("model").value("gpt-4").type(ValueType.STRING).build()))
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var blueprint2Id = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .description("Second")
                                    .values(List.of(AgentConfigValue.builder()
                                            .key("temperature").value("0.5").type(ValueType.FLOAT).build()))
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var blueprint3Id = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .description("Third")
                                    .values(List.of(AgentConfigValue.builder()
                                            .key("max_tokens").value("2048").type(ValueType.INTEGER).build()))
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var bp1 = agentConfigsResourceClient.getBlueprintById(blueprint1Id, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            var bp2 = agentConfigsResourceClient.getBlueprintById(blueprint2Id, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            var bp3 = agentConfigsResourceClient.getBlueprintById(blueprint3Id, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(bp1.name()).isEqualTo("v1");
            assertThat(bp2.name()).isEqualTo("v2");
            assertThat(bp3.name()).isEqualTo("v3");
        }

        Stream<Arguments> createAgentConfig__perValueType() {
            return Stream.of(
                    arguments(ValueType.STRING, RandomStringUtils.insecure().nextAlphanumeric(10)),
                    arguments(ValueType.INTEGER, RandomStringUtils.insecure().nextAlphanumeric(10)),
                    arguments(ValueType.FLOAT, RandomStringUtils.insecure().nextAlphanumeric(10)),
                    arguments(ValueType.BOOLEAN, RandomStringUtils.insecure().nextAlphanumeric(10)),
                    arguments(ValueType.PROMPT, UUID.randomUUID().toString()),
                    arguments(ValueType.PROMPT_COMMIT, UUID.randomUUID().toString()),
                    arguments(ValueType.STRING, null),
                    arguments(ValueType.INTEGER, null),
                    arguments(ValueType.FLOAT, null),
                    arguments(ValueType.BOOLEAN, null));
        }

        @Test
        @DisplayName("Error: POST returns 409 when config already exists for project")
        void createAgentConfig__whenConfigAlreadyExists__thenReturn409() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("First blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint)
                    .build();

            agentConfigsResourceClient.createAgentConfig(request, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            try (var actualResponse = agentConfigsResourceClient.createAgentConfigWithResponse(request, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                assertThat(actualResponse.hasEntity()).isTrue();
                var body = actualResponse.readEntity(ErrorMessage.class);
                assertThat(body.errors()).anyMatch(e -> e.contains("already exists"));
            }
        }

        @Test
        @DisplayName("Error: POST returns 400 when blueprint type is MASK")
        void createAgentConfig__whenBlueprintTypeIsMask__thenReturn400() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.MASK)
                    .description("Mask blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint)
                    .build();

            try (var actualResponse = agentConfigsResourceClient.createAgentConfigWithResponse(request, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(actualResponse.hasEntity()).isTrue();
                var body = actualResponse.readEntity(ErrorMessage.class);
                assertThat(body.errors()).contains(
                        "Cannot create config with a MASK blueprint. Use BLUEPRINT type for POST.");
            }
        }

        @Test
        @DisplayName("Error: PATCH returns 404 when config does not exist for project")
        void updateAgentConfig__whenConfigDoesNotExist__thenReturn404() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Blueprint for non-existing config")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint)
                    .build();

            try (var actualResponse = agentConfigsResourceClient.updateAgentConfigWithResponse(request, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_FOUND);
                assertThat(actualResponse.hasEntity()).isTrue();
                var body = actualResponse.readEntity(ErrorMessage.class);
                assertThat(body.errors()).anyMatch(e -> e.contains("No config found"));
            }
        }

        @Test
        @DisplayName("Success: PATCH resolves project by name")
        void updateAgentConfig__withProjectName__thenSuccess() {
            var projectName = UUID.randomUUID().toString();
            projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var createRequest = AgentConfigCreate.builder()
                    .projectName(projectName)
                    .blueprint(AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("Initial blueprint")
                            .values(List.of(AgentConfigValue.builder()
                                    .key("model").value("gpt-4").type(ValueType.STRING).build()))
                            .build())
                    .build();

            agentConfigsResourceClient.createAgentConfig(createRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_CREATED);

            var updateRequest = AgentConfigCreate.builder()
                    .projectName(projectName)
                    .blueprint(AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("Updated blueprint")
                            .values(List.of(AgentConfigValue.builder()
                                    .key("temperature").value("0.7").type(ValueType.FLOAT).build()))
                            .build())
                    .build();

            var blueprintId = agentConfigsResourceClient.updateAgentConfig(updateRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_CREATED);

            var retrieved = agentConfigsResourceClient.getBlueprintById(blueprintId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(retrieved.name()).isEqualTo("v2");
            assertThat(retrieved.description()).isEqualTo("Updated blueprint");
        }

    }

    @Nested
    @DisplayName("Retrieve Agent Config:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetrieveAgentConfig {

        private TestSetupData setupBlueprintsAndMask() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint1 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Initial configuration")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                                    .description("LLM model to use").build(),
                            AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT)
                                    .build(),
                            AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER)
                                    .build(),
                            AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                            AgentConfigValue.builder().key("system_prompt").value("prompt-content")
                                    .type(ValueType.PROMPT).build(),
                            AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                                    .type(ValueType.PROMPT_COMMIT).build()))
                    .build();

            var request1 = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint1)
                    .build();

            var blueprint1Id = agentConfigsResourceClient.createAgentConfig(request1, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_CREATED);

            var blueprint2 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Update temperature")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                                    .description("LLM model to use").build(),
                            AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT)
                                    .build(),
                            AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER)
                                    .build(),
                            AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                            AgentConfigValue.builder().key("system_prompt").value("prompt-content")
                                    .type(ValueType.PROMPT).build(),
                            AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                                    .type(ValueType.PROMPT_COMMIT).build()))
                    .build();

            var request2 = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint2)
                    .build();

            var blueprint2Id = agentConfigsResourceClient.updateAgentConfig(request2, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_CREATED);

            var blueprint3 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Update max_tokens")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                                    .description("LLM model to use").build(),
                            AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT)
                                    .build(),
                            AgentConfigValue.builder().key("max_tokens").value("2048").type(ValueType.INTEGER)
                                    .build(),
                            AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                            AgentConfigValue.builder().key("system_prompt").value("prompt-content")
                                    .type(ValueType.PROMPT).build(),
                            AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                                    .type(ValueType.PROMPT_COMMIT).build()))
                    .build();

            var request3 = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint3)
                    .build();

            var blueprint3Id = agentConfigsResourceClient.updateAgentConfig(request3, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_CREATED);

            var mask = AgentBlueprint.builder()
                    .type(BlueprintType.MASK)
                    .description("Override model and add top_p")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("claude-3").type(ValueType.STRING)
                                    .build(),
                            AgentConfigValue.builder().key("top_p").value("0.95").type(ValueType.FLOAT).build()))
                    .build();

            var maskRequest = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(mask)
                    .build();

            var maskId = agentConfigsResourceClient.updateAgentConfig(maskRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_CREATED);

            var envUpdate = AgentConfigEnvUpdate.builder()
                    .projectId(projectId)
                    .envs(List.of(
                            com.comet.opik.domain.AgentConfigEnv.builder()
                                    .envName("dev")
                                    .blueprintId(blueprint2Id)
                                    .build()))
                    .build();

            agentConfigsResourceClient.createOrUpdateEnvs(envUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            return new TestSetupData(projectId, blueprint1Id, blueprint2Id, blueprint3Id, maskId);
        }

        @Test
        @DisplayName("Success: retrieve latest blueprint with all inherited values")
        void retrieveLatestBlueprint() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getLatestBlueprint(setup.projectId(), null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint3Id());
            assertThat(blueprint.name()).isEqualTo("v3");
            assertThat(blueprint.values()).hasSize(6);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                            .description("LLM model to use").build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("2048").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @Test
        @DisplayName("Success: retrieve middle blueprint with partial inheritance")
        void retrieveBlueprintById_middleBlueprint() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getBlueprintById(setup.blueprint2Id(), null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(blueprint.name()).isEqualTo("v2");
            assertThat(blueprint.values()).hasSize(6);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                            .description("LLM model to use").build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @Test
        @DisplayName("Success: retrieve latest blueprint with mask applied")
        void retrieveLatestBlueprintWithMask() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getLatestBlueprint(setup.projectId(), setup.maskId(), API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.values()).hasSize(7);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("claude-3").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("2048").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build(),
                    AgentConfigValue.builder().key("top_p").value("0.95").type(ValueType.FLOAT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @Test
        @DisplayName("Success: retrieve blueprint by environment")
        void retrieveBlueprintByEnv() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getBlueprintByEnv("dev", setup.projectId(), null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(blueprint.name()).isEqualTo("v2");
            assertThat(blueprint.values()).hasSize(6);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                            .description("LLM model to use").build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @Test
        @DisplayName("Success: retrieve blueprint by environment with mask")
        void retrieveBlueprintByEnvWithMask() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getBlueprintByEnv("dev", setup.projectId(), setup.maskId(),
                    API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(blueprint.values()).hasSize(7);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("claude-3").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build(),
                    AgentConfigValue.builder().key("top_p").value("0.95").type(ValueType.FLOAT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @Test
        @DisplayName("Success: retrieve blueprint returns full stored values")
        void retrieveDelta() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getDelta(setup.blueprint2Id(), API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(blueprint.name()).isEqualTo("v2");
            assertThat(blueprint.values()).hasSize(6);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                            .description("LLM model to use").build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when retrieving latest blueprint fails, then return expected error")
        void retrieveLatestBlueprint__whenError__thenReturnExpectedStatus(
                UUID projectId, UUID maskId, int expectedStatus) {

            agentConfigsResourceClient.getLatestBlueprint(projectId, maskId, API_KEY, TEST_WORKSPACE,
                    expectedStatus);
        }

        Stream<Arguments> retrieveLatestBlueprint__whenError__thenReturnExpectedStatus() {
            return Stream.of(
                    arguments(UUID.randomUUID(), null, HttpStatus.SC_NOT_FOUND),
                    arguments(UUID.randomUUID(), UUID.randomUUID(), HttpStatus.SC_NOT_FOUND));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when retrieving blueprint by ID fails, then return expected error")
        void retrieveBlueprintById__whenError__thenReturnExpectedStatus(
                UUID blueprintId, UUID maskId, int expectedStatus) {

            agentConfigsResourceClient.getBlueprintById(blueprintId, maskId, API_KEY, TEST_WORKSPACE,
                    expectedStatus);
        }

        Stream<Arguments> retrieveBlueprintById__whenError__thenReturnExpectedStatus() {
            return Stream.of(
                    arguments(UUID.randomUUID(), null, HttpStatus.SC_NOT_FOUND),
                    arguments(UUID.randomUUID(), UUID.randomUUID(), HttpStatus.SC_NOT_FOUND));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when retrieving delta fails, then return expected error")
        void retrieveDelta__whenError__thenReturnExpectedStatus(
                UUID blueprintId, int expectedStatus) {

            agentConfigsResourceClient.getDelta(blueprintId, API_KEY, TEST_WORKSPACE, expectedStatus);
        }

        Stream<Arguments> retrieveDelta__whenError__thenReturnExpectedStatus() {
            return Stream.of(
                    arguments(UUID.randomUUID(), HttpStatus.SC_NOT_FOUND));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when retrieving blueprint by environment fails, then return expected error")
        void retrieveBlueprintByEnv__whenError__thenReturnExpectedStatus(
                String envName, UUID projectId, UUID maskId, int expectedStatus) {

            agentConfigsResourceClient.getBlueprintByEnv(envName, projectId, maskId, API_KEY, TEST_WORKSPACE,
                    expectedStatus);
        }

        Stream<Arguments> retrieveBlueprintByEnv__whenError__thenReturnExpectedStatus() {
            return Stream.of(
                    arguments("nonexistent", UUID.randomUUID(), null, HttpStatus.SC_NOT_FOUND),
                    arguments("dev", UUID.randomUUID(), null, HttpStatus.SC_NOT_FOUND),
                    arguments("nonexistent", UUID.randomUUID(), UUID.randomUUID(), HttpStatus.SC_NOT_FOUND));
        }

        @Test
        @DisplayName("when pinning environment to non-existing blueprint, then return 404")
        void createEnv__whenBlueprintDoesNotExist__thenReturn404() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var nonExistingBlueprintId = UUID.randomUUID();

            var envUpdate = AgentConfigEnvUpdate.builder()
                    .projectId(projectId)
                    .envs(List.of(
                            AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(nonExistingBlueprintId)
                                    .build()))
                    .build();

            agentConfigsResourceClient.createOrUpdateEnvs(envUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("when pinning environment to blueprint from different project, then return 404")
        void createEnv__whenBlueprintFromDifferentProject__thenReturn404() {
            var projectName1 = UUID.randomUUID().toString();
            var projectId1 = projectResourceClient.createProject(projectName1, API_KEY, TEST_WORKSPACE);

            var projectName2 = UUID.randomUUID().toString();
            var projectId2 = projectResourceClient.createProject(projectName2, API_KEY, TEST_WORKSPACE);

            var blueprint1 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Project 1 blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId1).blueprint(blueprint1).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var blueprint2 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Project 2 blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("claude").type(ValueType.STRING).build()))
                    .build();

            var blueprint2Id = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId2).blueprint(blueprint2).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var envUpdate = AgentConfigEnvUpdate.builder()
                    .projectId(projectId1)
                    .envs(List.of(
                            AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(blueprint2Id)
                                    .build()))
                    .build();

            agentConfigsResourceClient.createOrUpdateEnvs(envUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("when retrieving mask by ID, then return 404")
        void getBlueprintById__whenMask__thenReturn404() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Regular blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var mask = AgentBlueprint.builder()
                    .type(BlueprintType.MASK)
                    .description("Test mask")
                    .values(List.of(
                            AgentConfigValue.builder().key("temperature").value("0.8").type(ValueType.STRING).build()))
                    .build();

            var maskId = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(mask).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.getBlueprintById(maskId, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("when retrieving mask by environment, then return 404")
        void getBlueprintByEnv__whenMask__thenReturn404() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Regular blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var mask = AgentBlueprint.builder()
                    .type(BlueprintType.MASK)
                    .description("Test mask")
                    .values(List.of(
                            AgentConfigValue.builder().key("temperature").value("0.8").type(ValueType.STRING).build()))
                    .build();

            var maskId = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(mask).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var envUpdate = AgentConfigEnvUpdate.builder()
                    .projectId(projectId)
                    .envs(List.of(
                            AgentConfigEnv.builder()
                                    .envName("test-env")
                                    .blueprintId(maskId)
                                    .build()))
                    .build();

            agentConfigsResourceClient.createOrUpdateEnvs(envUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.getBlueprintByEnv("test-env", projectId, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Success: reassigning env to different blueprint closes old record and creates new one")
        void updateEnv__whenBlueprintChanged__thenCloseOldAndInsertNew() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint1 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("First blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var blueprint1Id = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint1).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var blueprint2 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Second blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("claude").type(ValueType.STRING).build()))
                    .build();

            var blueprint2Id = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint2).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.createOrUpdateEnvs(
                    AgentConfigEnvUpdate.builder()
                            .projectId(projectId)
                            .envs(List.of(AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(blueprint1Id)
                                    .build()))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            var result1 = agentConfigsResourceClient.getBlueprintByEnv("prod", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(result1.id()).isEqualTo(blueprint1Id);

            agentConfigsResourceClient.createOrUpdateEnvs(
                    AgentConfigEnvUpdate.builder()
                            .projectId(projectId)
                            .envs(List.of(AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(blueprint2Id)
                                    .build()))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            var result2 = agentConfigsResourceClient.getBlueprintByEnv("prod", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(result2.id()).isEqualTo(blueprint2Id);

            var historyPage = agentConfigsResourceClient.getHistory(projectId, 1, 10, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(historyPage.content()).hasSize(2);

            var expectedBlueprints = List.of(
                    AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("Second blueprint")
                            .envs(List.of("prod"))
                            .build(),
                    AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("First blueprint")
                            .envs(null)
                            .build());

            assertThat(historyPage.content())
                    .usingRecursiveComparison()
                    .ignoringFields(GetBlueprintHistory.BLUEPRINT_IGNORED_FIELDS)
                    .isEqualTo(expectedBlueprints);
        }

        @Test
        @DisplayName("Success: retrieve blueprint by name and set environment by blueprint name")
        void retrieveBlueprintByName__andSetEnvByName() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getBlueprintByName("v2", setup.projectId(), null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(blueprint.name()).isEqualTo("v2");
            assertThat(blueprint.values()).hasSize(6);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING)
                            .description("LLM model to use").build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build());

            assertConfigValues(expectedValues, blueprint.values());

            agentConfigsResourceClient.setEnvByBlueprintName("staging", setup.projectId(),
                    AgentConfigEnvSetByName.builder()
                            .blueprintName("v2")
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            var envBlueprint = agentConfigsResourceClient.getBlueprintByEnv("staging", setup.projectId(), null,
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(envBlueprint).isNotNull();
            assertThat(envBlueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(envBlueprint.name()).isEqualTo("v2");
            assertConfigValues(expectedValues, envBlueprint.values());
        }

        @Test
        @DisplayName("Success: retrieve blueprint by name with mask applied")
        void retrieveBlueprintByNameWithMask() {
            var setup = setupBlueprintsAndMask();

            var blueprint = agentConfigsResourceClient.getBlueprintByName("v2", setup.projectId(), setup.maskId(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(blueprint).isNotNull();
            assertThat(blueprint.id()).isEqualTo(setup.blueprint2Id());
            assertThat(blueprint.values()).hasSize(7);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("claude-3").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.5").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("stream").value("false").type(ValueType.BOOLEAN).build(),
                    AgentConfigValue.builder().key("system_prompt").value("prompt-content").type(ValueType.PROMPT)
                            .build(),
                    AgentConfigValue.builder().key("prompt_version").value("v1.0.0")
                            .type(ValueType.PROMPT_COMMIT).build(),
                    AgentConfigValue.builder().key("top_p").value("0.95").type(ValueType.FLOAT).build());

            assertConfigValues(expectedValues, blueprint.values());
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when retrieving blueprint by name fails, then return expected error")
        void retrieveBlueprintByName__whenError__thenReturnExpectedStatus(
                String name, UUID projectId, UUID maskId, int expectedStatus) {

            agentConfigsResourceClient.getBlueprintByName(name, projectId, maskId, API_KEY, TEST_WORKSPACE,
                    expectedStatus);
        }

        Stream<Arguments> retrieveBlueprintByName__whenError__thenReturnExpectedStatus() {
            return Stream.of(
                    arguments("nonexistent", UUID.randomUUID(), null, HttpStatus.SC_NOT_FOUND),
                    arguments("v1", UUID.randomUUID(), null, HttpStatus.SC_NOT_FOUND),
                    arguments("nonexistent", UUID.randomUUID(), UUID.randomUUID(), HttpStatus.SC_NOT_FOUND));
        }

        @Test
        @DisplayName("when setting env by non-existing blueprint name, then return 404")
        void setEnvByBlueprintName__whenBlueprintNotFound__thenReturn404() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .description("Test")
                                    .values(List.of(AgentConfigValue.builder()
                                            .key("model").value("gpt-4").type(ValueType.STRING).build()))
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.setEnvByBlueprintName("prod", projectId,
                    AgentConfigEnvSetByName.builder()
                            .blueprintName("nonexistent")
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("when request contains duplicate env names, then return 400")
        void createOrUpdateEnvs__whenDuplicateEnvNames__thenReturn400() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var envUpdate = AgentConfigEnvUpdate.builder()
                    .projectId(projectId)
                    .envs(List.of(
                            AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(UUID.randomUUID())
                                    .build(),
                            AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(UUID.randomUUID())
                                    .build()))
                    .build();

            agentConfigsResourceClient.createOrUpdateEnvs(envUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Get Blueprint History:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetBlueprintHistory {

        private static final String[] BLUEPRINT_IGNORED_FIELDS = new String[]{
                "id", "name", "projectId", "createdBy", "createdAt", "lastUpdatedBy", "lastUpdatedAt", "values"};

        @Test
        @DisplayName("Success: get paginated history with tagged blueprints and delta values, excludes masks")
        void getHistory() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint1 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Initial config")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint1).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var blueprint2 = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Update temp")
                    .values(List.of(
                            AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT)
                                    .build()))
                    .build();

            var blueprint2Id = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint2).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var mask = AgentBlueprint.builder()
                    .type(BlueprintType.MASK)
                    .description("This mask should NOT appear in history")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("claude").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(mask).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var envUpdate = AgentConfigEnvUpdate.builder()
                    .projectId(projectId)
                    .envs(List.of(
                            AgentConfigEnv.builder()
                                    .envName("prod")
                                    .blueprintId(blueprint2Id)
                                    .build()))
                    .build();

            agentConfigsResourceClient.createOrUpdateEnvs(envUpdate, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NO_CONTENT);

            var historyPage = agentConfigsResourceClient.getHistory(projectId, 1, 10, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);

            assertThat(historyPage).isNotNull();
            assertThat(historyPage.page()).isEqualTo(1);
            assertThat(historyPage.size()).isEqualTo(10);
            assertThat(historyPage.total()).isEqualTo(2);
            assertThat(historyPage.content()).hasSize(2);

            var expectedBlueprints = List.of(
                    AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("Update temp")
                            .envs(List.of("prod"))
                            .build(),
                    AgentBlueprint.builder()
                            .type(BlueprintType.BLUEPRINT)
                            .description("Initial config")
                            .envs(null)
                            .build());

            assertThat(historyPage.content())
                    .usingRecursiveComparison()
                    .ignoringFields(BLUEPRINT_IGNORED_FIELDS)
                    .isEqualTo(expectedBlueprints);

            assertConfigValues(
                    List.of(AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT).build()),
                    historyPage.content().getFirst().values());

            assertConfigValues(
                    List.of(AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()),
                    historyPage.content().get(1).values());
        }

        @Test
        @DisplayName("when config does not exist, then return 404")
        void getHistory__whenConfigNotFound__thenReturn404() {
            agentConfigsResourceClient.getHistory(UUID.randomUUID(), 1, 10, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Delete Environment:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteEnvironment {

        @Test
        @DisplayName("Success: delete existing environment, then getBlueprintByEnv returns 404")
        void deleteEnv__whenEnvExists__thenSoftDeleted() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var blueprintId = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.createOrUpdateEnvs(
                    AgentConfigEnvUpdate.builder()
                            .projectId(projectId)
                            .envs(List.of(AgentConfigEnv.builder()
                                    .envName("staging")
                                    .blueprintId(blueprintId)
                                    .build()))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.getBlueprintByEnv("staging", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            agentConfigsResourceClient.deleteEnv("staging", projectId, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.getBlueprintByEnv("staging", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Success: deleting non-existent environment returns 204 (idempotent)")
        void deleteEnv__whenEnvDoesNotExist__thenReturn204() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.deleteEnv("nonexistent", projectId, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("Success: deleting env does not affect other envs on same project")
        void deleteEnv__whenMultipleEnvs__thenOnlyTargetDeleted() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var blueprintId = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.createOrUpdateEnvs(
                    AgentConfigEnvUpdate.builder()
                            .projectId(projectId)
                            .envs(List.of(
                                    AgentConfigEnv.builder().envName("staging").blueprintId(blueprintId).build(),
                                    AgentConfigEnv.builder().envName("prod").blueprintId(blueprintId).build()))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.deleteEnv("staging", projectId, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.getBlueprintByEnv("staging", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);

            agentConfigsResourceClient.getBlueprintByEnv("prod", projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
        }

        @Test
        @DisplayName("Success: deleting already deleted env returns 204 (idempotent)")
        void deleteEnv__whenAlreadyDeleted__thenReturn204() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test blueprint")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build()))
                    .build();

            var blueprintId = agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.createOrUpdateEnvs(
                    AgentConfigEnvUpdate.builder()
                            .projectId(projectId)
                            .envs(List.of(AgentConfigEnv.builder()
                                    .envName("staging")
                                    .blueprintId(blueprintId)
                                    .build()))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.deleteEnv("staging", projectId, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            agentConfigsResourceClient.deleteEnv("staging", projectId, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);
        }
    }

    @Nested
    @DisplayName("Remove Config Keys:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RemoveConfigKeys {

        @Test
        @DisplayName("Success: should remove config values and create new blueprint")
        void removeConfigKeys() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var values = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("stream").value("true").type(ValueType.BOOLEAN).build());

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .values(values)
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var deleteBlueprintId = agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(Set.of("model", "temperature"))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            assertThat(deleteBlueprintId).isNotNull();

            var latest = agentConfigsResourceClient.getLatestBlueprint(projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(latest.values()).hasSize(1);
            assertThat(latest.values().getFirst().key()).isEqualTo("stream");
            assertThat(latest.values().getFirst().value()).isEqualTo("true");
            assertThat(latest.values().getFirst().type()).isEqualTo(ValueType.BOOLEAN);

            assertThat(latest.name()).isEqualTo("v2");
        }

        @Test
        @DisplayName("Success: should remove all config values")
        void removeConfigKeys__whenAllKeysRemoved() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var values = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT).build());

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .values(values)
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(Set.of("model", "temperature"))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var latest = agentConfigsResourceClient.getLatestBlueprint(projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(latest.values()).isEmpty();
            assertThat(latest.name()).isEqualTo("v2");
            assertThat(latest.description()).contains("Deleted configuration parameters");
        }

        @Test
        @DisplayName("when removing same keys twice, second remove returns 204 and no new blueprint is created")
        void removeConfigKeys__whenSameKeysTwice__thenSecondReturns204() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var values = List.of(
                    AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT).build());

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder()
                            .projectId(projectId)
                            .blueprint(AgentBlueprint.builder()
                                    .type(BlueprintType.BLUEPRINT)
                                    .values(values)
                                    .build())
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(Set.of("model"))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(Set.of("model"))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            var latest = agentConfigsResourceClient.getLatestBlueprint(projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(latest.name()).isEqualTo("v2");
        }

        @Test
        @DisplayName("when no config exists, then return 204 (idempotent)")
        void removeConfigKeys__whenNoConfig__thenReturn204() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(Set.of("model"))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("when keys is empty, then return 422")
        void removeConfigKeys__whenKeysEmpty__thenReturn422() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(Set.of(""))
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("when keys is null, then return 422")
        void removeConfigKeys__whenKeysNull__thenReturn422() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            agentConfigsResourceClient.removeConfigKeys(
                    AgentConfigRemoveValues.builder()
                            .projectId(projectId)
                            .keys(null)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Nested
    @DisplayName("Create Blueprint From Mask:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateBlueprintFromMask {

        @Test
        @DisplayName("Success: should create new blueprint from mask changes")
        void createBlueprintFromMask() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var blueprint = AgentBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Initial config")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("gpt-4").type(ValueType.STRING).build(),
                            AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT).build(),
                            AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build()))
                    .build();

            agentConfigsResourceClient.createAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(blueprint).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var mask = AgentBlueprint.builder()
                    .type(BlueprintType.MASK)
                    .description("Override model and add top_p")
                    .values(List.of(
                            AgentConfigValue.builder().key("model").value("claude-3").type(ValueType.STRING).build(),
                            AgentConfigValue.builder().key("top_p").value("0.95").type(ValueType.FLOAT).build()))
                    .build();

            var maskId = agentConfigsResourceClient.updateAgentConfig(
                    AgentConfigCreate.builder().projectId(projectId).blueprint(mask).build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            var newBlueprintId = agentConfigsResourceClient.createBlueprintFromMask(
                    projectId, maskId, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            assertThat(newBlueprintId).isNotNull();

            var newBlueprint = agentConfigsResourceClient.getBlueprintById(newBlueprintId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            var expectedValues = List.of(
                    AgentConfigValue.builder().key("model").value("claude-3").type(ValueType.STRING).build(),
                    AgentConfigValue.builder().key("temperature").value("0.7").type(ValueType.FLOAT).build(),
                    AgentConfigValue.builder().key("max_tokens").value("1024").type(ValueType.INTEGER).build(),
                    AgentConfigValue.builder().key("top_p").value("0.95").type(ValueType.FLOAT).build());

            var expectedBlueprint = AgentBlueprint.builder()
                    .id(newBlueprintId)
                    .type(BlueprintType.BLUEPRINT)
                    .name("v2")
                    .description("Override model and add top_p")
                    .values(expectedValues)
                    .projectId(projectId)
                    .build();

            assertThat(newBlueprint)
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .ignoringFields(BLUEPRINT_PAYLOAD_IGNORED_FIELDS)
                    .isEqualTo(expectedBlueprint);

            var latestWithInheritance = agentConfigsResourceClient.getLatestBlueprint(projectId, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(latestWithInheritance)
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .ignoringFields(BLUEPRINT_PAYLOAD_IGNORED_FIELDS)
                    .isEqualTo(expectedBlueprint);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("Error: should return 404 when mask or config not found")
        void createBlueprintFromMask__whenNotFound__thenReturn404(boolean createConfig) {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            if (createConfig) {
                agentConfigsResourceClient.createAgentConfig(
                        AgentConfigCreate.builder()
                                .projectId(projectId)
                                .blueprint(AgentBlueprint.builder()
                                        .type(BlueprintType.BLUEPRINT)
                                        .description("Test")
                                        .values(List.of(AgentConfigValue.builder()
                                                .key("model").value("gpt-4").type(ValueType.STRING).build()))
                                        .build())
                                .build(),
                        API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);
            }

            agentConfigsResourceClient.createBlueprintFromMask(
                    projectId, UUID.randomUUID(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        Stream<Arguments> createBlueprintFromMask__whenNotFound__thenReturn404() {
            return Stream.of(
                    arguments(true),
                    arguments(false));
        }
    }
}
