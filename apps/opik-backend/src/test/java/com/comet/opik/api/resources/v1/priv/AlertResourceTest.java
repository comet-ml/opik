package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.tuple.Pair;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Alerts Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AlertResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final String[] ALERT_IGNORED_FIELDS = new String[]{
            "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "webhook.name", "webhook.createdAt", "webhook.lastUpdatedAt",
            "webhook.createdBy", "webhook.lastUpdatedBy", "triggers"};

    private static final String[] TRIGGER_IGNORED_FIELDS = new String[]{
            "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "triggerConfigs"};

    private static final String[] TRIGGER_CONFIG_IGNORED_FIELDS = new String[]{
            "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

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

    private AlertResourceClient alertResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.alertResourceClient = new AlertResourceClient(client);

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
    @DisplayName("Create Alert:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateAlert {

        @Test
        @DisplayName("Success: should create alert with all fields")
        void createAlert() {
            var mock = prepareMockWorkspace();

            var alert = factory.manufacturePojo(Alert.class);

            var alertId = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            assertThat(alertId).isNotNull();
            assertThat(alertId.version()).isEqualTo(7);
        }

        @Test
        @DisplayName("Success: should create alert with minimal required fields")
        void createAlert__whenMinimalFields__thenCreateAlert() {
            var mock = prepareMockWorkspace();

            var alert = Alert.builder()
                    .name("Test Alert: " + UUID.randomUUID())
                    .webhook(factory.manufacturePojo(Webhook.class))
                    .build();

            var alertId = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            assertThat(alertId).isNotNull();
            assertThat(alertId.version()).isEqualTo(7);
        }

        @Test
        @DisplayName("Success: should create alert with enabled null (defaults to true)")
        void createAlert__whenEnabledIsNull__thenCreateAlert() {
            var mock = prepareMockWorkspace();

            var alert = factory.manufacturePojo(Alert.class).toBuilder()
                    .enabled(null)
                    .build();

            var alertId = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            assertThat(alertId).isNotNull();
            assertThat(alertId.version()).isEqualTo(7);
        }

        @Test
        @DisplayName("when alert id already exists, then return conflict")
        void createAlert__whenAlertIdAlreadyExists__thenReturnConflict() {
            var mock = prepareMockWorkspace();

            var alert = factory.manufacturePojo(Alert.class);

            // Create first alert
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Try to create alert with same name
            try (var actualResponse = alertResourceClient.createAlertWithResponse(alert, mock.getLeft(),
                    mock.getRight())) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CONFLICT);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                        .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_CONFLICT,
                                "Alert already exists"));
            }
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when alert is invalid, then return validation error")
        void createAlert__whenAlertIsInvalid__thenReturnValidationError(Alert alert, int expectedStatusCode,
                Object expectedBody, Class<?> expectedResponseClass) {

            var mock = prepareMockWorkspace();

            try (var actualResponse = alertResourceClient.createAlertWithResponse(alert, mock.getLeft(),
                    mock.getRight())) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatusCode);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualBody = actualResponse.readEntity(expectedResponseClass);
                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> createAlert__whenAlertIsInvalid__thenReturnValidationError() {
            return Stream.of(
                    // Name validation
                    arguments(
                            factory.manufacturePojo(Alert.class).toBuilder().name(null).build(),
                            422,
                            new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            factory.manufacturePojo(Alert.class).toBuilder().name("").build(),
                            422,
                            new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            factory.manufacturePojo(Alert.class).toBuilder().name("   ").build(),
                            422,
                            new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            factory.manufacturePojo(Alert.class).toBuilder().name("a".repeat(256)).build(),
                            422,
                            new ErrorMessage(List.of("name size must be between 0 and 255")),
                            ErrorMessage.class),

                    // WebhookId validation
                    arguments(
                            factory.manufacturePojo(Alert.class).toBuilder().webhook(null).build(),
                            422,
                            new ErrorMessage(List.of("webhook must not be null")),
                            ErrorMessage.class),

                    // Invalid UUID version
                    arguments(
                            factory.manufacturePojo(Alert.class).toBuilder().id(UUID.randomUUID()).build(),
                            HttpStatus.SC_BAD_REQUEST,
                            new ErrorMessage(List.of("Alert id must be a version 7 UUID")),
                            ErrorMessage.class));
        }

        @Test
        @DisplayName("when malformed JSON, then return bad request")
        void createAlert__whenMalformedJson__thenReturnBadRequest() {
            var mock = prepareMockWorkspace();

            String malformedJson = "{ \"name\": \"test\", \"invalid\": }";

            try (var actualResponse = alertResourceClient.createAlertWithResponse(malformedJson, mock.getLeft(),
                    mock.getRight())) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                assertThat(actualResponse.hasEntity()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Get Alert By ID:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetAlertById {

        @Test
        @DisplayName("Success: should get alert by id")
        void getAlertById() {
            var mock = prepareMockWorkspace();

            // Create an alert first
            var alert = factory.manufacturePojo(Alert.class);
            var createdAlertId = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            var actualAlert = alertResourceClient.getAlertById(createdAlertId, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_OK);

            compareAlerts(alert, actualAlert);
        }

        @Test
        @DisplayName("when alert does not exist, then return not found")
        void getAlertById__whenAlertDoesNotExist__thenReturnNotFound() {
            var mock = prepareMockWorkspace();

            UUID nonExistentId = UUID.randomUUID();

            alertResourceClient.getAlertById(nonExistentId, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NOT_FOUND);
        }
    }

    private Pair<String, String> prepareMockWorkspace() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        return Pair.of(apiKey, workspaceName);
    }

    private void compareAlerts(Alert expected, Alert actual) {
        var preparedExpected = prepareForComparison(expected, true);
        var preparedActual = prepareForComparison(actual, false);

        assertThat(preparedActual)
                .usingRecursiveComparison()
                .ignoringFields(ALERT_IGNORED_FIELDS)
                .ignoringCollectionOrder()
                .isEqualTo(preparedExpected);

        assertThat(preparedActual.triggers())
                .usingRecursiveComparison()
                .ignoringFields(TRIGGER_IGNORED_FIELDS)
                .ignoringCollectionOrder()
                .isEqualTo(preparedExpected.triggers());

        for (int i = 0; i < preparedActual.triggers().size(); i++) {
            var actualTrigger = preparedActual.triggers().get(i);
            var expectedTrigger = preparedExpected.triggers().get(i);

            assertThat(actualTrigger.triggerConfigs())
                    .usingRecursiveComparison()
                    .ignoringFields(TRIGGER_CONFIG_IGNORED_FIELDS)
                    .ignoringCollectionOrder()
                    .isEqualTo(expectedTrigger.triggerConfigs());
        }
    }

    private Alert prepareForComparison(Alert alert, boolean isExpected) {
        var sortedTriggers = alert.triggers().stream()
                .map(trigger -> {
                    var configs = trigger.triggerConfigs().stream()
                            .map(config -> config.toBuilder()
                                    .alertTriggerId(isExpected ? trigger.id() : config.alertTriggerId())
                                    .build())
                            .toList();
                    return trigger.toBuilder()
                            .triggerConfigs(configs)
                            .alertId(isExpected ? alert.id() : trigger.alertId())
                            .build();
                })
                .sorted(Comparator.comparing(AlertTrigger::id))
                .toList();

        return alert.toBuilder()
                .triggers(sortedTriggers)
                .build();
    }
}
