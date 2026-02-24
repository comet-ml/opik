package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.OptimizerConfigCreate;
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
import com.comet.opik.api.resources.utils.resources.OptimizerConfigResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.OptimizerBlueprint;
import com.comet.opik.domain.OptimizerBlueprint.BlueprintType;
import com.comet.opik.domain.OptimizerConfigValue;
import com.comet.opik.domain.OptimizerConfigValue.ValueType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
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
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Optimizer Config Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizerConfigResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

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

    private OptimizerConfigResourceClient optimizerConfigResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        String baseUrl = TestUtils.getBaseUrl(client);
        this.optimizerConfigResourceClient = new OptimizerConfigResourceClient(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);

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
    class CreateOptimizerConfig {

        @Test
        @DisplayName("Success: should create optimizer config with blueprint")
        void createOptimizerConfig() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var values = List.of(
                    OptimizerConfigValue.builder()
                            .key("model")
                            .value("gpt-4")
                            .type(ValueType.STRING)
                            .build(),
                    OptimizerConfigValue.builder()
                            .key("temperature")
                            .value("0.7")
                            .type(ValueType.NUMBER)
                            .build());

            var blueprint = OptimizerBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Initial configuration")
                    .values(values)
                    .build();

            var request = OptimizerConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(blueprint)
                    .build();

            optimizerConfigResourceClient.createOptimizerConfig(request, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_CREATED);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when request is invalid, then return validation error")
        void createOptimizerConfig__whenRequestIsInvalid__thenReturnValidationError(
                OptimizerConfigCreate request, int expectedStatusCode, Object expectedBody,
                Class<?> expectedResponseClass) {

            try (var actualResponse = optimizerConfigResourceClient.createOptimizerConfigWithResponse(request, API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatusCode);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualBody = actualResponse.readEntity(expectedResponseClass);
                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> createOptimizerConfig__whenRequestIsInvalid__thenReturnValidationError() {
            var projectName = UUID.randomUUID().toString();
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var validValues = List.of(
                    OptimizerConfigValue.builder()
                            .key("model")
                            .value("gpt-4")
                            .type(ValueType.STRING)
                            .build());

            var validBlueprint = OptimizerBlueprint.builder()
                    .type(BlueprintType.BLUEPRINT)
                    .description("Test")
                    .values(validValues)
                    .build();

            return Stream.of(
                    // Blueprint validation
                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(null)
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint must not be null")),
                            ErrorMessage.class),

                    // Blueprint type validation
                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder().type(null).build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.type must not be null")),
                            ErrorMessage.class),

                    // Values validation
                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder().values(null).build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values must not be empty")),
                            ErrorMessage.class),

                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder().values(List.of()).build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values must not be empty")),
                            ErrorMessage.class),

                    // Value key validation
                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    OptimizerConfigValue.builder()
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
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    OptimizerConfigValue.builder()
                                                            .key("")
                                                            .value("test")
                                                            .type(ValueType.STRING)
                                                            .build()))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values[0].key must not be blank")),
                            ErrorMessage.class),

                    // Value value validation
                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    OptimizerConfigValue.builder()
                                                            .key("test")
                                                            .value(null)
                                                            .type(ValueType.STRING)
                                                            .build()))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.values[0].value must not be blank")),
                            ErrorMessage.class),

                    // Value type validation
                    arguments(
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .values(List.of(
                                                    OptimizerConfigValue.builder()
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
                            OptimizerConfigCreate.builder()
                                    .projectId(projectId)
                                    .blueprint(validBlueprint.toBuilder()
                                            .description("a".repeat(256))
                                            .build())
                                    .build(),
                            422,
                            new ErrorMessage(List.of("blueprint.description cannot exceed 255 characters")),
                            ErrorMessage.class));
        }
    }
}
