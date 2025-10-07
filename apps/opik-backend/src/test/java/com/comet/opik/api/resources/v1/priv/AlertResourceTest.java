package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.WebhookTestResult;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.filter.AlertField;
import com.comet.opik.api.filter.AlertFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.core5.http.HttpStatus;
import org.eclipse.jetty.http.HttpHeader;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

            var alert = generateAlert();

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

            var alert = generateAlert();

            // Create first alert
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Try to create alert with same id
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
            var alert = generateAlert();
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

    @Nested
    @DisplayName("Update Alert:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateAlert {

        @Test
        @DisplayName("Success: should update alert with all fields")
        void updateAlert() {
            var mock = prepareMockWorkspace();

            // Create an alert first
            var originalAlert = generateAlert();
            var createdAlertId = alertResourceClient.createAlert(originalAlert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            var actualAlert = alertResourceClient.getAlertById(createdAlertId, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_OK);

            var updatedAlert = generateAlertUpdate(actualAlert);

            alertResourceClient.updateAlert(createdAlertId, updatedAlert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NO_CONTENT);

            // Verify the update
            var actualUpdatedAlert = alertResourceClient.getAlertById(createdAlertId, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_OK);

            compareAlerts(updatedAlert, actualUpdatedAlert);
        }

        @Test
        @DisplayName("when alert does not exist, then return not found")
        void updateAlert__whenAlertDoesNotExist__thenReturnNotFound() {
            var mock = prepareMockWorkspace();

            UUID nonExistentId = UUID.randomUUID();
            var alert = generateAlert().toBuilder()
                    .id(nonExistentId)
                    .build();

            alertResourceClient.updateAlert(nonExistentId, alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Find Alerts:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindAlerts {

        @Test
        @DisplayName("Success: should find alerts")
        void findAlerts() {
            var mock = prepareMockWorkspace();

            var alert = generateAlert();
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            List<Alert> expectedAlerts = List.of(alert);

            findAlertsAndAssertPage(expectedAlerts, mock.getLeft(), mock.getRight(), expectedAlerts.size(), 1, null,
                    null);
        }

        @Test
        @DisplayName("when fetch alerts, then return alerts sorted by creation time")
        void findAlerts__whenFetchAlerts__thenReturnAlertsSortedByCreationTime() {
            var mock = prepareMockWorkspace();

            var alerts = PodamFactoryUtils.manufacturePojoList(factory, Alert.class).stream()
                    .map(alert -> generateAlert())
                    .toList();

            alerts.forEach(alert -> alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED));

            List<Alert> expectedAlerts = alerts.reversed();

            findAlertsAndAssertPage(expectedAlerts, mock.getLeft(), mock.getRight(), expectedAlerts.size(), 1, null,
                    null);
        }

        @Test
        @DisplayName("when fetch alerts using pagination, then return alerts paginated")
        void findAlerts__whenFetchAlertsUsingPagination__thenReturnAlertsPaginated() {
            var mock = prepareMockWorkspace();

            var alerts = IntStream.range(0, 20)
                    .mapToObj(i -> generateAlert())
                    .toList();

            alerts.forEach(alert -> alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED));

            List<Alert> alertPage1 = alerts.reversed().subList(0, 10);
            List<Alert> alertPage2 = alerts.reversed().subList(10, 20);

            findAlertsAndAssertPage(alertPage1, mock.getLeft(), mock.getRight(), alerts.size(), 1, null, null);
            findAlertsAndAssertPage(alertPage2, mock.getLeft(), mock.getRight(), alerts.size(), 2, null, null);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when sorting alerts by valid fields, then return sorted alerts")
        void findAlerts__whenSortingByValidFields__thenReturnAlertsSorted(Comparator<Alert> comparator,
                SortingField sorting) {
            var mock = prepareMockWorkspace();

            List<Alert> expectedAlerts = IntStream.range(0, 5)
                    .mapToObj(i -> generateAlert())
                    .map(alert -> {
                        var id = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                                HttpStatus.SC_CREATED);
                        return alertResourceClient.getAlertById(id, mock.getLeft(), mock.getRight(), HttpStatus.SC_OK);
                    })
                    .sorted(comparator)
                    .toList();

            findAlertsAndAssertPage(expectedAlerts, mock.getLeft(), mock.getRight(), expectedAlerts.size(), 1,
                    List.of(sorting), null);
        }

        private Stream<Arguments> findAlerts__whenSortingByValidFields__thenReturnAlertsSorted() {
            // Comparators for all sortable fields
            Comparator<Alert> idComparator = Comparator.comparing(Alert::id);
            Comparator<Alert> nameComparator = Comparator.comparing(Alert::name, String.CASE_INSENSITIVE_ORDER);
            Comparator<Alert> createdAtComparator = Comparator.comparing(Alert::createdAt);
            Comparator<Alert> lastUpdatedAtComparator = Comparator.comparing(Alert::lastUpdatedAt);
            Comparator<Alert> createdByComparator = Comparator.comparing(Alert::createdBy,
                    String.CASE_INSENSITIVE_ORDER);
            Comparator<Alert> lastUpdatedByComparator = Comparator.comparing(Alert::lastUpdatedBy,
                    String.CASE_INSENSITIVE_ORDER);
            Comparator<Alert> webhookUrlComparator = Comparator
                    .comparing(alert -> alert.webhook().url(), String.CASE_INSENSITIVE_ORDER);
            Comparator<Alert> webhookSecretTokenComparator = Comparator
                    .comparing(alert -> alert.webhook().secretToken(), String.CASE_INSENSITIVE_ORDER);

            return Stream.of(
                    // ID field sorting
                    Arguments.of(
                            idComparator,
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(
                            idComparator.reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),

                    // NAME field sorting
                    Arguments.of(
                            nameComparator,
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    Arguments.of(
                            nameComparator.reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),

                    // CREATED_AT field sorting
                    Arguments.of(
                            createdAtComparator,
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    Arguments.of(
                            createdAtComparator.reversed(),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),

                    // LAST_UPDATED_AT field sorting
                    Arguments.of(
                            lastUpdatedAtComparator,
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            lastUpdatedAtComparator.reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()),

                    // CREATED_BY field sorting
                    Arguments.of(
                            createdByComparator.thenComparing(Alert::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                    Arguments.of(
                            createdByComparator.reversed().thenComparing(Alert::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),

                    // LAST_UPDATED_BY field sorting
                    Arguments.of(
                            lastUpdatedByComparator.thenComparing(Alert::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            lastUpdatedByComparator.reversed().thenComparing(Alert::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.DESC)
                                    .build()),

                    // WEBHOOK_URL field sorting
                    Arguments.of(
                            webhookUrlComparator,
                            SortingField.builder().field(SortableFields.WEBHOOK_URL).direction(Direction.ASC).build()),
                    Arguments.of(
                            webhookUrlComparator.reversed(),
                            SortingField.builder().field(SortableFields.WEBHOOK_URL).direction(Direction.DESC).build()),

                    // WEBHOOK_SECRET_TOKEN field sorting
                    Arguments.of(
                            webhookSecretTokenComparator,
                            SortingField.builder().field(SortableFields.WEBHOOK_SECRET_TOKEN).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            webhookSecretTokenComparator.reversed(),
                            SortingField.builder().field(SortableFields.WEBHOOK_SECRET_TOKEN).direction(Direction.DESC)
                                    .build()));
        }

        @ParameterizedTest
        @MethodSource("getValidFilters")
        @DisplayName("when filter alerts by valid fields, then return filtered alerts")
        void findAlerts__whenFilterAlertsByValidFields__thenReturnFilteredAlerts(
                Function<List<Alert>, AlertFilter> getFilter,
                Function<List<Alert>, List<Alert>> getExpectedAlerts) {
            var mock = prepareMockWorkspace();

            var alerts = IntStream.range(0, 5)
                    .mapToObj(i -> generateAlert())
                    .map(alert -> {
                        var id = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                                HttpStatus.SC_CREATED);
                        return alertResourceClient.getAlertById(id, mock.getLeft(), mock.getRight(), HttpStatus.SC_OK);
                    })
                    .toList();

            List<Alert> expectedAlerts = getExpectedAlerts.apply(alerts);
            AlertFilter filter = getFilter.apply(alerts);

            findAlertsAndAssertPage(expectedAlerts.reversed(), mock.getLeft(), mock.getRight(), expectedAlerts.size(),
                    1, null, List.of(filter));
        }

        private Stream<Arguments> getValidFilters() {
            return Stream.of(
                    // ID field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.ID)
                                    .operator(Operator.EQUAL)
                                    .value(alerts.getFirst().id().toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.ID)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(alerts.getFirst().id().toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts.subList(1, alerts.size())),

                    // NAME field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.NAME)
                                    .operator(Operator.EQUAL)
                                    .value(alerts.getFirst().name())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.NAME)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(alerts.getFirst().name())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts.subList(1, alerts.size())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.NAME)
                                    .operator(Operator.STARTS_WITH)
                                    .value(alerts.getFirst().name().substring(0, 3))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.NAME)
                                    .operator(Operator.ENDS_WITH)
                                    .value(alerts.getFirst().name().substring(3))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.NAME)
                                    .operator(Operator.CONTAINS)
                                    .value(alerts.getFirst().name().substring(2, 5))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),

                    // WEBHOOK_URL field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.WEBHOOK_URL)
                                    .operator(Operator.EQUAL)
                                    .value(alerts.getFirst().webhook().url())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.WEBHOOK_URL)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(alerts.getFirst().webhook().url())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts.subList(1, alerts.size())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.WEBHOOK_URL)
                                    .operator(Operator.CONTAINS)
                                    .value(alerts.getFirst().webhook().url().substring(5, 10))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),

                    // WEBHOOK_SECRET_TOKEN field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.WEBHOOK_SECRET_TOKEN)
                                    .operator(Operator.EQUAL)
                                    .value(alerts.getFirst().webhook().secretToken())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.WEBHOOK_SECRET_TOKEN)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(alerts.getFirst().webhook().secretToken())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts.subList(1, alerts.size())),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.WEBHOOK_SECRET_TOKEN)
                                    .operator(Operator.CONTAINS)
                                    .value(alerts.getFirst().webhook().secretToken().substring(2, 5))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of(alerts.getFirst())),

                    // CREATED_BY field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.CREATED_BY)
                                    .operator(Operator.EQUAL)
                                    .value(USER)
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.CREATED_BY)
                                    .operator(Operator.STARTS_WITH)
                                    .value(USER.substring(0, 3))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),

                    // LAST_UPDATED_BY field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.LAST_UPDATED_BY)
                                    .operator(Operator.NOT_EQUAL)
                                    .value("non-existent-user")
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.LAST_UPDATED_BY)
                                    .operator(Operator.CONTAINS)
                                    .value(USER.substring(0, 3))
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),

                    // CREATED_AT field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.CREATED_AT)
                                    .operator(Operator.GREATER_THAN)
                                    .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.CREATED_AT)
                                    .operator(Operator.LESS_THAN)
                                    .value(Instant.now().plus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.CREATED_AT)
                                    .operator(Operator.GREATER_THAN)
                                    .value(Instant.now().plus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of()),

                    // LAST_UPDATED_AT field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.LAST_UPDATED_AT)
                                    .operator(Operator.GREATER_THAN_EQUAL)
                                    .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.LAST_UPDATED_AT)
                                    .operator(Operator.GREATER_THAN_EQUAL)
                                    .value(Instant.now().plus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> List.of()),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.LAST_UPDATED_AT)
                                    .operator(Operator.LESS_THAN_EQUAL)
                                    .value(Instant.now().plus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts));
        }
    }

    @Nested
    @DisplayName("Delete Alert Batch:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteAlertBatch {

        @Test
        @DisplayName("Success: should delete multiple alerts")
        void deleteAlertBatch__whenMultipleAlerts__thenDeleteSuccessfully() {
            var mock = prepareMockWorkspace();

            // Create multiple alerts
            var alert1 = factory.manufacturePojo(Alert.class);
            var alert2 = factory.manufacturePojo(Alert.class);
            var createdAlertId1 = alertResourceClient.createAlert(alert1, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);
            var createdAlertId2 = alertResourceClient.createAlert(alert2, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Delete both alerts
            var batchDelete = BatchDelete.builder()
                    .ids(Set.of(createdAlertId1, createdAlertId2))
                    .build();

            alertResourceClient.deleteAlertBatch(batchDelete, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NO_CONTENT);

            // Verify both alerts are deleted
            alertResourceClient.getAlertById(createdAlertId1, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NOT_FOUND);
            alertResourceClient.getAlertById(createdAlertId2, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Success: should fail empty list")
        void deleteAlertBatch__whenEmptyList__thenReturnValidationError() {
            var mock = prepareMockWorkspace();

            var batchDelete = BatchDelete.builder()
                    .ids(Set.of())
                    .build();

            alertResourceClient.deleteAlertBatch(batchDelete, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("Success: should handle non-existent alert IDs gracefully")
        void deleteAlertBatch__whenNonExistentIds__thenReturnNoContent() {
            var mock = prepareMockWorkspace();

            var batchDelete = BatchDelete.builder()
                    .ids(Set.of(UUID.randomUUID(), UUID.randomUUID()))
                    .build();

            alertResourceClient.deleteAlertBatch(batchDelete, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_NO_CONTENT);
        }
    }

    @Nested
    @DisplayName("Test Webhook:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TestWebhook {

        private WireMockServer externalWebhookServer;
        private static final String WEBHOOK_PATH = "/webhook";

        @BeforeAll
        void setUpAll() {
            externalWebhookServer = new WireMockServer(0);
            externalWebhookServer.start();
        }

        @AfterAll
        void tearDownAll() {
            if (externalWebhookServer != null) {
                externalWebhookServer.stop();
            }
        }

        @BeforeEach
        void setUp() {
            externalWebhookServer.resetAll();
        }

        @Test
        @DisplayName("Success: should test webhook successfully when webhook server responds with 2xx")
        void testWebhook__whenWebhookServerRespondsWithSuccess__thenReturnSuccessResult() {
            // Given
            var mock = prepareMockWorkspace();
            var webhookUrl = "http://localhost:" + externalWebhookServer.port() + WEBHOOK_PATH;

            // Setup WireMock to return successful response
            externalWebhookServer.stubFor(post(urlEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader(HttpHeader.CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON)
                            .withBody("{\"status\":\"success\"}")));

            // Create alert with webhook
            var alert = generateAlert();
            var webhook = alert.webhook().toBuilder()
                    .url(webhookUrl)
                    .build();
            alert = alert.toBuilder()
                    .webhook(webhook)
                    .build();

            // When
            var result = alertResourceClient.testWebhook(alert, mock.getLeft(), mock.getRight());
            assertThat(result.status()).isEqualTo(WebhookTestResult.Status.SUCCESS);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.errorMessage()).isNull();

            assertWebhookTestResultRequest(alert, result.requestBody());

            // Verify HTTP call was made
            externalWebhookServer.verify(postRequestedFor(urlEqualTo(WEBHOOK_PATH))
                    .withHeader(HttpHeader.CONTENT_TYPE.toString(), equalTo(MediaType.APPLICATION_JSON)));
        }

        @Test
        @DisplayName("Success: should test webhook and return failure when webhook server responds with non-2xx")
        void testWebhook__whenWebhookServerRespondsWithError__thenReturnFailureResult() {
            // Given
            var mock = prepareMockWorkspace();
            var webhookUrl = "http://localhost:" + externalWebhookServer.port() + WEBHOOK_PATH;

            // Setup WireMock to return error response
            externalWebhookServer.stubFor(post(urlEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("Internal Server Error")));

            // Create alert with webhook
            var alert = generateAlert();
            var webhook = alert.webhook().toBuilder()
                    .url(webhookUrl)
                    .build();
            alert = alert.toBuilder()
                    .webhook(webhook)
                    .build();

            // When
            var result = alertResourceClient.testWebhook(alert, mock.getLeft(), mock.getRight());
            assertThat(result.status()).isEqualTo(WebhookTestResult.Status.FAILURE);
            assertThat(result.statusCode()).isEqualTo(500);
            assertThat(result.errorMessage()).isNotNull();

            assertWebhookTestResultRequest(alert, result.requestBody());

            // Verify HTTP call was made
            externalWebhookServer.verify(postRequestedFor(urlEqualTo(WEBHOOK_PATH))
                    .withHeader(HttpHeader.CONTENT_TYPE.toString(), equalTo(MediaType.APPLICATION_JSON)));
        }

        private void assertWebhookTestResultRequest(Alert alert, String testResultRequestBody) {
            WebhookEvent<?> actualEvent = JsonUtils.readValue(testResultRequestBody, WebhookEvent.class);

            // Verify the webhook event is not null
            assertThat(actualEvent).isNotNull();

            // Verify event metadata
            assertThat(actualEvent.getId()).isNotNull();
            assertThat(actualEvent.getUrl()).isEqualTo(alert.webhook().url());
            assertThat(actualEvent.getAlertId()).isEqualTo(alert.id());
            assertThat(actualEvent.getCreatedAt()).isNotNull();
            assertThat(actualEvent.getMaxRetries()).isEqualTo(1);

            // Verify headers
            var expectedHeaders = Optional.ofNullable(alert.webhook().headers()).orElse(Map.of());
            assertThat(actualEvent.getHeaders()).isEqualTo(expectedHeaders);
            assertThat(actualEvent.getEventType()).isEqualTo(alert.triggers().getFirst().eventType());

            // Verify payload
            Map<String, Object> payload = (Map<String, Object>) actualEvent.getPayload();

            // Verify payload fields
            assertThat(payload.get("alertId")).isEqualTo(alert.id().toString());
            assertThat(payload.get("alertName")).isEqualTo(alert.name());
            assertThat(payload.get("eventType")).isEqualTo(alert.triggers().getFirst().eventType().getValue());
            assertThat(payload.get("aggregationType")).isEqualTo("consolidated");

            // Verify eventIds
            var eventIds = (Collection<String>) payload.get("eventIds");
            assertThat(eventIds).hasSize(1);

            // Verify eventCount
            assertThat(payload.get("eventCount")).isEqualTo(1);

            // Verify message format
            assertThat(payload.get("message").toString()).isEqualTo(String.format("Alert '%s': %d %s events aggregated",
                    alert.name(), eventIds.size(), alert.triggers().getFirst().eventType().getValue()));
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

    private Alert generateAlert() {
        var alert = factory.manufacturePojo(Alert.class);

        var webhook = alert.webhook().toBuilder()
                .createdBy(null)
                .createdAt(null)
                .build();

        var triggers = alert.triggers().stream()
                .map(trigger -> {
                    var configs = trigger.triggerConfigs().stream()
                            .map(config -> config.toBuilder()
                                    .createdBy(null)
                                    .createdAt(null)
                                    .build())
                            .toList();
                    return trigger.toBuilder()
                            .triggerConfigs(configs)
                            .createdBy(null)
                            .createdAt(null)
                            .build();
                })
                .toList();

        return alert.toBuilder()
                .webhook(webhook)
                .createdBy(null)
                .createdAt(null)
                .triggers(triggers)
                .build();
    }

    private Alert generateAlertUpdate(Alert existingAlert) {
        var alert = generateAlert();

        var webhook = alert.webhook().toBuilder()
                .id(existingAlert.webhook().id())
                .build();

        // add one new trigger, update one existing trigger, keep one existing trigger unchanged
        var unchangedTrigger = existingAlert.triggers().get(0);
        var newTrigger = alert.triggers().get(0);
        var updatedTrigger = generateAlertTriggerUpdate(existingAlert.triggers().get(1), alert.triggers().get(1));

        return alert.toBuilder()
                .id(existingAlert.id())
                .webhook(webhook)
                .triggers(List.of(unchangedTrigger, newTrigger, updatedTrigger))
                .build();
    }

    private AlertTrigger generateAlertTriggerUpdate(AlertTrigger existingTrigger, AlertTrigger updatedTrigger) {
        // add one new config, update one existing config, keep one existing config unchanged

        var unchangedConfig = existingTrigger.triggerConfigs().get(0);
        var newConfig = updatedTrigger.triggerConfigs().get(0);

        var updatedConfigs = updatedTrigger.triggerConfigs().get(1).toBuilder()
                .id(existingTrigger.triggerConfigs().get(1).id())
                .build();

        return updatedTrigger.toBuilder()
                .id(existingTrigger.id())
                .alertId(existingTrigger.alertId())
                .triggerConfigs(List.of(unchangedConfig, newConfig, updatedConfigs))
                .build();
    }

    private void findAlertsAndAssertPage(List<Alert> expectedAlerts, String apiKey, String workspaceName,
            int expectedTotal, int page, List<SortingField> sortingFields, List<AlertFilter> filters) {

        // Always add size parameter - default to expectedAlerts.size() if not 0
        int size = expectedAlerts.isEmpty() ? 10 : expectedAlerts.size();

        var alertPage = alertResourceClient.findAlerts(apiKey, workspaceName, page, size, sortingFields, filters,
                HttpStatus.SC_OK);

        assertThat(alertPage.total()).isEqualTo(expectedTotal);
        assertThat(alertPage.content()).hasSize(expectedAlerts.size());
        assertThat(alertPage.page()).isEqualTo(page);
        assertThat(alertPage.size()).isEqualTo(expectedAlerts.size());

        assertSortableFields(alertPage);

        for (int i = 0; i < alertPage.content().size(); i++) {
            compareAlerts(expectedAlerts.get(i), alertPage.content().get(i));
        }
    }

    private static void assertSortableFields(Alert.AlertPage alertPage) {
        assertThat(alertPage.sortableBy()).contains(
                SortableFields.ID,
                SortableFields.NAME,
                SortableFields.CREATED_AT,
                SortableFields.LAST_UPDATED_AT,
                SortableFields.CREATED_BY,
                SortableFields.LAST_UPDATED_BY,
                SortableFields.WEBHOOK_URL,
                SortableFields.WEBHOOK_SECRET_TOKEN);
    }
}
